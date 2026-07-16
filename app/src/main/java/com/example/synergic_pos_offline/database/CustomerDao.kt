package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer for the [DatabaseHelper.Tables.MD_CUSTOMERS] master table.
 */
class CustomerDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_CUSTOMERS

    /** A single customer row. */
    data class Customer(
        val id: Long,
        val name: String,
        val address: String,
        val phone: String,
        val gstin: String,
        val creditEnabled: Boolean,
        val creditLimit: Double,
        val creditDays: Int,
        val balance: Double
    )

    /** All customers, oldest first. */
    fun getAll(): List<Customer> {
        val list = mutableListOf<Customer>()
        helper.readableDatabase.query(
            table,
            arrayOf(
                "id", "customer_name", "customer_address", "phone_number", "gstin",
                "credit_enabled", "credit_limit", "credit_days", "balance_amount"
            ),
            null, null, null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Customer(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        address = c.getString(2).orEmpty(),
                        phone = c.getString(3).orEmpty(),
                        gstin = c.getString(4).orEmpty(),
                        creditEnabled = c.getInt(5) == 1,
                        creditLimit = c.getDouble(6),
                        creditDays = c.getInt(7),
                        balance = c.getDouble(8)
                    )
                )
            }
        }
        return list
    }

    /** Inserts a new customer and returns its new row id (or -1 on failure). */
    fun insert(customer: Customer): Long {
        return helper.writableDatabase.insert(table, null, customer.toValues(isNew = true))
    }

    /** Updates the customer identified by [id]. */
    fun update(id: Long, customer: Customer): Int {
        return helper.writableDatabase.update(
            table, customer.toValues(isNew = false), "id=?", arrayOf(id.toString())
        )
    }

    /** Deletes every customer in [ids]. */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        return helper.writableDatabase.delete(table, "id IN ($placeholders)", args)
    }

    private fun Customer.toValues(isNew: Boolean): ContentValues = ContentValues().apply {
        put("customer_name", name)
        put("customer_address", address)
        put("phone_number", phone)
        put("gstin", gstin)
        put("credit_enabled", if (creditEnabled) 1 else 0)
        put("credit_limit", creditLimit)
        put("credit_days", creditDays)
        put("balance_amount", balance)
        if (isNew) {
            put("store_id", currentStoreId())
            put("created_by", currentUser())
        } else {
            put("modified_at", now())
            put("modified_by", currentUser())
        }
    }

    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
