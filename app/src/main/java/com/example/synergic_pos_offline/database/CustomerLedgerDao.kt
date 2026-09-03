package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * The running account between the business and one customer.
 *
 * Every movement of that account is already recorded in
 * [DatabaseHelper.Tables.TD_CUSTOMER_LEDGER]: a DEBIT when a sale goes on credit
 * ([BillDao.recordBalanceDue]), a CREDIT when a due is collected
 * ([AdvancePaymentDao.collect]). This reads them back for a date range and states
 * the account the way a ledger states one - what was owed at the start, what moved
 * either way, and what is owed at the end.
 *
 * Settled sales are deliberately absent: a cash sale is paid at the counter and
 * never touches the account, so listing it would put a line on the ledger that the
 * balance column could not account for.
 */
class CustomerLedgerDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val customerDao = CustomerDao(context)

    /**
     * One movement on the account.
     *
     * [out] is value that left the business - goods supplied on credit, which the
     * customer now owes for. [in_] is money that came back in against those dues.
     * Exactly one of the two is non-zero on any line.
     */
    data class Entry(
        val date: String,
        val particulars: String,
        val reference: String,
        val `in`: Double,
        val out: Double,
        /** The account balance immediately after this line. */
        val balance: Double
    )

    /** A line as read off the ledger, before the balance column is walked onto it. */
    private data class Row(
        val date: String,
        val particulars: String,
        val reference: String,
        val moneyIn: Double,
        val moneyOut: Double
    )

    /** A customer's account over a date range, with the four figures that frame it. */
    data class Ledger(
        val customer: CustomerDao.Customer,
        val fromDate: String,
        val toDate: String,
        /** Owed at the start of [fromDate], before any line below. */
        val opening: Double,
        val entries: List<Entry>,
        /** Total collected in the range. */
        val totalIn: Double,
        /** Total put on account in the range. */
        val totalOut: Double,
        /** Owed at the end of [toDate]: [opening] + [totalOut] - [totalIn]. */
        val closing: Double
    )

    /**
     * The account for the customer on [phone], covering [fromDate]..[toDate]
     * inclusive (both "yyyy-MM-dd").
     *
     * The closing figure is anchored to `md_customers.balance_amount` - the master
     * is what the rest of the app bills and collects against - by winding back the
     * movements recorded after [toDate]. Opening is then derived from closing and
     * the range's own movements, so the four figures always reconcile even for a
     * customer whose starting balance was typed in on the Customers screen rather
     * than built up from transactions.
     *
     * @return null when no customer is registered against [phone].
     */
    fun forPhone(phone: String, fromDate: String, toDate: String): Ledger? =
        customerDao.findByPhone(phone)?.let { forCustomer(it, fromDate, toDate) }

    /**
     * The same statement for a customer already in hand - what the Customers master
     * has when the ledger is opened from a row, and the only way to reach the
     * account of a customer with no phone number on file.
     */
    fun forCustomer(customerId: Long, fromDate: String, toDate: String): Ledger? =
        customerDao.findById(customerId)?.let { forCustomer(it, fromDate, toDate) }

    private fun forCustomer(
        customer: CustomerDao.Customer,
        fromDate: String,
        toDate: String
    ): Ledger {
        val db = helper.readableDatabase
        val id = customer.id.toString()

        // Movements after the range: what has to be undone from today's balance to
        // land on the balance as it stood at the close of [toDate].
        var afterDebit = 0.0
        var afterCredit = 0.0
        db.rawQuery(
            """
            SELECT COALESCE(SUM(CASE WHEN transaction_type = 'DEBIT'  THEN amount ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN transaction_type = 'CREDIT' THEN amount ELSE 0 END), 0)
            FROM ${DatabaseHelper.Tables.TD_CUSTOMER_LEDGER}
            WHERE customer_id = ? AND date(transaction_date) > date(?)
            """.trimIndent(),
            arrayOf(id, toDate)
        ).use { c ->
            if (c.moveToFirst()) {
                afterDebit = c.getDouble(0)
                afterCredit = c.getDouble(1)
            }
        }
        val closing = BillRounding.toPaise(customer.balance - afterDebit + afterCredit)

        // The lines themselves. A bill number reads better than a receipt row id, so
        // the sale is joined in where the movement came from one.
        val rows = mutableListOf<Row>()
        db.rawQuery(
            """
            SELECT l.transaction_date, l.transaction_type, l.amount, b.bill_number, l.bill_id, l.payment_id
            FROM ${DatabaseHelper.Tables.TD_CUSTOMER_LEDGER} l
            LEFT JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = l.bill_id
            WHERE l.customer_id = ?
              AND date(l.transaction_date) BETWEEN date(?) AND date(?)
            ORDER BY date(l.transaction_date) ASC, l.id ASC
            """.trimIndent(),
            arrayOf(id, fromDate, toDate)
        ).use { c ->
            while (c.moveToNext()) {
                val date = c.getString(0).orEmpty()
                val debit = c.getString(1) == "DEBIT"
                val amount = c.getDouble(2)
                val billNumber = c.getString(3)?.takeIf { it.isNotBlank() }
                val particulars = if (debit) "Credit sale" else "Payment received"
                val reference = when {
                    billNumber != null -> "Bill $billNumber"
                    !c.isNull(4) -> "Bill #${c.getLong(4)}"
                    else -> ""
                }
                rows.add(
                    Row(
                        date = date,
                        particulars = particulars,
                        reference = reference,
                        moneyIn = if (debit) 0.0 else amount,
                        moneyOut = if (debit) amount else 0.0
                    )
                )
            }
        }

        val totalIn = BillRounding.toPaise(rows.sumOf { it.moneyIn })
        val totalOut = BillRounding.toPaise(rows.sumOf { it.moneyOut })
        val opening = BillRounding.toPaise(closing - totalOut + totalIn)

        // The balance column is walked forward from opening rather than read off the
        // stored `balance` on each row: that column holds the balance at the time the
        // line was written, which a later correction elsewhere can leave stale.
        var running = opening
        val entries = rows.map { row ->
            running = BillRounding.toPaise(running + row.moneyOut - row.moneyIn)
            Entry(
                date = row.date,
                particulars = row.particulars,
                reference = row.reference,
                `in` = row.moneyIn,
                out = row.moneyOut,
                balance = running
            )
        }

        return Ledger(
            customer = customer,
            fromDate = fromDate,
            toDate = toDate,
            opening = opening,
            entries = entries,
            totalIn = totalIn,
            totalOut = totalOut,
            closing = closing
        )
    }
}
