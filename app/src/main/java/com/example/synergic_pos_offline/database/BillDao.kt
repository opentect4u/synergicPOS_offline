package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.synergic_pos_offline.utils.AmountInWords
import com.example.synergic_pos_offline.utils.BillPricing
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer that generates a completed sale, writing atomically to
 * [DatabaseHelper.Tables.TD_BILLS], [DatabaseHelper.Tables.TD_BILL_ITEMS] and
 * [DatabaseHelper.Tables.TD_PAYMENTS].
 */

/**
 * Read access to the bill history stored in [DatabaseHelper.Tables.TD_BILLS],
 * joined with customers (name) and bill items (product names). Powers the
 * Bill History screen with real, persisted data.
 */
class BillDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val settingsDao = BillSettingsDao(context)
    private val taxSettingsDao = TaxSettingsDao(context)
    private val stockDao by lazy { StockDao(context) }

    /** A single line on the bill. */
    data class Item(
        val productId: Long?,
        val name: String,
        val quantity: Double,
        val rate: Double,
        val cgstRate: Double = 0.0,
        val sgstRate: Double = 0.0,
        /**
         * The product's VAT rate, off the master.
         *
         * Charged whenever the line carries one, whichever tax the till is set up
         * for - see [com.example.synergic_pos_offline.utils.BillPricing]. It used to
         * be read only under a VAT regime, which is how a VAT-rated product sold on
         * a GST till came to be charged no VAT at all.
         */
        val vatRate: Double = 0.0,
        /** This line's share of the bill discount; tax is charged on the remainder. */
        val discountAmount: Double = 0.0
    )

    /** Payment collected against the bill. */
    data class Payment(
        val mode: String,          // CASH / UPI / CARD / CHEQUE / ONLINE
        val amountPaid: Double,
        val changeAmount: Double = 0.0,
        val custName: String? = null,
        val custPhone: String? = null,
        val custGstin: String? = null,
        val custId: Long? = null
    )

    /** Everything needed to persist a completed sale. */
    data class NewBill(
        val billType: String,      // CASH / CREDIT / ONLINE / VOID
        val customerId: Long?,
        val items: List<Item>,
        val payment: Payment,
        val totalPrice: Double,
        val discountAmount: Double,
        val discountPercentage: Double,
        /** How the discount was entered - the raw amount is what math runs on either way. */
        val discountIsPercent: Boolean = true,
        val cgstAmount: Double,
        val sgstAmount: Double,
        val netAmount: Double,
        val igstAmount: Double = 0.0,
        val vatAmount: Double = 0.0,
        val otherChargesAmount: Double = 0.0,
        val roundOffAmount: Double = 0.0,
        val waiterId: Long? = null,
        val isMrpBilling: Boolean = false,
        val isReturnBill: Boolean = false,
        // Restaurant-mode fields (null/0 for grocery bills).
        val tableNumber: String? = null,
        /** The table's section. Null for grocery, and for take-away. */
        val tableSection: String? = null,
        val orderType: String? = null,
        val serviceChargeAmount: Double = 0.0,
        /**
         * The number this bill was already printed under, held on the running order
         * since Print Bill - see td_running_order.bill_seq_no.
         *
         * Null for a sale numbered at the moment it is saved, which is every grocery
         * bill: the till prints and books it in one act, so there is no gap for the
         * counter to move in. A restaurant bill has a gap of minutes, and this is what
         * carries the number across it.
         */
        val reservedBillSeq: Int? = null,
        /**
         * Set when the shelf has ALREADY been drawn down for these items, so this
         * bill must not draw it down again.
         *
         * The restaurant does this: its stock comes off at Print Bill, when the
         * kitchen has served the order and the table is locked, rather than at
         * payment - which can be minutes later, or never, and by then the food is
         * gone whatever the customer does. The bill is still written at payment, and
         * without this flag that write would take the same items off a second time.
         *
         * Grocery leaves it false: there is no moment between serving and paying for
         * a shelf sale, so the bill IS the deduction, inside the same transaction.
         */
        val stockAlreadyMoved: Boolean = false
    )

    /** Result of a successful generation. */
    data class Result(val receiptNo: Long, val billNumber: String)

    /**
     * Persists [bill] across the three transaction tables in one atomic
     * transaction. Returns the new receipt number and its formatted bill number,
     * or null if the write failed.
     */
    fun createBill(bill: NewBill): Result? {
        val db = helper.writableDatabase
        val storeId = currentStoreId()
        val outletId = currentOutletId()
        val operatorId = currentOperatorId()
        val user = currentUser()
        val nowDateTime = now()
        val nowDate = today()
        val settings = settingsDao.load()
        // The number this sale was PRINTED under, where it was printed before being
        // paid, so the books carry what the customer is holding. Only a fresh sale -
        // one whose slip has not been cut yet - takes the next number off the counter.
        val seq = bill.reservedBillSeq ?: nextBillSequence(db, settings, nowDate)
        val billNumber = formatBillNumber(seq, settings)
        val taxSettings = taxSettingsDao.load()
        val taxEnabled = taxSettings.taxEnabled
        val inclusive = taxSettings.taxMode == TaxSettingsDao.GstMode.INCLUSIVE
        val discountPreTax = taxSettings.discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX

        db.beginTransaction()
        try {
            // 1) Bill header. bill_seq_no is the raw counter Bill Settings' Start No. /
            // Reset Bill No. work off of; bill_number is just that, formatted with the
            // configured character prefix.
            val billValues = ContentValues().apply {
                put("store_id", storeId)
                put("outlet_id", outletId)
                put("bill_date", nowDate)
                put("bill_date_time", nowDateTime)
                put("bill_seq_no", seq)
                put("bill_number", billNumber)
                if (bill.customerId != null) put("customer_id", bill.customerId)
                if (operatorId != null) put("operator_id", operatorId)
                put("bill_type", bill.billType)
                // Frozen at creation time so a later reprint reads exactly as it did
                // on the day, even after Bill/Tax Settings have since changed.
                put("settings_snapshot", BillSettingsSnapshot.serialize(settings, taxEnabled, discountPreTax, inclusive))
                put("tot_price", bill.totalPrice)
                put("tot_discount_amount", bill.discountAmount)
                put("tot_discount_percentage", bill.discountPercentage)
                put("discount_flag", if (bill.discountAmount > 0) 1 else 0)
                put(
                    "discount_type",
                    if (bill.discountAmount <= 0.0) null else if (bill.discountIsPercent) "PERCENTAGE" else "FLAT"
                )
                put("tot_cgst_amount", bill.cgstAmount)
                put("tot_sgst_amount", bill.sgstAmount)
                put("tot_igst_amount", bill.igstAmount)
                put("tot_vat_amount", bill.vatAmount)
                put("tot_other_charges_amount", bill.otherChargesAmount)
                put("tot_round_off_amount", bill.roundOffAmount)
                put("net_amount", bill.netAmount)
                put("amount_in_words", AmountInWords.of(bill.netAmount))
                put("gst_flag", if (bill.cgstAmount + bill.sgstAmount + bill.igstAmount > 0) 1 else 0)
                put("vat_flag", if (bill.vatAmount > 0) 1 else 0)
                if (bill.waiterId != null) put("waiter_id", bill.waiterId)
                bill.tableNumber?.takeIf { it.isNotBlank() }?.let { put("table_number", it) }
                bill.tableSection?.takeIf { it.isNotBlank() }?.let { put("table_section", it) }
                bill.orderType?.takeIf { it.isNotBlank() }?.let { put("order_type", it) }
                put("service_charge_amount", bill.serviceChargeAmount)
                put("is_mrp_billing", if (bill.isMrpBilling) 1 else 0)
                put("is_return_bill", if (bill.isReturnBill) 1 else 0)
                put("is_duplicate", 0)
                put("is_voided", if (bill.billType == "VOID") 1 else 0)
                put("bill_status", if (bill.billType == "VOID") "CANCELLED" else "COMPLETED")
                put("created_by", user)
            }
            val receiptNo = db.insert(DatabaseHelper.Tables.TD_BILLS, null, billValues)
            if (receiptNo == -1L) return null

            // 2) Bill items.
            bill.items.forEach { item ->
                // Priced by the one routine that prices a line anywhere in the app, so
                // what is stored here is exactly what checkout previewed - see
                // [BillPricing.price].
                val priced = BillPricing.price(
                    rate = item.rate,
                    quantity = item.quantity,
                    cgstRate = item.cgstRate,
                    sgstRate = item.sgstRate,
                    vatRate = item.vatRate,
                    discountAmount = item.discountAmount,
                    taxEnabled = taxEnabled,
                    inclusive = inclusive,
                    discountPreTax = discountPreTax
                )
                val cgstAmt = priced.cgst
                val sgstAmt = priced.sgst
                val vatAmt = priced.vat
                val itemTotal = priced.itemTotal
                val itemValues = ContentValues().apply {
                    put("store_id", storeId)
                    put("receipt_no", receiptNo)
                    put("trans_dt", nowDateTime)
                    put("bill_id", receiptNo)
                    if (item.productId != null) put("product_id", item.productId)
                    unitIdForProduct(db, item.productId)?.let { put("unit_id", it) }
                    put("quantity", item.quantity)
                    put("rate", item.rate)
                    put("item_subtotal", priced.subtotal)
                    put("discount_amount", item.discountAmount)
                    put("cgst_rate", item.cgstRate)
                    put("sgst_rate", item.sgstRate)
                    put("vat_rate", item.vatRate)
                    put("cgst_amount", cgstAmt)
                    put("sgst_amount", sgstAmt)
                    put("vat_amount", vatAmt)
                    put("item_total", itemTotal)
                    put("created_by", user)
                }
                db.insert(DatabaseHelper.Tables.TD_BILL_ITEMS, null, itemValues)
            }

            // 3) Payment.
            val paymentValues = ContentValues().apply {
                put("store_id", storeId)
                put("receipt_no", receiptNo)
                put("bill_id", receiptNo)
                put("payment_mode", bill.payment.mode)
                put("amount_paid", bill.payment.amountPaid)
                put("change_amount", bill.payment.changeAmount)
                put("payment_status", paymentStatusFor(bill))
                put("balance_amount", balanceDueFor(bill))
                put("payment_date", nowDateTime)
                bill.payment.custName?.let { put("cust_name", it) }
                bill.payment.custGstin?.let { put("cust_gstin", it) }
                bill.payment.custPhone?.let { put("cust_phone", it) }
                bill.payment.custId?.let { put("cust_id", it) }
                put("created_by", user)
            }
            val paymentId = db.insert(DatabaseHelper.Tables.TD_PAYMENTS, null, paymentValues)

            // 4) Anything still owed goes on the customer's ledger for recovery.
            val balance = balanceDueFor(bill)
            val customerId = bill.customerId ?: bill.payment.custId
            if (balance > 0.001 && customerId != null && paymentId != -1L) {
                recordBalanceDue(db, customerId, receiptNo, paymentId, balance, nowDateTime, user)
            }

            // 5) Stock. Inside the bill's own transaction, so a sale and the stock it
            // moved land together or not at all. Only while stock tracking is on:
            // with the flag off the sale never touches the stock tables at all, which
            // is how the till behaved before they were being kept.
            //
            // A void sold nothing and a return is stock coming back, so neither is a
            // deduction - the return flow has its own accounting.
            // ...and not when the caller has already moved it - see
            // [NewBill.stockAlreadyMoved]. A restaurant takes its stock off at Print
            // Bill; deducting again here is how one sale came off the shelf twice.
            val movesStock = bill.billType != "VOID" && !bill.isReturnBill &&
                !bill.stockAlreadyMoved &&
                GeneralSettingsDao.isStockEnabled(appContext)
            if (movesStock) {
                bill.items.forEach { item ->
                    val productId = item.productId?.toInt() ?: return@forEach
                    stockDao.deductForSale(db, productId, item.quantity, billNumber)
                }
            }

            db.setTransactionSuccessful()
            return Result(receiptNo, billNumber)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * What is still owed on the bill. Change is not deducted - handing over 32.00
     * for a 31.50 bill settles it in full - and the comparison carries a small
     * tolerance because the total holds rounded paise.
     */
    private fun balanceDueFor(bill: NewBill): Double =
        (bill.netAmount - bill.payment.amountPaid).coerceAtLeast(0.0)

    /**
     * A payment is only COMPLETED once the bill is covered. A credit sale is billed
     * now and collected later, so it stays PENDING until something is taken, and
     * PARTIAL while it is short.
     */
    private fun paymentStatusFor(bill: NewBill): String = when {
        balanceDueFor(bill) <= 0.001 -> "COMPLETED"
        bill.payment.amountPaid > 0.001 -> "PARTIAL"
        else -> "PENDING"
    }

    /**
     * Books an outstanding bill against the customer so it can be recovered later:
     * a DEBIT line on their ledger carrying the running balance, and the same total
     * on their master record.
     *
     * The credit limit comes down by what was just put on the account, so it always
     * reads as the credit the customer has left rather than the figure they started
     * with. It floors at zero: a sale larger than the remaining limit exhausts it
     * rather than turning it negative, and the full debt is still carried by the
     * balance either way.
     *
     * Skipped when the sale has no customer - there would be nobody to chase, and
     * the balance is still recorded on the payment row either way.
     */
    private fun recordBalanceDue(
        db: SQLiteDatabase,
        customerId: Long,
        receiptNo: Long,
        paymentId: Long,
        balance: Double,
        nowDateTime: String,
        user: String?
    ) {
        var previous = 0.0
        var previousLimit = 0.0
        db.rawQuery(
            "SELECT balance_amount, credit_limit FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} WHERE id = ?",
            arrayOf(customerId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                previous = if (c.isNull(0)) 0.0 else c.getDouble(0)
                previousLimit = if (c.isNull(1)) 0.0 else c.getDouble(1)
            }
        }
        val running = BillRounding.toPaise(previous + balance)
        val remainingLimit = BillRounding.toPaise((previousLimit - balance).coerceAtLeast(0.0))

        db.insert(
            DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, null,
            ContentValues().apply {
                put("customer_id", customerId)
                put("bill_id", receiptNo)
                put("payment_id", paymentId)
                put("transaction_type", "DEBIT")
                put("amount", balance)
                put("balance", running)
                put("transaction_date", nowDateTime)
                put("created_by", user)
            }
        )
        db.update(
            DatabaseHelper.Tables.MD_CUSTOMERS,
            ContentValues().apply {
                put("balance_amount", running)
                put("credit_limit", remainingLimit)
            },
            "id = ?", arrayOf(customerId.toString())
        )
    }

    /** The bill number the next completed sale will be given, per Bill Settings. */
    fun nextBillNumber(): String = nextNumber().number

    /** A bill number and the counter behind it, taken together so they cannot disagree. */
    data class NextNumber(val seq: Int, val number: String)

    /**
     * The number the next bill will carry, with its raw counter.
     *
     * Handed out as a pair because a caller that PRINTS a number has to be able to
     * save that same one later - see [NewBill.reservedBillSeq]. Asking twice and
     * hoping the answer has not moved is what put one number on the slip and another
     * in the books.
     */
    fun nextNumber(): NextNumber {
        val settings = settingsDao.load()
        val seq = nextBillSequence(helper.readableDatabase, settings, today())
        return NextNumber(seq, formatBillNumber(seq, settings))
    }

    /**
     * The receipt number the next bill will take.
     *
     * Read from sqlite_sequence rather than MAX(receipt_no): td_bills is
     * AUTOINCREMENT, so deleting the last bill does not release its number and the
     * counter keeps going. MAX is still folded in as a guard for a table whose
     * sequence row is missing - a restored or hand-edited database - where
     * trusting the sequence alone would hand out a number already in use.
     */
    fun nextReceiptNo(): Long {
        helper.readableDatabase.rawQuery(
            """
            SELECT MAX(
                COALESCE((SELECT seq FROM sqlite_sequence WHERE name = ?), 0),
                COALESCE((SELECT MAX(receipt_no) FROM ${DatabaseHelper.Tables.TD_BILLS}), 0)
            ) + 1
            """.trimIndent(),
            arrayOf(DatabaseHelper.Tables.TD_BILLS)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return 1L
    }

    /** The most recent bill's number (e.g. "3"), or null if none exist yet. */
    fun lastBillNumber(): String? {
        helper.readableDatabase.rawQuery(
            """
            SELECT bill_number, receipt_no FROM ${DatabaseHelper.Tables.TD_BILLS}
            ORDER BY receipt_no DESC LIMIT 1
            """.trimIndent(),
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getLong(1).toString()
        }
    }

    /** The most recent bill's receipt number, or null if none exist yet. */
    fun lastReceiptNo(): Long? {
        helper.readableDatabase.rawQuery(
            "SELECT receipt_no FROM ${DatabaseHelper.Tables.TD_BILLS} ORDER BY receipt_no DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** The unit_id for a product, taken from its default rate (else its first rate). */
    private fun unitIdForProduct(db: SQLiteDatabase, productId: Long?): Long? {
        if (productId == null) return null
        db.rawQuery(
            "SELECT unit_id FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} " +
                "WHERE product_id = ? AND unit_id IS NOT NULL ORDER BY \"default\" DESC, id ASC LIMIT 1",
            arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    /** Looks up a customer's id by phone number, or null if not found. */
    fun findCustomerIdByPhone(phone: String?): Long? {
        if (phone.isNullOrBlank()) return null
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_CUSTOMERS, arrayOf("id"),
            "phone_number=?", arrayOf(phone), null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /**
     * The counter the next bill in the current reset period should carry.
     *
     * Scoped to today/this month/this year when Bill Settings' Reset Bill No. calls
     * for it, so bill_seq_no - not the ever-climbing receipt_no - is what actually
     * resets. Continuing on from the highest one already used in that scope, so a
     * bill deleted mid-sequence does not hand its number to a later sale. Only an
     * empty scope falls back to a starting point: the configured Start No. when
     * numbering continues indefinitely, or 1 when a new period is starting fresh.
     */
    private fun nextBillSequence(db: SQLiteDatabase, settings: BillSettingsDao.BillSettings, nowDate: String): Int {
        // The sequence is shared across sales and sale returns, so each document's number
        // continues on from the last of either. Each table dates its rows on its own
        // column, but both carry the same bill_seq_no counter. Advance-payment collections
        // are NOT part of this: they run on their own keyname sequence (see AdvancePaymentDao).
        val maxSeq = listOfNotNull(
            maxSeqIn(db, DatabaseHelper.Tables.TD_BILLS, "bill_date", settings.resetMode, nowDate),
            maxSeqIn(db, DatabaseHelper.Tables.TD_SALE_RETURNS, "return_date", settings.resetMode, nowDate),
            // Numbers already PRINTED on a bill that has not been settled yet. They
            // are spoken for: the slip is on a table with that number on it, so the
            // next table has to be given a different one. Not scoped to the reset
            // period - an unsettled order that has crossed midnight still holds its
            // number, and handing it out again would put one number on two bills,
            // which is worse than the gap that skipping it leaves.
            maxReservedSeq(db)
        ).maxOrNull()
        return when {
            maxSeq != null -> maxSeq + 1
            settings.resetMode == BillSettingsDao.ResetMode.CONTINUE -> settings.startBillNo + 1
            else -> 1
        }
    }

    /** Highest bill number reserved by an order whose bill is printed but not settled. */
    private fun maxReservedSeq(db: SQLiteDatabase): Int? = runCatching {
        db.rawQuery(
            "SELECT MAX(bill_seq_no) FROM ${DatabaseHelper.Tables.TD_RUNNING_ORDER}", null
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
    }.getOrNull()

    /** Highest bill_seq_no in [table] within the reset period (dated on [dateCol]). */
    private fun maxSeqIn(
        db: SQLiteDatabase, table: String, dateCol: String,
        resetMode: BillSettingsDao.ResetMode, nowDate: String
    ): Int? {
        val periodWhere = when (resetMode) {
            BillSettingsDao.ResetMode.DAILY -> "$dateCol = ?" to nowDate
            BillSettingsDao.ResetMode.MONTHLY -> "substr($dateCol, 1, 7) = ?" to nowDate.take(7)
            BillSettingsDao.ResetMode.YEARLY -> "substr($dateCol, 1, 4) = ?" to nowDate.take(4)
            BillSettingsDao.ResetMode.CONTINUE -> null
        }
        val sql = "SELECT MAX(bill_seq_no) FROM $table" + (periodWhere?.let { " WHERE ${it.first}" } ?: "")
        val args = periodWhere?.let { arrayOf(it.second) }
        return runCatching {
            db.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
        }.getOrNull()
    }

    /** A shared bill number for a sale return / credit recovery, continuous with sales. */
    data class SharedNumber(val seq: Int, val number: String)

    /**
     * The next continuous bill number to stamp on a sale return or credit recovery,
     * drawn from the SAME sequence as normal sales (and advancing it). Store [seq] as
     * that document's bill_seq_no so the next sale continues past it.
     */
    fun nextSharedBillNumber(): SharedNumber {
        val settings = settingsDao.load()
        val seq = nextBillSequence(helper.readableDatabase, settings, today())
        return SharedNumber(seq, formatBillNumber(seq, settings))
    }

    private fun formatBillNumber(seq: Int, settings: BillSettingsDao.BillSettings): String {
        val prefix = if (settings.billNoCharEnabled) settings.billNoCharPrefix else ""
        return "$prefix$seq"
    }

    private fun currentStoreId(): Long? {
        // The signed-in user's store is the current store; registration is a fallback.
        SessionManager.currentUser?.storeId?.takeIf { it != 0 }?.let { return it.toLong() }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentOutletId(): Long? {
        // Prefer the outlet registered for the current store; else the first row.
        currentStoreId()?.let { store ->
            helper.readableDatabase.rawQuery(
                "SELECT outlet_id FROM ${DatabaseHelper.Tables.MD_REGISTRATION} WHERE store_id = ? LIMIT 1",
                arrayOf(store.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        }
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("outlet_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** Resolves the logged-in user's md_users.id from their user_id. */
    private fun currentOperatorId(): Long? {
        val userId = SessionManager.currentUser?.userId ?: return null
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_USERS, arrayOf("id"),
            "user_id=?", arrayOf(userId), null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    /** One bill summary row, shaped for the Bill History list. */
    data class Bill(
        val receiptNo: Long,
        val billNo: String,
        val name: String,
        val date: String,       // dd-MM-yyyy
        val time: String,       // HH:mm
        val total: String,      // formatted net amount, e.g. "1,285.75"
        val items: List<String>,
        val cancelled: Boolean,
        /**
         * Whether any of this bill has come back on a sale return.
         *
         * Derived from the returns table rather than stored on the bill: a return is
         * its own document with its own number, and rewriting the sale's status when
         * one is taken would change what every report reading `bill_status` counts.
         * True for a partial return too - some of the goods are back, so the sale is
         * no longer the plain sale it was, which is what Bill History needs to show.
         */
        val returned: Boolean = false,
        /**
         * Whether this bill has been deleted - moved out of td_bills and into the
         * archive, so it counts towards no report.
         *
         * History still lists it, filed with the cancelled ones: a deleted bill is
         * exactly the thing somebody comes looking for, and a till that simply lost it
         * would look like a till that had lost a sale.
         */
        val deleted: Boolean = false
    ) {
        /** Numeric total, tolerant of thousands separators. */
        val amount: Double get() = total.replace(",", "").toDoubleOrNull() ?: 0.0
    }

    /** All bills, newest first, each with its customer name and item names. */
    fun getAll(): List<Bill> {
        val itemsByBill = loadItemsByBill()
        val list = mutableListOf<Bill>()

        // Store-scoped: only the current store's bills (all date filters run on top of this).
        val store = currentStoreId()
        val storeClause = if (store != null) "WHERE b.store_id = ?" else ""
        // Bound twice - the store clause appears in both halves of the union below.
        val args = store?.let { arrayOf(it.toString(), it.toString()) }
        // Live bills and deleted ones in one list. Deleted bills are gone from every
        // report - that is what deleting them did - but History is where somebody
        // goes to find one, so it reads both tables and marks which is which.
        val sql = """
            SELECT b.receipt_no, b.bill_number, b.bill_date, b.bill_date_time,
                   b.net_amount, b.bill_status, c.customer_name,
                   EXISTS(SELECT 1 FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS} r
                          WHERE r.original_bill_id = b.receipt_no), 0
            FROM td_bills b
            LEFT JOIN md_customers c ON c.id = b.customer_id
            $storeClause
            UNION ALL
            SELECT b.receipt_no, b.bill_number, b.bill_date, b.bill_date_time,
                   b.net_amount, b.bill_status, c.customer_name,
                   EXISTS(SELECT 1 FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS} r
                          WHERE r.original_bill_id = b.receipt_no), 1
            FROM ${DatabaseHelper.Tables.TD_BILLS_DELETE} b
            LEFT JOIN md_customers c ON c.id = b.customer_id
            $storeClause
            ORDER BY 1 DESC
        """.trimIndent()

        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val receiptNo = c.getLong(0)
                val billNumber = c.getString(1)?.takeIf { it.isNotBlank() } ?: receiptNo.toString()
                val rawDate = c.getString(2)
                val rawDateTime = c.getString(3)
                val net = c.getDouble(4)
                val status = c.getString(5).orEmpty()
                val customer = c.getString(6)?.takeIf { it.isNotBlank() } ?: "Walk-in"

                list.add(
                    Bill(
                        receiptNo = receiptNo,
                        billNo = billNumber,
                        name = customer,
                        date = formatDate(rawDateTime, rawDate),
                        time = formatTime(rawDateTime),
                        total = String.format(Locale.US, "%,.2f", net),
                        items = itemsByBill[receiptNo].orEmpty(),
                        // A deleted bill files with the cancelled ones, which is where
                        // History looks for anything that is no longer a plain sale.
                        cancelled = status.equals("CANCELLED", ignoreCase = true) || c.getInt(8) == 1,
                        returned = c.getInt(7) == 1,
                        deleted = c.getInt(8) == 1
                    )
                )
            }
        }
        return list
    }

    /** Distinct product names across all bills (for the item filter suggestions). */
    fun allItems(): List<String> =
        loadItemsByBill().values.flatten().distinct().sorted()

    /** Maps each bill's receipt_no to the list of its product names (current store only). */
    private fun loadItemsByBill(): Map<Long, MutableList<String>> {
        val map = hashMapOf<Long, MutableList<String>>()
        val store = currentStoreId()
        // Restrict to the current store's bill lines (scoped directly by store_id).
        val storeClause = if (store != null) "WHERE bi.store_id = ?" else ""
        // Bound twice - once per half of the union.
        val args = store?.let { arrayOf(it.toString(), it.toString()) }
        val sql = """
            SELECT bi.bill_id, p.product_name
            FROM td_bill_items bi
            LEFT JOIN md_products p ON p.id = bi.product_id
            $storeClause
            UNION ALL
            SELECT bi.bill_id, p.product_name
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS_DELETE} bi
            LEFT JOIN md_products p ON p.id = bi.product_id
            $storeClause
        """.trimIndent()

        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                if (c.isNull(0)) continue
                val billId = c.getLong(0)
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                map.getOrPut(billId) { mutableListOf() }.add(name)
            }
        }
        return map
    }

    private val dbDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dbDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outDate = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val outTime = SimpleDateFormat("HH:mm", Locale.US)

    private fun formatDate(dateTime: String?, date: String?): String {
        parse(dbDateTime, dateTime)?.let { return outDate.format(it) }
        parse(dbDate, date)?.let { return outDate.format(it) }
        return date.orEmpty()
    }

    private fun formatTime(dateTime: String?): String =
        parse(dbDateTime, dateTime)?.let { outTime.format(it) } ?: ""

    private fun parse(fmt: SimpleDateFormat, text: String?) =
        try { if (text.isNullOrBlank()) null else fmt.parse(text) } catch (_: Exception) { null }

    companion object {

        /**
         * SQL predicate for a bill that still counts as a sale: not voided, and with
         * nothing returned against it.
         *
         * Kept here as one string because every figure the till reports has to agree
         * on what a countable bill is. There are two dozen reports still to be built,
         * and each one writing its own version of this is how a Day-Wise total ends up
         * disagreeing with a Bill-Wise one over the same day.
         *
         * [alias] is the table alias the query gave `td_bills`, or null when its
         * columns are unqualified.
         *
         * Note this drops a partly-returned bill entirely rather than netting the
         * returned amount off it - see the returns table if a report needs the
         * finer-grained figure.
         */
        fun countableBillClause(alias: String? = null): String {
            val prefix = alias?.let { "$it." } ?: ""
            return "${prefix}is_voided = 0 AND NOT EXISTS(" +
                "SELECT 1 FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS} r " +
                "WHERE r.original_bill_id = ${prefix}receipt_no)"
        }
    }
}
