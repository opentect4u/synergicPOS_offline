package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live restaurant billing store. A "running order" is an open table's bill before
 * payment; its items accumulate as they are ordered, and KOT batches are cut from
 * the not-yet-printed items into [DatabaseHelper.Tables.TD_KOT].
 *
 * Store-scoped by the signed-in user's store. On payment the running order is
 * closed (removed) via [close].
 */
class RunningOrderDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val orders = DatabaseHelper.Tables.TD_RUNNING_ORDER
    private val items = DatabaseHelper.Tables.TD_RUNNING_ORDER_ITEMS

    data class RunningOrder(
        val id: Long, val tableCode: String, val section: String, val waiterId: Long?,
        val orderType: String, val phone: String, val cashier: String,
        val time: String, val total: Double, val note: String, val status: String
    )

    data class RunningItem(
        val id: Long, val productId: Long, val name: String,
        var qty: Double, var rate: Double, val kotQty: Double
    ) {
        /** Quantity not yet sent to the kitchen. */
        val pending: Double get() = (qty - kotQty).coerceAtLeast(0.0)
    }

    /** One KOT batch produced by [printKot], ready to print. */
    data class KotBatch(
        val kotNumber: String, val tableCode: String, val time: String,
        val lines: List<Pair<String, Double>>,   // name -> qty
        val note: String = ""
    )

    // ---- Orders ------------------------------------------------------------

    /** Opens a new running order for a table; returns its id (or -1 on failure). */
    fun createOrder(
        tableCode: String, section: String, waiterId: Long?,
        orderType: String, phone: String, cashier: String
    ): Long {
        val v = ContentValues().apply {
            put("store_id", currentStoreId())
            put("table_code", tableCode)
            put("section", section.ifBlank { null })
            if (waiterId != null) put("waiter_id", waiterId) else putNull("waiter_id")
            put("order_type", orderType)
            put("customer_phone", phone.ifBlank { null })
            put("cashier", cashier)
            put("status", "RUNNING")
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(orders, null, v)
    }

    /** All running orders for the current store (newest first), with live totals. */
    fun allRunning(): List<RunningOrder> {
        val list = mutableListOf<RunningOrder>()
        val store = currentStoreId()
        // All open tables (RUNNING or COMPLETED); paid orders are deleted, not listed.
        val where = if (store != null) "store_id = ?" else null
        val args = if (store != null) arrayOf(store.toString()) else null
        helper.readableDatabase.query(
            orders,
            arrayOf("id", "table_code", "section", "waiter_id", "order_type", "customer_phone", "cashier", "created_at", "order_note", "status"),
            where, args, null, null, "id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                list.add(
                    RunningOrder(
                        id = id,
                        tableCode = c.getString(1).orEmpty(),
                        section = c.getString(2).orEmpty(),
                        waiterId = if (c.isNull(3)) null else c.getLong(3),
                        orderType = c.getString(4).orEmpty(),
                        phone = c.getString(5).orEmpty(),
                        cashier = c.getString(6).orEmpty(),
                        time = formatTime(c.getString(7)),
                        total = totalOf(id),
                        note = c.getString(8).orEmpty(),
                        status = c.getString(9)?.ifBlank { "RUNNING" } ?: "RUNNING"
                    )
                )
            }
        }
        return list
    }

    /** Marks a table billed (Bill & Print): locked to changes but not yet paid/removed. */
    fun markCompleted(orderId: Long) {
        helper.writableDatabase.update(
            orders, ContentValues().apply { put("status", "COMPLETED") },
            "id = ?", arrayOf(orderId.toString())
        )
    }

    /** Saves the order note for a running order (shown when the table is re-selected). */
    fun setNote(orderId: Long, note: String) {
        helper.writableDatabase.update(
            orders, ContentValues().apply { put("order_note", note.ifBlank { null }) },
            "id = ?", arrayOf(orderId.toString())
        )
    }

    fun findByTable(tableCode: String): RunningOrder? =
        allRunning().firstOrNull { it.tableCode.equals(tableCode, ignoreCase = true) }

    /** Deletes a running order and its items (called after payment). */
    fun close(orderId: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(items, "running_order_id = ?", arrayOf(orderId.toString()))
            db.delete(orders, "id = ?", arrayOf(orderId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- Items -------------------------------------------------------------

    /**
     * Adds [qty] of a product to the order, merging into the existing line for the
     * same product+rate (the extra quantity becomes pending, tracked vs [RunningItem.kotQty]).
     */
    fun addItem(orderId: Long, productId: Long, name: String, qty: Double, rate: Double): Long {
        val db = helper.writableDatabase
        db.query(
            items, arrayOf("id", "quantity"),
            "running_order_id = ? AND product_id = ? AND rate = ?",
            arrayOf(orderId.toString(), productId.toString(), rate.toString()),
            null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) {
                val lineId = c.getLong(0)
                db.update(items, ContentValues().apply { put("quantity", c.getDouble(1) + qty) },
                    "id = ?", arrayOf(lineId.toString()))
                return lineId
            }
        }
        return db.insert(items, null, ContentValues().apply {
            put("running_order_id", orderId)
            put("product_id", productId)
            put("product_name", name)
            put("quantity", qty)
            put("rate", rate)
            put("kot_printed", 0)
            put("kot_qty", 0)
        })
    }

    fun itemsFor(orderId: Long): List<RunningItem> {
        val list = mutableListOf<RunningItem>()
        helper.readableDatabase.query(
            items, arrayOf("id", "product_id", "product_name", "quantity", "rate", "kot_qty"),
            "running_order_id = ?", arrayOf(orderId.toString()), null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    RunningItem(
                        id = c.getLong(0),
                        productId = c.getLong(1),
                        name = c.getString(2).orEmpty(),
                        qty = c.getDouble(3),
                        rate = c.getDouble(4),
                        kotQty = c.getDouble(5)
                    )
                )
            }
        }
        return list
    }

    /** Sets a line's total quantity; a reduction below what's already sent caps kot_qty. */
    fun setItemQty(itemId: Long, qty: Double) {
        if (qty <= 0) { helper.writableDatabase.delete(items, "id = ?", arrayOf(itemId.toString())); return }
        helper.writableDatabase.execSQL(
            "UPDATE $items SET quantity = ?, kot_qty = MIN(kot_qty, ?) WHERE id = ?",
            arrayOf<Any>(qty, qty, itemId)
        )
    }

    fun removeItem(itemId: Long) {
        helper.writableDatabase.delete(items, "id = ?", arrayOf(itemId.toString()))
    }

    /** The pending KOT for preview — same content [printKot] would cut, but writes nothing. */
    fun peekPending(orderId: Long, tableCode: String, note: String = ""): KotBatch? {
        val pending = itemsFor(orderId).filter { it.pending > 0.0 }
        if (pending.isEmpty()) return null
        return KotBatch(
            kotNumber = nextKotNumber(), tableCode = tableCode,
            time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()),
            lines = pending.map { it.name to it.pending }, note = note.trim()
        )
    }

    private fun totalOf(orderId: Long): Double {
        helper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(quantity * rate), 0) FROM $items WHERE running_order_id = ?",
            arrayOf(orderId.toString())
        ).use { c -> if (c.moveToFirst()) return c.getDouble(0) }
        return 0.0
    }

    // ---- KOT ---------------------------------------------------------------

    /**
     * Cuts a KOT for the PENDING quantity of each line (total − already sent):
     * writes a [DatabaseHelper.Tables.TD_KOT] header + items with just the delta,
     * bumps each line's kot_qty to its full quantity, and returns the batch to
     * print. Null when nothing new is pending. This is what makes an item's added
     * quantity go to the kitchen without re-sending what was already ordered.
     */
    fun printKot(orderId: Long, tableCode: String, waiterId: Long?, note: String = ""): KotBatch? {
        val pending = itemsFor(orderId).filter { it.pending > 0.0 }
        if (pending.isEmpty()) return null

        val db = helper.writableDatabase
        val kotNumber = nextKotNumber()
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)

        db.beginTransaction()
        try {
            val kotId = db.insert(DatabaseHelper.Tables.TD_KOT, null, ContentValues().apply {
                putNull("bill_id")
                put("kot_number", kotNumber)
                put("table_number", tableCode)
                if (waiterId != null) put("waiter_id", waiterId) else putNull("waiter_id")
                put("kot_date", date)
                put("kot_time", time)
                put("status", "OPEN")
                put("created_by", currentUser())
            })
            pending.forEach { it2 ->
                db.insert(DatabaseHelper.Tables.TD_KOT_ITEMS, null, ContentValues().apply {
                    put("kot_id", kotId)
                    put("product_id", it2.productId)
                    put("quantity", it2.pending)          // only the added quantity
                    put("status", "PENDING")
                    put("created_by", currentUser())
                })
                // Everything up to the current quantity is now sent.
                db.update(items, ContentValues().apply {
                    put("kot_qty", it2.qty); put("kot_printed", 1)
                }, "id = ?", arrayOf(it2.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return KotBatch(
            kotNumber = kotNumber, tableCode = tableCode,
            time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now),
            lines = pending.map { it.name to it.pending },
            note = note.trim()
        )
    }

    /** Next KOT number for the store, formatted like KOT-0007. */
    fun nextKotNumber(): String {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_KOT}", null).use { c ->
            val n = if (c.moveToFirst()) c.getLong(0) + 1 else 1L
            return "KOT-" + n.toString().padStart(4, '0')
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun formatTime(dbTime: String?): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(dbTime ?: "")
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(parsed ?: Date())
    }.getOrDefault(dbTime.orEmpty())

    private fun currentStoreId(): Long? {
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private fun currentUser(): String? = SessionManager.currentUser?.userId
}
