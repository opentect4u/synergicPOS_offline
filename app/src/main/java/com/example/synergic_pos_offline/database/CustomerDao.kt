package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
        val balance: Double,
        /** Birthday (dob) and anniversary (dom), stored as "yyyy-MM-dd" or "". */
        val birthday: String = "",
        val anniversary: String = ""
    )

    /** All customers, oldest first. */
    fun getAll(): List<Customer> {
        val list = mutableListOf<Customer>()
        helper.readableDatabase.query(
            table,
            arrayOf(
                "id", "customer_name", "customer_address", "phone_number", "gstin",
                "credit_enabled", "credit_limit", "credit_days", "balance_amount", "dob", "dom"
            ),
            (if (currentStoreId() != null) "store_id = ?" else null),
            currentStoreId()?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
            table, COLUMNS, null, null, null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) list.add(c.toCustomer())
        }
        return list
    }

    /** The customer registered against [phone], or null when there is no match. */
    fun findByPhone(phone: String?): Customer? {
        if (phone.isNullOrBlank()) return null
        helper.readableDatabase.query(
            table, COLUMNS, "phone_number = ?", arrayOf(phone.trim()), null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) return c.toCustomer()
        }
        return null
    }

    /** The customer with this row id, or null when it no longer exists. */
    fun findById(id: Long): Customer? {
        helper.readableDatabase.query(
            table, COLUMNS, "id = ?", arrayOf(id.toString()), null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return c.toCustomer()
        }
        return null
    }

    /**
     * Customers whose name or phone number contains [term], for a type-ahead
     * lookup. A blank term matches nobody: an empty search box should offer
     * nothing rather than dump the whole master into a dropdown.
     *
     * Ordered by name so the list reads the way the operator scans it.
     */
    fun search(term: String, limit: Int = 25): List<Customer> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return emptyList()
        // LIKE's own wildcards in the typed text would silently widen the search.
        val pattern = "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val list = mutableListOf<Customer>()
        helper.readableDatabase.query(
            table, COLUMNS,
            "customer_name LIKE ? ESCAPE '!' OR phone_number LIKE ? ESCAPE '!'",
            arrayOf(pattern, pattern),
            null, null, "customer_name COLLATE NOCASE ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(c.toCustomer())
        }
        return list
    }

    /** Reads a customer off a cursor positioned on a row selected with [COLUMNS]. */
    private fun Cursor.toCustomer(): Customer = Customer(
        id = getLong(0),
        name = getString(1).orEmpty(),
        address = getString(2).orEmpty(),
        phone = getString(3).orEmpty(),
        gstin = getString(4).orEmpty(),
        creditEnabled = getInt(5) == 1,
        creditLimit = getDouble(6),
        balance = getDouble(7),
        birthday = getString(8).orEmpty(),
        anniversary = getString(9).orEmpty()
    )

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
        if (birthday.isBlank()) putNull("dob") else put("dob", birthday)
        if (anniversary.isBlank()) putNull("dom") else put("dom", anniversary)
        put("credit_enabled", if (creditEnabled) 1 else 0)
        put("credit_limit", creditLimit)
        // credit_days is no longer collected anywhere. The column stays on the
        // table so existing rows keep whatever was recorded against them, but
        // nothing here writes it any more.
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
        // The signed-in user's store is the current store; the registration row is
        // only a fallback (e.g. seeding before anyone has logged in).
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
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

    private companion object {
        /** Selected in this order by every read here; [Cursor.toCustomer] indexes it. */
        val COLUMNS = arrayOf(
            "id", "customer_name", "customer_address", "phone_number", "gstin",
            "credit_enabled", "credit_limit", "balance_amount", "dob", "dom"
        )
    }
}
