package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.CustomerDao

/**
 * Putting a restaurant sale on a customer's account - the restaurant's side of the
 * grocery checkout's Credit tile, and deliberately the same in every step.
 *
 * The gate is the grocery till's: a credit sale needs a customer on the master, with
 * a name and a phone, Credit switched on, and enough of their limit left to carry
 * what is payable. Falling short is answered the grocery way too - the same "Credit
 * not enabled" and "Credit limit too low" prompts, each opening the same
 * [CreditCustomerDialog] the grocery checkout opens, and a customer with nobody on
 * file goes straight to that form.
 *
 * The booking is the grocery till's as well: the bill is a CREDIT bill with the
 * customer on it, which BillDao.createBill posts to the ledger, the balance and the
 * limit - including whatever was handed over against it at the counter.
 */
object CreditSale {

    /** Whether a sale of [payable] can go on the account behind [phone], and if not, why. */
    sealed class Verdict {
        data class Ok(val customer: CustomerDao.Customer) : Verdict()
        /** Nobody on file for the phone, or a record without the name a credit bill needs. */
        data class NeedsDetails(val onFile: CustomerDao.Customer?) : Verdict()
        data class CreditOff(val customer: CustomerDao.Customer) : Verdict()
        data class LimitTooLow(val customer: CustomerDao.Customer) : Verdict()
    }

    fun verdict(context: Context, phone: String?, payable: Double): Verdict {
        val onFile = phone?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { CustomerDao(context).findByPhone(it) }.getOrNull() }
        return when {
            onFile == null || onFile.name.isBlank() || onFile.phone.isBlank() -> Verdict.NeedsDetails(onFile)
            !onFile.creditEnabled -> Verdict.CreditOff(onFile)
            // credit_limit is what is LEFT - BillDao brings it down by every credit
            // sale - so it is compared with this sale alone, as grocery does.
            onFile.creditLimit < payable - 0.005 -> Verdict.LimitTooLow(onFile)
            else -> Verdict.Ok(onFile)
        }
    }

    /**
     * Calls [onReady] with the customer this sale of [payable] goes on, once there is
     * one it can go on: straight away when [phone] already names such a customer,
     * otherwise once the prompts and the form have put it right. Backing out of any of
     * them calls nothing, and the sale stays unpaid for another mode to be chosen.
     */
    fun ensure(
        context: Context,
        phone: String?,
        payable: Double,
        money: (Double) -> String,
        onReady: (CustomerDao.Customer) -> Unit
    ) {
        when (val v = verdict(context, phone, payable)) {
            is Verdict.Ok -> onReady(v.customer)
            is Verdict.NeedsDetails -> openForm(context, phone, v.onFile, payable, money, onReady)
            // The grocery checkout's own two refusals, word for word - see
            // PosCheckoutFragment.showCreditDisabledDialog and showCreditLimitDialog.
            is Verdict.CreditOff -> {
                val who = v.customer.name.takeIf { it.isNotBlank() } ?: v.customer.phone
                DialogUtils.showConfirm(
                    context = context,
                    title = "Credit not enabled",
                    message = "Credit is switched off for $who. Turn Credit on in the customer's " +
                        "details before billing this sale to their account.",
                    positiveText = "Open details",
                    negativeText = "Cancel",
                    onConfirm = { openForm(context, v.customer.phone, v.customer, payable, money, onReady) }
                )
            }
            is Verdict.LimitTooLow -> DialogUtils.showConfirm(
                context = context,
                title = "Credit limit too low",
                message = "This sale comes to ${money(payable)}, but only ${money(v.customer.creditLimit)} of credit " +
                    "is left on the account. Raise the credit limit to at least ${money(payable)} " +
                    "to bill it to their account.",
                positiveText = "Open details",
                negativeText = "Cancel",
                onConfirm = { openForm(context, v.customer.phone, v.customer, payable, money, onReady) }
            )
        }
    }

    /**
     * The grocery credit form, opened on whatever is known, then straight back through
     * the gate on what was saved - so a limit still short of the bill says so again
     * rather than letting the sale through, exactly as the grocery checkout does.
     */
    private fun openForm(
        context: Context,
        phone: String?,
        onFile: CustomerDao.Customer?,
        payable: Double,
        money: (Double) -> String,
        onReady: (CustomerDao.Customer) -> Unit
    ) {
        CreditCustomerDialog.show(
            context = context,
            prefill = CreditCustomerDialog.Prefill(
                phone = onFile?.phone ?: phone.orEmpty(),
                name = onFile?.name.orEmpty(),
                address = onFile?.address.orEmpty(),
                gstin = onFile?.gstin.orEmpty()
            ),
            onFile = onFile,
            money = money
        ) { saved -> ensure(context, saved.phone, payable, money, onReady) }
    }

    /**
     * The customer behind [phone], as the grocery checkout's info button shows them -
     * credit status, limit and balance included - or a toast when there is nobody.
     */
    fun showCustomer(context: Context, phone: String?) {
        val p = phone?.trim().orEmpty()
        val onFile = p.takeIf { it.isNotEmpty() }
            ?.let { runCatching { CustomerDao(context).findByPhone(it) }.getOrNull() }
        if (onFile == null && p.isEmpty()) {
            android.widget.Toast.makeText(context, "No customer on this sale", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        CustomerCardDialog.show(
            context = context,
            inflater = android.view.LayoutInflater.from(context),
            customer = CustomerCardDialog.Customer(
                name = onFile?.name.orEmpty(),
                phone = onFile?.phone ?: p,
                address = onFile?.address.orEmpty(),
                gstin = onFile?.gstin.orEmpty(),
                creditEnabled = onFile?.creditEnabled ?: false,
                creditLimit = onFile?.creditLimit ?: 0.0,
                balance = onFile?.balance ?: 0.0
            ),
            status = if (onFile == null) "NOT ON FILE" else "BILLING TO",
            note = if (onFile == null) "This customer is not in the customer master yet." else null
        )
    }

    /**
     * Wires the Credit panel ([R.layout.view_credit_payment]) inside [root]: the
     * amount handed over now, and the BALANCE DUE left on the account, kept up to date
     * as it is typed - the grocery checkout's own sum, floored at zero on screen.
     * Returns a reader for the amount handed over.
     */
    fun bindPanel(root: android.view.View, payable: () -> Double, money: (Double) -> String): () -> Double {
        val et = root.findViewById<com.google.android.material.textfield.TextInputEditText>(
            com.example.synergic_pos_offline.R.id.etCreditPaid
        )
        val tv = root.findViewById<android.widget.TextView>(com.example.synergic_pos_offline.R.id.tvCreditBalanceDue)
        val paid = { Amounts.parse(et?.text?.toString()) ?: 0.0 }
        val refresh = { tv?.text = money((payable() - paid()).coerceAtLeast(0.0)) }
        et?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refresh()
        })
        refresh()
        return paid
    }

    /** Shows or hides the Credit panel inside [root]. */
    fun showPanel(root: android.view.View, on: Boolean) {
        root.findViewById<android.view.View>(com.example.synergic_pos_offline.R.id.sectionCreditPay)
            ?.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * What the customer will owe once a credit sale of [payable] is booked with
     * [paidNow] handed over against it - the figure the slip prints as outstanding,
     * and the same sum BillDao writes. Not floored at zero: paying more than the bill
     * brings an older balance down, and can leave the customer in credit.
     */
    fun outstandingAfter(context: Context, phone: String?, payable: Double, paidNow: Double): Double? {
        val onFile = phone?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { CustomerDao(context).findByPhone(it) }.getOrNull() } ?: return null
        return BillRounding.toPaise(onFile.balance + payable - paidNow)
    }
}
