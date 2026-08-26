package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CRUD for [DatabaseHelper.Tables.MD_CHARGES] - the shop's own extra charges, added
 * to a bill on top of what was sold: service, packing, delivery.
 *
 * ## What a charge is
 *
 * A NAME and a PERCENTAGE, plus a switch. The percentage is taken on the bill's ITEM
 * LINES BEFORE ANY TAX - see [amountsOn] - so two items at 100 and 200 give a 5%
 * charge of 15, whatever tax is then charged on the goods themselves.
 *
 * ## Why three
 *
 * [MAX_CHARGES] is a limit on a slip, not on the database. Every enabled charge is a
 * line the customer reads between the goods and the total, and a bill that lists six
 * additions before its total stops reading as a bill for the things that were bought.
 * Three covers what a shop actually levies - service, packing, delivery - and the
 * master refuses a fourth rather than quietly printing it.
 *
 * Store-scoped by the signed-in user's store, like every other master.
 */
class ChargeDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_CHARGES

    data class Charge(
        val id: Long,
        val name: String,
        val percentage: Double,
        val enabled: Boolean
    )

    /** One charge worked out against a particular bill. */
    data class Applied(val name: String, val percentage: Double, val amount: Double)

    /** Every charge in the master, enabled or not - what the master screen lists. */
    fun getAll(): List<Charge> {
        val list = mutableListOf<Charge>()
        val store = currentStoreId()
        helper.readableDatabase.query(
            table, arrayOf("id", "charge_name", "percentage", "is_enabled"),
            (if (store != null) "store_id = ? AND is_active = 1" else "is_active = 1"),
            store?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Charge(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        percentage = c.getDouble(2),
                        enabled = c.getInt(3) != 0
                    )
                )
            }
        }
        return list
    }

    /**
     * The charges that actually apply to a sale: switched on, and worth something.
     *
     * A charge left at 0% is dropped as well as a disabled one - it would print a line
     * saying nothing was added, which is a line the customer has to read to learn it
     * did not matter.
     */
    fun enabled(): List<Charge> = getAll().filter { it.enabled && it.percentage > 0.0001 }

    /**
     * [enabled] charges worked out against [itemsTotal] - the sum of the bill's item
     * lines, before any tax.
     *
     * Each is a percentage of that same base, never of the running total: charging 5%
     * and then 10% of the result would make the second charge depend on the first, so
     * reordering the master would change the bill. Here the order only decides which
     * line prints first.
     */
    fun amountsOn(itemsTotal: Double): List<Applied> {
        if (itemsTotal <= 0.0) return emptyList()
        return enabled().map {
            Applied(it.name, it.percentage, round2(itemsTotal * it.percentage / 100.0))
        }
    }

    /** What [amountsOn] adds up to - the figure that joins the grand total. */
    fun totalOn(itemsTotal: Double): Double = round2(amountsOn(itemsTotal).sumOf { it.amount })

    fun insert(name: String, percentage: Double, enabled: Boolean): Long {
        val v = ContentValues().apply {
            put("store_id", currentStoreId())
            put("charge_name", name)
            put("percentage", percentage)
            put("is_enabled", if (enabled) 1 else 0)
            put("is_active", 1)
            put("created_at", now())
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, v)
    }

    fun update(id: Long, name: String, percentage: Double, enabled: Boolean): Int {
        val v = ContentValues().apply {
            put("charge_name", name)
            put("percentage", percentage)
            put("is_enabled", if (enabled) 1 else 0)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, v, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Removes charges by clearing is_active rather than deleting the rows.
     *
     * Bills already printed carry the charge they were printed with, and a report that
     * goes looking for the name behind an old figure should still find it.
     */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val v = ContentValues().apply {
            put("is_active", 0)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        var n = 0
        ids.forEach { n += helper.writableDatabase.update(table, v, "id = ?", arrayOf(it.toString())) }
        return n
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** The most charges a shop may define - see the note on the class. */
        const val MAX_CHARGES = 3
    }
}
