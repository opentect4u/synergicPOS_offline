package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every read and write of stock on hand: the batches in
 * [DatabaseHelper.Tables.MD_BATCH_STOCK] and the movements that explain them in
 * [DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS].
 *
 * The two are always written together and inside one transaction. A count with no
 * movement behind it cannot be accounted for, and a movement that never reached the
 * count is a lie about what is on the shelf - so neither is allowed to happen alone.
 */
class StockDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)

    /** Which way a movement took the count. Stored in td_stock_transactions.stock_flow. */
    enum class Flow(val code: String) { IN("IN"), OUT("OUT") }

    /** Why stock is leaving. Each maps to the transaction_type the schema allows. */
    enum class OutReason(val label: String, val transactionType: String) {
        RETURN("Return", "RETURN"),
        DAMAGE("Damage", "DAMAGE_WRITEOFF");

        companion object {
            fun fromLabel(value: String?): OutReason? =
                entries.firstOrNull { it.label.equals(value?.trim(), true) }
        }
    }

    /** One line of the stock table: a product and what is on hand across its batches. */
    data class StockItem(
        val productId: Int,
        val name: String,
        val category: String,
        val stock: Double
    )

    // ---- Reads ---------------------------------------------------------------

    /**
     * Every product with the quantity on hand across all of its batches.
     *
     * Products with no batch at all still list, at zero: an item that has never been
     * received is exactly the item someone opens Stock In to receive, so leaving it
     * out would hide it from the screen that exists to fix that.
     */
    fun items(storeId: Int): List<StockItem> {
        val sql = """
            SELECT p.id, p.product_name, COALESCE(c.category_name,''),
                   COALESCE((SELECT SUM(s.current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s
                             WHERE s.product_id = p.id), 0)
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            LEFT JOIN ${DatabaseHelper.Tables.MD_CATEGORY} c ON c.id = p.category_id
            WHERE p.store_id = ?
            ORDER BY p.product_name COLLATE NOCASE
        """.trimIndent()

        val out = mutableListOf<StockItem>()
        helper.readableDatabase.rawQuery(sql, arrayOf(storeId.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(StockItem(c.getInt(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getDouble(3)))
            }
        }
        return out
    }

    /**
     * What the sale screen needs to know about one product's stock: how much is on
     * hand, and the quantity at or below which it counts as running low.
     */
    data class StockLevel(val quantity: Double, val alertQty: Double) {
        /** Nothing left to sell. A negative count is out too - it is worse than empty. */
        val isOut: Boolean get() = quantity <= 0.0

        /**
         * Running low, which only means anything while there is still something on
         * the shelf: at zero the item is out, and saying "low" about nothing left
         * would put the softer of the two badges on the worse of the two states.
         */
        val isLow: Boolean get() = !isOut && alertQty > 0.0 && quantity <= alertQty
    }

    /**
     * Stock on hand for every product in the store, keyed by product id.
     *
     * The low-stock threshold is General Settings' Alert Quantity, for every product
     * alike. `md_products.stock_alert_qty` is deliberately *not* consulted: it is
     * carried by seeded and imported products, has no field on the product form to
     * set or correct it, and letting it take precedence made the setting look broken
     * - an item sitting at 10 against an alert of 20 would read as fine because a
     * figure nobody could see said its level was 5.
     *
     * Comes back as 0 (never low) whenever Stock Alert is off, so the caller does not
     * have to ask the setting a second time.
     */
    fun levels(storeId: Int): Map<Long, StockLevel> {
        val settings = GeneralSettingsDao(appContext).load()
        val alertOn = settings.stockFlag && settings.stockAlert
        val alertQty = if (alertOn) settings.stockAlertQty.toDouble() else 0.0

        val sql = """
            SELECT p.id,
                   COALESCE((SELECT SUM(s.current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s
                             WHERE s.product_id = p.id), 0)
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            WHERE p.store_id = ?
        """.trimIndent()

        val out = linkedMapOf<Long, StockLevel>()
        helper.readableDatabase.rawQuery(sql, arrayOf(storeId.toString())).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = StockLevel(c.getDouble(1), alertQty)
            }
        }
        return out
    }

    /** Quantity on hand for one product, across every batch. */
    fun stockOf(productId: Int): Double =
        helper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(current_quantity),0) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} " +
                "WHERE product_id = ?",
            arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

    /** The product's name, for naming it in a message about its stock. */
    private fun nameOf(productId: Int): String =
        helper.readableDatabase.rawQuery(
            "SELECT product_name FROM ${DatabaseHelper.Tables.MD_PRODUCTS} WHERE id = ? LIMIT 1",
            arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "Item" }

    // ---- Writes --------------------------------------------------------------

    /** One row of a stock entry: an item and how much of it. */
    data class Movement(val productId: Int, val quantity: Double)

    /**
     * Receives every row of a Stock In entry, in one transaction.
     *
     * Written together because they were entered together: a delivery half booked in
     * is worse than one not booked at all, since nothing on screen would say which
     * half. Each row updates md_batch_stock and adds its own td_stock_transactions
     * row, so the history has a line per item received rather than one lump.
     *
     * The entry carries no batch number, so the stock joins the batch the product
     * last moved in rather than starting a fresh row beside it - stock of one item
     * stays in one place instead of fragmenting a little further with every
     * delivery. A product with no batch at all gets an unnumbered one.
     */
    fun receive(rows: List<Movement>, note: String? = null) {
        val kept = rows.filter { it.quantity > 0.0 }
        if (kept.isEmpty()) return

        val db = helper.writableDatabase
        val now = now()
        val user = currentUser()

        db.beginTransaction()
        try {
            kept.forEach { row ->
                val batchId = latestBatch(row.productId)
                    ?: ensureUnnumberedBatch(db, row.productId, now, user)
                    ?: return@forEach
                addToBatch(db, batchId, row.quantity, now, user)
                recordMovement(
                    db, row.productId, batchId, "PURCHASE", Flow.IN, row.quantity, "", note, now, user
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Takes every row of a Write Off entry off the shelf and records why.
     *
     * Each row is drawn earliest-expiry-first across that product's batches - the
     * order stock actually leaves a shelf - and every batch drawn from gets its own
     * movement row, so the history says which batch the stock left.
     *
     * Checked before anything is written, and checked on the *combined* quantity per
     * product: two rows of six against a shelf of ten is still asking for twelve.
     * Returns the first problem found and writes nothing in that case.
     */
    fun issue(rows: List<Movement>, reason: OutReason, note: String): String? {
        val kept = rows.filter { it.quantity > 0.0 }
        if (kept.isEmpty()) return null

        kept.groupBy { it.productId }.forEach { (productId, forProduct) ->
            val wanted = forProduct.sumOf { it.quantity }
            val available = stockOf(productId)
            if (wanted > available) {
                return "${nameOf(productId)}: only ${trim(available)} in stock, " +
                    "cannot take out ${trim(wanted)}"
            }
        }

        val db = helper.writableDatabase
        val now = now()
        val user = currentUser()

        db.beginTransaction()
        try {
            kept.forEach { drain(db, it.productId, it.quantity, reason.transactionType, "", note, now, user) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return null
    }

    /**
     * Takes the quantity a bill sold off the shelf and files it as a SALE.
     *
     * Takes the caller's [db] and writes no transaction of its own: a sale's stock
     * has to land or not land with the bill that sold it, so it runs inside the
     * transaction [BillDao] already has open around the bill.
     *
     * [reference] is the bill number, so a movement can be traced back to the sale.
     */
    fun deductForSale(
        db: android.database.sqlite.SQLiteDatabase,
        productId: Int,
        quantity: Double,
        reference: String
    ) {
        if (quantity <= 0.0) return
        drain(db, productId, quantity, "SALE", reference, null, now(), currentUser())
    }

    /**
     * Puts a quantity a customer brought back onto the shelf, and files it as a
     * RETURN coming [Flow.IN].
     *
     * Note the pairing: a supplier return is also typed RETURN but flows OUT. The
     * type says what kind of movement it was, `stock_flow` says which way it went,
     * and only the two together tell the goods leaving from the goods arriving.
     *
     * Where the stock goes back to depends on what is known about how it left. On a
     * bill-wise return [soldUnderReference] is the original bill number, and the
     * sale's own movements say which batches that bill drew from - so the goods go
     * back into those, most recent first, which matters when the batches expire on
     * different dates. An item-wise return has no bill behind it and so no such
     * trail; that stock, and any remainder, goes into the batch the product last
     * moved in. A product with no batch at all gets an unnumbered one, so a return
     * always has somewhere to land.
     *
     * Takes the caller's [db] and opens no transaction: a return's stock has to land
     * with the return that caused it, inside [ReturnDao]'s own transaction.
     */
    fun restoreForReturn(
        db: android.database.sqlite.SQLiteDatabase,
        productId: Int,
        quantity: Double,
        reference: String,
        soldUnderReference: String? = null
    ) {
        if (quantity <= 0.0) return
        val now = now()
        val user = currentUser()
        var left = quantity

        if (!soldUnderReference.isNullOrBlank()) {
            // Newest movement first: the last batch to go out is the first to come back.
            db.rawQuery(
                "SELECT batch_id, SUM(quantity) FROM ${DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS} " +
                    "WHERE product_id = ? AND transaction_type = 'SALE' AND stock_flow = 'OUT' " +
                    "AND reference_number = ? AND batch_id IS NOT NULL " +
                    "GROUP BY batch_id ORDER BY MAX(id) DESC",
                arrayOf(productId.toString(), soldUnderReference)
            ).use { c ->
                while (c.moveToNext() && left > 0) {
                    // Never put more back into a batch than that bill took out of it.
                    val putBack = minOf(left, c.getDouble(1))
                    if (putBack <= 0.0) continue
                    addToBatch(db, c.getLong(0), putBack, now, user)
                    recordMovement(
                        db, productId, c.getLong(0), "RETURN", Flow.IN, putBack,
                        reference, null, now, user
                    )
                    left -= putBack
                }
            }
        }

        if (left <= 0) return
        val batchId = latestBatch(productId) ?: ensureUnnumberedBatch(db, productId, now, user) ?: return
        addToBatch(db, batchId, left, now, user)
        recordMovement(db, productId, batchId, "RETURN", Flow.IN, left, reference, null, now, user)
    }

    /**
     * Opens a newly created product's count at [quantity]: the batch it starts with
     * in md_batch_stock, and the movement that declares it in td_stock_transactions,
     * written together as everywhere else here.
     *
     * Runs on the caller's [db] and opens no transaction of its own - an opening
     * stock belongs to the product being created and has to land, or fail to land,
     * with it. Both callers create products: the Add Product form one at a time, and
     * the bulk upload a sheet at a time through its `stock` column.
     *
     * Typed ADJUSTMENT rather than PURCHASE because that is what an opening count is:
     * stock declared, not stock bought. The movement is only written for a real
     * quantity - an opening of zero has no movement to record - while the batch is
     * written either way, so a product declared empty still has somewhere for its
     * first delivery to land.
     *
     * The batch carries no number and no dates, since neither caller asks for them:
     * it is the product's unnumbered batch, the one later Stock In entries add to.
     */
    fun recordOpening(
        db: android.database.sqlite.SQLiteDatabase,
        productId: Long,
        quantity: Double,
        storeId: Int?,
        outletId: Int?
    ) {
        val now = now()
        val user = currentUser()

        val batch = ContentValues().apply {
            if (storeId != null) put("store_id", storeId) else putNull("store_id")
            if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
            put("product_id", productId)
            put("current_quantity", quantity)
            put("last_stock_update", now)
            put("created_by", user)
        }
        val batchId = db.insert(DatabaseHelper.Tables.MD_BATCH_STOCK, null, batch)
        if (batchId == -1L || quantity <= 0.0) return

        recordMovement(
            db, productId.toInt(), batchId, "ADJUSTMENT", Flow.IN, quantity,
            "", "Opening stock", now, user
        )
    }

    /** One line of a completed sale, for [recordSale]. */
    data class SaleLine(val productId: Int, val quantity: Double)

    /**
     * Records a whole sale's worth of lines in one transaction, for a caller that
     * has no transaction of its own to join - Restaurant mode, where an order is
     * settled on the Orders screen rather than through [BillDao].
     *
     * [reference] identifies the sale on each movement: a bill number where there is
     * one, and the table the order was served at where there is not.
     */
    fun recordSale(reference: String, lines: List<SaleLine>) {
        if (lines.isEmpty()) return
        val db = helper.writableDatabase
        val now = now()
        val user = currentUser()
        db.beginTransaction()
        try {
            lines.filter { it.quantity > 0.0 }.forEach {
                drain(db, it.productId, it.quantity, "SALE", reference, null, now, user)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- Internals -----------------------------------------------------------

    /**
     * Takes [quantity] off the product's batches, earliest expiry first, writing one
     * movement per batch it draws from.
     *
     * Earliest-expiry-first is the order stock actually leaves a shelf - the batch
     * closest to expiring is the one going.
     *
     * No batch is ever taken below zero. A count cannot go negative, so a quantity
     * larger than the shelf holds stops at what is there: every caller settles that
     * question before it gets here - the cart refuses to hold more than is in stock,
     * and a write-off refuses the quantity outright - and this is the floor under both.
     */
    private fun drain(
        db: android.database.sqlite.SQLiteDatabase,
        productId: Int,
        quantity: Double,
        transactionType: String,
        reference: String,
        note: String?,
        now: String,
        user: String?
    ) {
        var left = quantity
        // NULLs last: a batch with no expiry has no claim to going first.
        db.rawQuery(
            "SELECT id, batch_no, COALESCE(current_quantity,0) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} " +
                "WHERE product_id = ? AND COALESCE(current_quantity,0) > 0 " +
                "ORDER BY (expiry_date IS NULL), expiry_date ASC, id ASC",
            arrayOf(productId.toString())
        ).use { c ->
            while (c.moveToNext() && left > 0) {
                val batchId = c.getLong(0)
                val batchNo = c.getString(1).orEmpty()
                val taken = minOf(left, c.getDouble(2))
                db.execSQL(
                    "UPDATE ${DatabaseHelper.Tables.MD_BATCH_STOCK} " +
                        "SET current_quantity = COALESCE(current_quantity,0) - ?, " +
                        "last_stock_update = ?, modified_at = ?, modified_by = ? WHERE id = ?",
                    arrayOf<Any?>(taken, now, now, user, batchId)
                )
                recordMovement(
                    db, productId, batchId, transactionType, Flow.OUT, taken,
                    reference.ifBlank { batchNo }, note, now, user
                )
                left -= taken
            }
        }
    }

    /** The product's batch carrying [batchNo] (or its unnumbered batch), if any. */
    private fun batchFor(productId: Int, batchNo: String): Long? {
        val trimmed = batchNo.trim()
        val where = if (trimmed.isEmpty()) {
            "product_id = ? AND (batch_no IS NULL OR batch_no = '')" to arrayOf(productId.toString())
        } else {
            "product_id = ? AND batch_no = ?" to arrayOf(productId.toString(), trimmed)
        }
        return helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_BATCH_STOCK, arrayOf("id"),
            where.first, where.second, null, null, "id ASC", "1"
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun addToBatch(
        db: android.database.sqlite.SQLiteDatabase,
        batchId: Long,
        quantity: Double,
        now: String,
        user: String?
    ) {
        db.execSQL(
            "UPDATE ${DatabaseHelper.Tables.MD_BATCH_STOCK} " +
                "SET current_quantity = COALESCE(current_quantity,0) + ?, " +
                "last_stock_update = ?, modified_at = ?, modified_by = ? WHERE id = ?",
            arrayOf<Any?>(quantity, now, now, user, batchId)
        )
    }

    /** The product's most recently moved batch - where stock goes when nothing says otherwise. */
    private fun latestBatch(productId: Int): Long? =
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_BATCH_STOCK, arrayOf("id"),
            "product_id = ?", arrayOf(productId.toString()),
            null, null, "last_stock_update DESC, id DESC", "1"
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** The product's unnumbered batch, created empty if it has none. */
    private fun ensureUnnumberedBatch(
        db: android.database.sqlite.SQLiteDatabase,
        productId: Int,
        now: String,
        user: String?
    ): Long? {
        batchFor(productId, "")?.let { return it }
        val (storeId, outletId) = storeAndOutlet()
        val values = ContentValues().apply {
            if (storeId != null) put("store_id", storeId) else putNull("store_id")
            if (outletId != null) put("outlet_id", outletId) else putNull("outlet_id")
            put("product_id", productId)
            put("current_quantity", 0.0)
            put("last_stock_update", now)
            put("created_by", user)
        }
        return db.insert(DatabaseHelper.Tables.MD_BATCH_STOCK, null, values).takeIf { it != -1L }
    }

    /** One row of stock history. Quantity is always positive; [flow] carries direction. */
    private fun recordMovement(
        db: android.database.sqlite.SQLiteDatabase,
        productId: Int,
        batchId: Long,
        transactionType: String,
        flow: Flow,
        quantity: Double,
        reference: String,
        note: String?,
        now: String,
        user: String?
    ) {
        val values = ContentValues().apply {
            put("product_id", productId)
            put("batch_id", batchId)
            put("transaction_type", transactionType)
            put("stock_flow", flow.code)
            put("quantity", quantity)
            put("reference_number", reference.ifBlank { null })
            put("transaction_date", now)
            put("notes", note?.ifBlank { null })
            put("created_by", user)
        }
        db.insert(DatabaseHelper.Tables.TD_STOCK_TRANSACTIONS, null, values)
    }

    /** store_id / outlet_id for a new batch, taken from the registration row. */
    private fun storeAndOutlet(): Pair<Int?, Int?> {
        val sessionStore = SessionManager.currentUser?.storeId?.takeIf { it != 0 }
        helper.readableDatabase.rawQuery(
            "SELECT store_id, outlet_id FROM ${DatabaseHelper.Tables.MD_REGISTRATION} " +
                "ORDER BY verify_flag DESC, store_id ASC LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst()) {
                val s = sessionStore ?: if (c.isNull(0)) null else c.getInt(0)
                val o = if (c.isNull(1)) null else c.getInt(1)
                return s to o
            }
        }
        return sessionStore to null
    }

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Reads a numeric column as a display string ("" when null, no trailing ".0"). */
    private fun num(cursor: android.database.Cursor, index: Int): String {
        if (cursor.isNull(index)) return ""
        return trim(cursor.getDouble(index))
    }

    companion object {
        /** A quantity as text, without the ".0" on a whole number. */
        fun trim(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString()
            else String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    }
}
