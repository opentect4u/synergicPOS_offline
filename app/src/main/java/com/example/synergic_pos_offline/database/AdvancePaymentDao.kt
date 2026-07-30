package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collections taken against a credit customer's outstanding balance.
 *
 * [DatabaseHelper.Tables.MD_CUSTOMERS] is the ledger of record: `balance_amount` is
 * what the customer still owes and `credit_limit` is the credit they have left -
 * [BillDao.recordBalanceDue] moves both when a sale goes on account, and a
 * collection here moves them back. Everything this screen shows is derived from
 * those two figures and the transaction history behind them, so the master and the
 * screen cannot disagree.
 */
class AdvancePaymentDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val customerDao = CustomerDao(context)

    /** A customer's credit position, as the screen reports it. */
    data class Account(
        val customer: CustomerDao.Customer,
        /** Everything ever billed to them, cancelled bills excluded. */
        val totalBilled: Double,
        /** Everything ever collected from them - at the till and against their dues. */
        val totalPaid: Double,
        /** Still owed: `md_customers.balance_amount`. */
        val totalDue: Double,
        /** Credit still available: `md_customers.credit_limit`. */
        val creditLimit: Double
    )

    /** What a completed collection produced, and what its receipt prints. */
    data class Collection(
        val id: Long,
        val receiptNumber: String,
        val dateTime: String,
        /** What was actually taken, which is never more than was owed. */
        val amountPaid: Double,
        val previousDue: Double,
        val totalDue: Double,
        val totalPaid: Double,
        val creditLimit: Double,
        val mode: String
    )

    /** Type-ahead lookup by name or phone - see [CustomerDao.search]. */
    fun search(term: String, limit: Int = 25): List<CustomerDao.Customer> =
        customerDao.search(term, limit)

    /**
     * The customer's position right now. Null when the customer has been deleted
     * since the dropdown listed them.
     */
    fun account(customerId: Long): Account? {
        val customer = customerDao.findById(customerId) ?: return null
        return Account(
            customer = customer,
            totalBilled = totalBilled(customerId),
            totalPaid = totalPaid(customerId),
            totalDue = BillRounding.toPaise(customer.balance),
            creditLimit = BillRounding.toPaise(customer.creditLimit)
        )
    }

    /**
     * Takes [amount] off what [customerId] owes, in one transaction:
     *
     *  - `balance_amount` comes down by what was collected, and `credit_limit` goes
     *    back up by the same, releasing the credit that debt was holding;
     *  - a CREDIT line lands on their ledger carrying the running balance, which is
     *    also what makes the collection count towards [Account.totalPaid];
     *  - the collection itself is filed in `td_advance_payments` and given a receipt
     *    number, so the slip that prints can be traced back to a row.
     *
     * A payment larger than the outstanding balance is taken as it stands: the
     * balance simply carries on down through zero and the customer ends up in
     * credit, with the same arithmetic throughout. Nothing is capped or refused.
     *
     * @return null when the customer is gone or the write failed.
     */
    fun collect(customerId: Long, amount: Double, mode: String, notes: String? = null): Collection? {
        if (amount <= 0.0) return null
        val db = helper.writableDatabase
        val user = SessionManager.currentUser?.userId
        val nowDateTime = now()

        db.beginTransaction()
        try {
            var balance = 0.0
            var limit = 0.0
            var found = false
            db.rawQuery(
                "SELECT balance_amount, credit_limit FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} WHERE id = ?",
                arrayOf(customerId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    found = true
                    balance = if (c.isNull(0)) 0.0 else c.getDouble(0)
                    limit = if (c.isNull(1)) 0.0 else c.getDouble(1)
                }
            }
            if (!found) return null

            val previousDue = BillRounding.toPaise(balance)
            val applied = BillRounding.toPaise(amount)
            // Goes negative when more was handed over than was owed - that is the
            // customer sitting in credit, and it is carried as such.
            val newBalance = BillRounding.toPaise(previousDue - applied)
            val newLimit = BillRounding.toPaise(limit + applied)

            db.update(
                DatabaseHelper.Tables.MD_CUSTOMERS,
                ContentValues().apply {
                    put("balance_amount", newBalance)
                    put("credit_limit", newLimit)
                    put("modified_at", nowDateTime)
                    put("modified_by", user)
                },
                "id = ?", arrayOf(customerId.toString())
            )

            db.insert(
                DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, null,
                ContentValues().apply {
                    put("customer_id", customerId)
                    put("transaction_type", "CREDIT")
                    put("amount", applied)
                    put("balance", newBalance)
                    put("transaction_date", nowDateTime)
                    put("created_by", user)
                }
            )

            val id = db.insert(
                DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS, null,
                ContentValues().apply {
                    put("customer_id", customerId)
                    put("advance_amount", applied)
                    put("remaining_balance", newBalance)
                    put("payment_date", nowDateTime)
                    put("payment_mode", mode)
                    notes?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
                    put("created_by", user)
                }
            )
            if (id == -1L) return null

            // Numbered off its own row id, so the number on the paper is the row.
            val receiptNumber = RECEIPT_PREFIX + String.format(Locale.US, "%06d", id)
            db.update(
                DatabaseHelper.Tables.TD_ADVANCE_PAYMENTS,
                ContentValues().apply { put("receipt_number", receiptNumber) },
                "id = ?", arrayOf(id.toString())
            )

            // Read back after the ledger line, so the receipt's "total paid"
            // already includes the collection it is the receipt for.
            val paid = totalPaid(customerId, db)

            db.setTransactionSuccessful()
            return Collection(
                id = id,
                receiptNumber = receiptNumber,
                dateTime = nowDateTime,
                amountPaid = applied,
                previousDue = previousDue,
                totalDue = newBalance,
                totalPaid = paid,
                creditLimit = newLimit,
                mode = mode
            )
        } finally {
            db.endTransaction()
        }
    }

    /** Net value of every bill raised against the customer, cancellations aside. */
    private fun totalBilled(customerId: Long): Double =
        helper.readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(net_amount), 0) FROM ${DatabaseHelper.Tables.TD_BILLS}
            WHERE customer_id = ? AND COALESCE(bill_status, '') <> 'CANCELLED'
            """.trimIndent(),
            arrayOf(customerId.toString())
        ).use { c -> BillRounding.toPaise(if (c.moveToFirst()) c.getDouble(0) else 0.0) }

    /**
     * Every rupee the customer has handed over: what was taken at the till on their
     * bills, plus every collection since made against their dues (the CREDIT lines
     * on their ledger). The two never overlap - a till payment is recorded on the
     * bill's payment row, a due collection only on the ledger.
     */
    private fun totalPaid(
        customerId: Long,
        db: android.database.sqlite.SQLiteDatabase = helper.readableDatabase
    ): Double {
        val id = customerId.toString()
        val atTill = db.rawQuery(
            """
            SELECT COALESCE(SUM(p.amount_paid), 0)
            FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
            LEFT JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
            WHERE (p.cust_id = ? OR b.customer_id = ?)
              AND COALESCE(b.bill_status, '') <> 'CANCELLED'
            """.trimIndent(),
            arrayOf(id, id)
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        val againstDues = db.rawQuery(
            """
            SELECT COALESCE(SUM(amount), 0) FROM ${DatabaseHelper.Tables.TD_CUSTOMER_LEDGER}
            WHERE customer_id = ? AND transaction_type = 'CREDIT'
            """.trimIndent(),
            arrayOf(id)
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        return BillRounding.toPaise(atTill + againstDues)
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val RECEIPT_PREFIX = "AP"
    }
}
