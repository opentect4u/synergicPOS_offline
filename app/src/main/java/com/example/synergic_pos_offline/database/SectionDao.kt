package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CRUD for restaurant sections/floors ([DatabaseHelper.Tables.MD_SECTION]).
 *
 * The Price List a section bills at is a product-rate tier: the dropdown options
 * come from the distinct `rate_name`s in md_product_rates, each keyed by a
 * representative rate id stored in `price_list_id`.
 */
class SectionDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_SECTION

    data class Section(
        val id: Long,
        val name: String,
        val noOfTables: Int,
        val priceListId: Long?,
        val serviceCharge: Double,
        val isActive: Boolean
    )

    /** A selectable price list (a product-rate tier). */
    data class PriceList(val id: Long, val name: String)

    fun getAll(): List<Section> {
        val list = mutableListOf<Section>()
        helper.readableDatabase.query(
            table,
            arrayOf("id", "section_name", "no_of_tables", "price_list_id", "service_charge", "is_active"),
            (if (currentStoreId() != null) "store_id = ?" else null),
            currentStoreId()?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Section(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        noOfTables = c.getInt(2),
                        priceListId = if (c.isNull(3)) null else c.getLong(3),
                        serviceCharge = c.getDouble(4),
                        isActive = c.getInt(5) == 1
                    )
                )
            }
        }
        return list
    }

    /** Distinct product-rate tiers (Rate 1 / Rate 2 …) for the current store. */
    fun priceLists(): List<PriceList> {
        val list = mutableListOf<PriceList>()
        val store = currentStoreId()
        val storeClause = if (store != null) "AND store_id = ?" else ""
        val args = if (store != null) arrayOf(store.toString()) else null
        helper.readableDatabase.rawQuery(
            "SELECT MIN(id) AS pid, rate_name FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} " +
                "WHERE rate_name IS NOT NULL AND TRIM(rate_name) != '' $storeClause " +
                "GROUP BY rate_name ORDER BY rate_name COLLATE NOCASE",
            args
        ).use { c ->
            while (c.moveToNext()) {
                list.add(PriceList(c.getLong(0), c.getString(1).orEmpty()))
            }
        }
        return list
    }

    fun insert(section: Section): Long =
        helper.writableDatabase.insert(table, null, section.toValues(isNew = true))

    fun update(id: Long, section: Section): Int =
        helper.writableDatabase.update(table, section.toValues(isNew = false), "id=?", arrayOf(id.toString()))

    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        helper.writableDatabase.delete(table, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
    }

    private fun Section.toValues(isNew: Boolean): ContentValues = ContentValues().apply {
        put("section_name", name)
        put("no_of_tables", noOfTables)
        if (priceListId != null) put("price_list_id", priceListId) else putNull("price_list_id")
        put("service_charge", serviceCharge)
        put("is_active", if (isActive) 1 else 0)
        if (isNew) {
            put("store_id", currentStoreId())
            put("created_by", currentUser())
        } else {
            put("modified_at", now())
            put("modified_by", currentUser())
        }
    }

    private fun currentStoreId(): Long? {
        // The signed-in user's store is the current store; the registration row is
        // only a fallback (e.g. seeding before anyone has logged in).
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
