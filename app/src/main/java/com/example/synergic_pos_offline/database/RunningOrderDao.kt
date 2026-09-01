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
        val time: String, val total: Double, val note: String, val status: String,
        /**
         * Tables folded into this bill by a merge, in the order they joined.
         *
         * Read with the order rather than looked up per card: the sale screen names
         * every open table on every redraw, and a query behind each name would be a
         * query per table per tap.
         */
        val mergedTables: List<String> = emptyList(),
        /**
         * The whole-bill discount typed against this table, with [discountType] saying
         * how to read it - "A" for a flat amount, anything else a percentage.
         *
         * Read with the order rather than held on the screen, because a restaurant
         * prints the bill and settles it later with other tables served in between.
         * See the note on the column in DatabaseHelper.
         */
        val discount: Double = 0.0,
        val discountType: String? = null
    )

    data class RunningItem(
        val id: Long, val productId: Long, val name: String,
        var qty: Double, var rate: Double, val kotQty: Double,
        val cgstRate: Double = 0.0, val sgstRate: Double = 0.0,
        /** Carried so a VAT-rated dish is still VAT-rated when the bill is priced. */
        val vatRate: Double = 0.0,
        /**
         * The product's own discount, as configured on its rate row, snapshotted when
         * the line was added - Tax Settings' item-wise discount. Carried for the same
         * reason the tax rates above are: a table open across a price change is billed
         * at what it was sold at.
         */
        val discValue: Double = 0.0,
        /** "A" for a flat amount, otherwise a percentage. Null when the line has none. */
        val discType: String? = null
    ) {
        /** Quantity newly added, not yet sent to the kitchen. */
        val pending: Double get() = (qty - kotQty).coerceAtLeast(0.0)
        /** Quantity already sent to the kitchen but since removed — to be cancelled. */
        val pendingCancel: Double get() = (kotQty - qty).coerceAtLeast(0.0)
    }

    /** One KOT batch produced by [printKot], ready to print. */
    data class KotBatch(
        val kotNumber: String, val tableCode: String, val section: String, val time: String,
        /**
         * The day the ticket was cut, "dd-MM-yyyy".
         *
         * A KOT is a working document that outlives its shift: tickets are spiked at
         * the pass and kept, and a stack of them carrying only "03:45 PM" cannot be
         * told apart the next morning - not for a dispute over what was ordered, not
         * for a count of covers, not for matching a cancelled item back to its day.
         *
         * Defaulted so nothing that builds a batch has to be changed to keep working;
         * both real callers set it.
         */
        val date: String = "",
        val lines: List<Pair<String, Double>>,          // newly-added:   name -> qty
        val cancelLines: List<Pair<String, Double>> = emptyList(),  // cancelled: name -> qty
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
            arrayOf(
                "id", "table_code", "section", "waiter_id", "order_type", "customer_phone",
                "cashier", "created_at", "order_note", "status", "merged_tables",
                "bill_discount", "bill_discount_type"
            ),
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
                        status = c.getString(9)?.ifBlank { "RUNNING" } ?: "RUNNING",
                        mergedTables = c.getString(10).orEmpty()
                            .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        discount = if (c.isNull(11)) 0.0 else c.getDouble(11),
                        discountType = c.getString(12)?.takeIf { it.isNotBlank() }
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
        closeKot(orderId)   // Bill & Print → the table's KOT is CLOSED
    }

    /**
     * Moves a running order to another table (same section): updates the running
     * order's table_code and its OPEN KOT's table_number so the kitchen sees the new
     * table. Section/waiter are unchanged (same-section transfer).
     */
    fun transferTable(orderId: Long, newTableCode: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.update(orders, ContentValues().apply { put("table_code", newTableCode) },
                "id = ?", arrayOf(orderId.toString()))
            db.update(
                DatabaseHelper.Tables.TD_KOT, ContentValues().apply { put("table_number", newTableCode) },
                "running_order_id = ? AND status = 'OPEN'", arrayOf(orderId.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Merges [sourceId]'s order into [targetId]: moves every source line into the
     * target (merging same product+rate lines) while preserving each line's already-
     * sent quantity (kot_qty), then removes the source order/items and closes its
     * KOT. The target's PENDING KOT is refreshed so only not-yet-sent items remain
     * to send. Section/waiter of the target are kept (same-section merge).
     */
    /**
     * Joins a table that has NO order of its own onto [targetId]'s bill.
     *
     * The case [mergeOrders] cannot cover: a party grows and takes the empty table
     * beside it. There is nothing to move - the table has no items - so all that
     * happens is that the code is recorded against the kept order, which is what makes
     * it occupied for as long as that order lives and frees it when the order settles.
     *
     * Idempotent: joining a table already on the bill changes nothing, so a double tap
     * cannot record it twice and leave it half-freed later.
     */
    fun attachTable(targetId: Long, tableCode: String) {
        if (tableCode.isBlank()) return
        val merged = (mergedTablesOf(targetId) + tableCode)
            .distinctBy { it.lowercase() }
            .joinToString(",")
        helper.writableDatabase.update(
            orders, ContentValues().apply { put("merged_tables", merged) },
            "id = ?", arrayOf(targetId.toString())
        )
    }

    fun mergeOrders(targetId: Long, sourceId: Long) {
        val sourceItems = itemsFor(sourceId)
        // The source table (and any tables it had itself absorbed) now belong to the target.
        val sourceCode = orderRef(sourceId).first
        val carried = (listOf(sourceCode) + mergedTablesOf(sourceId)).filter { it.isNotBlank() }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            sourceItems.forEach { src ->
                val existing = db.query(
                    items, arrayOf("id", "quantity", "kot_qty"),
                    "running_order_id = ? AND product_id = ? AND rate = ?",
                    arrayOf(targetId.toString(), src.productId.toString(), src.rate.toString()),
                    null, null, "id ASC", "1"
                ).use { c ->
                    if (c.moveToFirst()) Triple(c.getLong(0), c.getDouble(1), c.getDouble(2)) else null
                }
                if (existing != null) {
                    db.update(items, ContentValues().apply {
                        put("quantity", existing.second + src.qty)
                        put("kot_qty", existing.third + src.kotQty)
                        if (existing.third + src.kotQty > 0) put("kot_printed", 1)
                    }, "id = ?", arrayOf(existing.first.toString()))
                } else {
                    db.insert(items, null, ContentValues().apply {
                        put("running_order_id", targetId)
                        put("product_id", src.productId)
                        put("product_name", src.name)
                        put("quantity", src.qty)
                        put("rate", src.rate)
                        put("cgst_rate", src.cgstRate)
                        put("sgst_rate", src.sgstRate)
                        put("vat_rate", src.vatRate)
                        // The line's own discount travels with it. Left behind, a
                        // merged line arrived on the other table priced at full while
                        // the same line on the table it came from had been discounted -
                        // so merging quietly changed what the food cost. Everything
                        // else that prices a line is copied here; this was missed when
                        // the item-wise discount was added.
                        put("discount", src.discValue)
                        if (src.discType.isNullOrBlank()) putNull("discount_type")
                        else put("discount_type", src.discType)
                        put("kot_qty", src.kotQty)
                        put("kot_printed", if (src.kotQty > 0) 1 else 0)
                    })
                }
            }
            // Remember the merged-away tables on the target so they can be freed only
            // when the target order is settled (they stay occupied as part of the merge).
            val merged = (mergedTablesOf(targetId) + carried).distinct().joinToString(",")
            db.update(orders, ContentValues().apply { put("merged_tables", merged) },
                "id = ?", arrayOf(targetId.toString()))
            closeKot(sourceId)   // the merged-away table's KOT is closed (kept as history)
            db.delete(items, "running_order_id = ?", arrayOf(sourceId.toString()))
            db.delete(orders, "id = ?", arrayOf(sourceId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        syncPendingKot(targetId)   // refresh the target's PENDING KOT items
    }

    /** Table codes merged into [orderId] (empty if none) — they share this order's bill. */
    fun mergedTablesOf(orderId: Long): List<String> {
        helper.readableDatabase.query(
            orders, arrayOf("merged_tables"), "id = ?", arrayOf(orderId.toString()), null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    /** Sets a running order's status (e.g. RUNNING ↔ HOLD). */
    fun setStatus(orderId: Long, status: String) {
        helper.writableDatabase.update(
            orders, ContentValues().apply { put("status", status) }, "id = ?", arrayOf(orderId.toString())
        )
    }

    /** Saves the order note for a running order (shown when the table is re-selected). */
    fun setNote(orderId: Long, note: String) {
        helper.writableDatabase.update(
            orders, ContentValues().apply { put("order_note", note.ifBlank { null }) },
            "id = ?", arrayOf(orderId.toString())
        )
    }

    /**
     * Puts a customer's phone on a running order.
     *
     * For the take-away flow, where the customer is asked for as the order is started
     * and an empty token that is already open is reused rather than a second one being
     * cut. Blank clears it, so skipping the prompt on a reused order does not leave the
     * previous customer attached to somebody else's food.
     */
    fun setPhone(orderId: Long, phone: String) {
        helper.writableDatabase.update(
            orders, ContentValues().apply { put("customer_phone", phone.ifBlank { null }) },
            "id = ?", arrayOf(orderId.toString())
        )
    }

    /**
     * Holds the whole-bill discount typed against this table, until it settles.
     *
     * The counterpart of [setBillSeq], and there for the same reason: what the printed
     * slip says has to survive the operator serving another table and coming back. A
     * discount that lived only on the screen was cleared by the next table opened, so
     * the guest held a slip saying 5% off and the settlement charged the full amount.
     *
     * [type] is "A" for a flat rupee amount, anything else a percentage - the
     * convention the item rows already use for their own discounts.
     */
    fun setDiscount(orderId: Long, value: Double, type: String?) {
        helper.writableDatabase.update(
            orders,
            ContentValues().apply {
                put("bill_discount", value)
                if (type.isNullOrBlank()) putNull("bill_discount_type") else put("bill_discount_type", type)
            },
            "id = ?", arrayOf(orderId.toString())
        )
    }

    fun findByTable(tableCode: String, section: String): RunningOrder? =
        allRunning().firstOrNull {
            it.tableCode.equals(tableCode, ignoreCase = true) &&
                (section.isBlank() || it.section.equals(section, ignoreCase = true))
        }

    /**
     * Holds the bill number this order's slip was printed under, until it settles.
     *
     * Written at Print Bill and read back by the settlement, so the two agree. It also
     * takes the number out of circulation while the order is open - BillDao counts
     * these when working out the next one, which is what stops the table after this
     * one printing the same number.
     */
    fun setBillSeq(orderId: Long, seq: Int) {
        helper.writableDatabase.update(
            orders, ContentValues().apply { put("bill_seq_no", seq) },
            "id = ?", arrayOf(orderId.toString())
        )
    }

    /** The number reserved for this order at Print Bill, or null if it has none. */
    fun billSeqOf(orderId: Long): Int? = runCatching {
        helper.readableDatabase.query(
            orders, arrayOf("bill_seq_no"), "id = ?", arrayOf(orderId.toString()), null, null, null, "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
    }.getOrNull()

    /** Empties an order (deletes its items, closes its KOT) but keeps the order row —
     *  used to reset a split sub-table so it stays available to re-order. */
    fun clearItems(orderId: Long) {
        closeKot(orderId)
        helper.writableDatabase.delete(items, "running_order_id = ?", arrayOf(orderId.toString()))
    }

    /** Deletes a running order and its items (called after payment). */
    fun close(orderId: Long) {
        closeKot(orderId)   // settled → ensure the KOT is CLOSED (kept as history)
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
    fun addItem(
        orderId: Long, productId: Long, name: String, qty: Double, rate: Double,
        cgstRate: Double = 0.0, sgstRate: Double = 0.0, vatRate: Double = 0.0,
        discValue: Double = 0.0, discType: String? = null
    ): Long {
        val db = helper.writableDatabase
        val lineId = db.query(
            items, arrayOf("id", "quantity"),
            "running_order_id = ? AND product_id = ? AND rate = ?",
            arrayOf(orderId.toString(), productId.toString(), rate.toString()),
            null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.update(items, ContentValues().apply { put("quantity", c.getDouble(1) + qty) },
                    "id = ?", arrayOf(id.toString()))
                id
            } else {
                db.insert(items, null, ContentValues().apply {
                    put("running_order_id", orderId)
                    put("product_id", productId)
                    put("product_name", name)
                    put("quantity", qty)
                    put("rate", rate)
                    put("cgst_rate", cgstRate)
                    put("sgst_rate", sgstRate)
                    put("vat_rate", vatRate)
                    put("discount", discValue)
                    if (discType.isNullOrBlank()) putNull("discount_type") else put("discount_type", discType)
                    put("kot_printed", 0)
                    put("kot_qty", 0)
                })
            }
        }
        // Adding an item opens a KOT (if not already) and mirrors the new items as
        // PENDING under it — the "add item → td_kot OPEN, td_kot_item PENDING" step.
        syncPendingKot(orderId)
        return lineId
    }

    fun itemsFor(orderId: Long): List<RunningItem> {
        val list = mutableListOf<RunningItem>()
        helper.readableDatabase.query(
            items, arrayOf(
                "id", "product_id", "product_name", "quantity", "rate", "kot_qty",
                "cgst_rate", "sgst_rate", "vat_rate", "discount", "discount_type"
            ),
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
                        kotQty = c.getDouble(5),
                        cgstRate = c.getDouble(6),
                        sgstRate = c.getDouble(7),
                        vatRate = c.getDouble(8),
                        discValue = if (c.isNull(9)) 0.0 else c.getDouble(9),
                        discType = c.getString(10)?.takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        return list
    }

    /**
     * Sets a line's total quantity. kot_qty (what's already gone to the kitchen) is
     * kept, so reducing/removing a sent item leaves a pending cancellation to print.
     * A line only vanishes when nothing was ever sent (kot_qty = 0).
     */
    fun setItemQty(itemId: Long, qty: Double) {
        val orderId = orderIdOfItem(itemId)
        val kotQty = kotQtyOf(itemId)
        if (qty <= 0 && kotQty <= 0.0) {
            helper.writableDatabase.delete(items, "id = ?", arrayOf(itemId.toString()))
        } else {
            helper.writableDatabase.execSQL(
                "UPDATE $items SET quantity = ? WHERE id = ?", arrayOf<Any>(qty.coerceAtLeast(0.0), itemId)
            )
        }
        orderId?.let { syncPendingKot(it) }
    }

    /**
     * Sets a line's quantity AND its rate together - what the product popup returns
     * when a cart line is opened and changed.
     *
     * The rate matters as much as the quantity: the popup can price a line manually
     * (App Settings' Manual Rate), and writing the quantity back while leaving the old
     * rate would charge the table something nobody agreed. Re-adding the item instead
     * would not do either - addItem matches on product AND rate, so a re-rated line
     * becomes a second row beside the first rather than a correction of it.
     *
     * Quantity follows the same rule as [setItemQty]: a line only disappears when
     * nothing of it was ever sent to the kitchen, otherwise it stays at zero so the
     * cancellation can be printed.
     */
    fun setItemLine(itemId: Long, qty: Double, rate: Double) {
        val orderId = orderIdOfItem(itemId)
        val kotQty = kotQtyOf(itemId)
        if (qty <= 0 && kotQty <= 0.0) {
            helper.writableDatabase.delete(items, "id = ?", arrayOf(itemId.toString()))
        } else {
            helper.writableDatabase.execSQL(
                "UPDATE $items SET quantity = ?, rate = ? WHERE id = ?",
                arrayOf<Any>(qty.coerceAtLeast(0.0), rate, itemId)
            )
        }
        orderId?.let { syncPendingKot(it) }
    }

    fun removeItem(itemId: Long) {
        val orderId = orderIdOfItem(itemId)
        if (kotQtyOf(itemId) > 0.0) {
            // Already sent — keep the row at qty 0 so the cancellation can be printed.
            helper.writableDatabase.execSQL("UPDATE $items SET quantity = 0 WHERE id = ?", arrayOf<Any>(itemId))
        } else {
            helper.writableDatabase.delete(items, "id = ?", arrayOf(itemId.toString()))
        }
        orderId?.let { syncPendingKot(it) }
    }

    /** True if the order has anything to send: newly-added or cancelled items. */
    fun hasPendingKot(orderId: Long): Boolean =
        itemsFor(orderId).any { it.pending > 0.0 || it.pendingCancel > 0.0 }

    /**
     * True if any item has been sent to the kitchen and is still on the order (not
     * yet cancelled). An order can only be cancelled outright when this is false —
     * i.e. before any KOT, or once every sent item has been cancelled.
     */
    fun hasSentActiveItems(orderId: Long): Boolean =
        itemsFor(orderId).any { it.kotQty > 0.0 && it.qty > 0.0 }

    private fun kotQtyOf(itemId: Long): Double {
        helper.readableDatabase.query(
            items, arrayOf("kot_qty"), "id = ?", arrayOf(itemId.toString()), null, null, null, "1"
        ).use { c -> if (c.moveToFirst()) return c.getDouble(0) }
        return 0.0
    }

    /** The pending KOT for preview — same content [printKot] would cut, but writes nothing. */
    fun peekPending(orderId: Long, tableCode: String, section: String = "", note: String = ""): KotBatch? {
        val all = itemsFor(orderId)
        val adds = all.filter { it.pending > 0.0 }
        val cancels = all.filter { it.pendingCancel > 0.0 }
        if (adds.isEmpty() && cancels.isEmpty()) return null
        return KotBatch(
            kotNumber = nextKotNumber(), tableCode = tableCode, section = section,
            time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()),
            date = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date()),
            lines = adds.map { it.name to it.pending },
            cancelLines = cancels.map { it.name to it.pendingCancel },
            note = note.trim()
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
     * Sends a table's KOT delta to the kitchen: newly-added items (PENDING → COMPLETE)
     * plus cancellations for items that were sent and since removed/reduced. Each
     * line's kot_qty is synced to its current quantity, and fully-removed lines are
     * deleted afterwards. The KOT header stays OPEN (CLOSED only at Bill & Print).
     * Null when there's nothing new to add or cancel.
     */
    fun printKot(orderId: Long, tableCode: String, waiterId: Long?, section: String = "", note: String = ""): KotBatch? {
        syncPendingKot(orderId)          // ensure PENDING reflects the current cart
        val all = itemsFor(orderId)
        val adds = all.filter { it.pending > 0.0 }
        val cancels = all.filter { it.pendingCancel > 0.0 }
        if (adds.isEmpty() && cancels.isEmpty()) return null

        val db = helper.writableDatabase
        val kotId = ensureOpenKot(orderId)
        val now = Date()

        db.beginTransaction()
        try {
            // Added items are now sent → flip their PENDING rows to COMPLETE.
            db.update(
                DatabaseHelper.Tables.TD_KOT_ITEMS,
                ContentValues().apply { put("status", "COMPLETE") },
                "kot_id = ? AND status = 'PENDING'", arrayOf(kotId.toString())
            )
            // Record a CANCELLED KOT row for each removed/reduced sent item.
            cancels.forEach { ri ->
                db.insert(DatabaseHelper.Tables.TD_KOT_ITEMS, null, ContentValues().apply {
                    put("kot_id", kotId)
                    put("product_id", ri.productId)
                    put("quantity", ri.pendingCancel)
                    put("status", "CANCELLED")
                    put("created_by", currentUser())
                })
            }
            // Sync each running line's kot_qty to its current quantity; drop emptied lines.
            all.forEach { ri ->
                if (ri.qty <= 0.0) {
                    db.delete(items, "id = ?", arrayOf(ri.id.toString()))
                } else if (ri.pending > 0.0 || ri.pendingCancel > 0.0) {
                    db.update(items, ContentValues().apply {
                        put("kot_qty", ri.qty); put("kot_printed", 1)
                    }, "id = ?", arrayOf(ri.id.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return KotBatch(
            kotNumber = kotNumberOf(kotId), tableCode = tableCode, section = section,
            time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now),
            date = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(now),
            lines = adds.map { it.name to it.pending },
            cancelLines = cancels.map { it.name to it.pendingCancel },
            note = note.trim()
        )
    }

    /** Marks a table's KOT(s) CLOSED — the "Bill & Print → td_kot CLOSED" step. */
    fun closeKot(orderId: Long) {
        helper.writableDatabase.update(
            DatabaseHelper.Tables.TD_KOT,
            ContentValues().apply { put("status", "CLOSED") },
            "running_order_id = ? AND status <> 'CLOSED'", arrayOf(orderId.toString())
        )
    }

    // ---- KOT internals -----------------------------------------------------

    /** Finds the OPEN KOT for a running order, creating one (OPEN) if none exists. */
    private fun ensureOpenKot(orderId: Long): Long {
        val db = helper.writableDatabase
        db.query(
            DatabaseHelper.Tables.TD_KOT, arrayOf("id"),
            "running_order_id = ? AND status = 'OPEN'", arrayOf(orderId.toString()),
            null, null, "id ASC", "1"
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }

        val ref = orderRef(orderId)
        val now = Date()
        return db.insert(DatabaseHelper.Tables.TD_KOT, null, ContentValues().apply {
            putNull("bill_id")
            put("running_order_id", orderId)
            put("kot_number", nextKotNumber())
            put("table_number", ref.first)
            if (ref.second != null) put("waiter_id", ref.second) else putNull("waiter_id")
            put("kot_date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
            put("kot_time", SimpleDateFormat("HH:mm:ss", Locale.US).format(now))
            put("status", "OPEN")
            put("created_by", currentUser())
        })
    }

    /**
     * Mirrors the order's not-yet-sent items as PENDING rows under its OPEN KOT:
     * clears the current PENDING set and re-inserts one row per pending line. Items
     * already sent (COMPLETE) are untouched, so incremental KOTs stay intact.
     */
    private fun syncPendingKot(orderId: Long) {
        val db = helper.writableDatabase
        val kotId = ensureOpenKot(orderId)
        db.delete(DatabaseHelper.Tables.TD_KOT_ITEMS, "kot_id = ? AND status = 'PENDING'", arrayOf(kotId.toString()))
        itemsFor(orderId).filter { it.pending > 0.0 }.forEach { ri ->
            db.insert(DatabaseHelper.Tables.TD_KOT_ITEMS, null, ContentValues().apply {
                put("kot_id", kotId)
                put("product_id", ri.productId)
                put("quantity", ri.pending)
                put("status", "PENDING")
                put("created_by", currentUser())
            })
        }
    }

    /** table_code + waiter_id for a running order, for KOT headers. */
    private fun orderRef(orderId: Long): Pair<String, Long?> {
        helper.readableDatabase.query(
            orders, arrayOf("table_code", "waiter_id"),
            "id = ?", arrayOf(orderId.toString()), null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0).orEmpty() to (if (c.isNull(1)) null else c.getLong(1))
        }
        return "" to null
    }

    /** The running order an item line belongs to, or null. */
    private fun orderIdOfItem(itemId: Long): Long? {
        helper.readableDatabase.query(
            items, arrayOf("running_order_id"), "id = ?", arrayOf(itemId.toString()), null, null, null, "1"
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    /** The KOT number stored on a KOT row. */
    private fun kotNumberOf(kotId: Long): String {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.TD_KOT, arrayOf("kot_number"), "id = ?", arrayOf(kotId.toString()), null, null, null, "1"
        ).use { c -> if (c.moveToFirst()) return c.getString(0).orEmpty() }
        return nextKotNumber()
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

    private fun currentUser(): String? = SessionManager.auditUser
}
