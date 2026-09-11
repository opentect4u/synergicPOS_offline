package com.example.synergic_pos_offline.utils

/**
 * The rules the credit-sale customer form saves by.
 *
 * Held apart from the dialog that draws them for two reasons. The rules can be stated
 * and checked without a screen to put them on; and a refusal names the field it
 * belongs to, so the dialog can say why it refused where the operator is looking
 * rather than in a toast raised over it - which is gone, or never seen at all, long
 * before it is read. A form that refused silently was indistinguishable from a Save
 * button that did nothing, which is exactly what it looked like at the counter.
 */
object CreditCustomerForm {

    /** The field a refusal belongs to. */
    enum class Field { PHONE, NAME, LIMIT }

    /** Why the form will not save, and which field is at fault. */
    data class Refusal(val field: Field, val message: String)

    /**
     * The first thing wrong with the form, or null when it will save.
     *
     * @param money formats a figure the way the till shows it, so the reason reads in
     *        the same currency as the screen around it.
     */
    fun refusal(
        phone: String,
        name: String,
        creditEnabled: Boolean,
        limit: Double,
        balance: Double,
        money: (Double) -> String
    ): Refusal? = when {
        phone.length != 10 || !phone.all { it.isDigit() } ->
            Refusal(Field.PHONE, "Phone number must be exactly 10 digits")

        name.isBlank() ->
            Refusal(Field.NAME, "Customer name is required for a credit bill")

        // The limit is what the customer is allowed to owe, so it cannot be set below
        // what they already do - that would put them over their limit the moment it
        // was saved.
        creditEnabled && limit < balance - 0.005 ->
            Refusal(Field.LIMIT, "Cannot be below the ${money(balance)} outstanding")

        else -> null
    }

    /**
     * The balance to write back, given what is typed and what the customer already owes.
     *
     * A balance already run up is not settled by switching credit off, so with credit
     * off the figure on file is carried over rather than zeroed - as in the customer
     * master. [onFile] must be the record the typed PHONE NUMBER belongs to: the
     * operator reaches this form with no customer on the sale and types or picks one,
     * so the record the dialog opened against is usually nobody at all, and reading
     * the balance off that one wiped whatever the customer owed.
     */
    fun balanceToSave(creditEnabled: Boolean, typed: Double, onFile: Double?): Double =
        if (creditEnabled) typed else onFile ?: 0.0
}
