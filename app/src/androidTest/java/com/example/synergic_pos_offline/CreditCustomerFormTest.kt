package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.synergic_pos_offline.utils.CreditCustomerForm
import com.example.synergic_pos_offline.utils.CreditCustomerForm.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The credit-sale customer form says why it will not save, and on which field.
 *
 * At the counter it said nothing. The three rules below each refused with a toast -
 * raised over a dialog, where it is gone, or never drawn at all, before anybody reads
 * it - so an operator who filled the form in and pressed Save watched nothing happen
 * and concluded the details were not being saved. They were not: the form was
 * refusing, silently.
 *
 * So a refusal now carries the FIELD it belongs to, which is what lets the dialog put
 * the reason under the input that caused it. That pairing is what these assert -
 * naming the wrong field would put the message on an input the operator has no reason
 * to look at, which is only marginally better than the toast.
 */
@RunWith(AndroidJUnit4::class)
class CreditCustomerFormTest {

    private companion object {
        const val PHONE = "9812345670"
        const val NAME = "Ledger customer"
    }

    /** As the till formats a figure, so the reason reads like the screen around it. */
    private val money: (Double) -> String = { "₹" + String.format("%.2f", it) }

    private fun refusal(
        phone: String = PHONE,
        name: String = NAME,
        creditEnabled: Boolean = true,
        limit: Double = 5000.0,
        balance: Double = 0.0
    ) = CreditCustomerForm.refusal(phone, name, creditEnabled, limit, balance, money)

    // ---- What refuses, and where it says so ----------------------------------------

    @Test
    fun aCompleteFormSaves() {
        assertNull("nothing is wrong with this form", refusal())
    }

    @Test
    fun aShortPhoneIsRefusedOnThePhoneField() {
        val r = refusal(phone = "98123")
        assertNotNull("a five-digit number is not a phone number", r)
        assertEquals(Field.PHONE, r!!.field)
        assertTrue("and it should say what is wrong with it", r.message.contains("10 digits"))
    }

    @Test
    fun anUntypedPhoneIsRefusedTheSameWay() {
        assertEquals(Field.PHONE, refusal(phone = "")!!.field)
    }

    /**
     * A number with the right length but the wrong contents is still wrong.
     *
     * The guard against checking only `length`: the field takes digits, but the value
     * reaching the rule is a string, and the master stores whatever it is given.
     */
    @Test
    fun tenCharactersThatAreNotDigitsAreRefused() {
        assertEquals(Field.PHONE, refusal(phone = "98123abcde")!!.field)
    }

    @Test
    fun aMissingNameIsRefusedOnTheNameField() {
        val r = refusal(name = "   ")
        assertEquals(Field.NAME, r!!.field)
        assertTrue(r.message.contains("name", ignoreCase = true))
    }

    /**
     * A limit below what the customer already owes is refused on the LIMIT field.
     *
     * This is the one that was actually hit in the shop: a customer carrying a balance
     * with the limit left at 0. The message names the figure it is being measured
     * against, because "too low" alone does not say how low.
     */
    @Test
    fun aLimitUnderTheOutstandingIsRefusedOnTheLimitField() {
        val r = refusal(limit = 0.0, balance = 1000.0)
        assertEquals(Field.LIMIT, r!!.field)
        assertTrue(
            "the reason should name the outstanding: was \"${r.message}\"",
            r.message.contains("1000.00")
        )
    }

    @Test
    fun aLimitThatExactlyCoversTheOutstandingIsFine() {
        assertNull(refusal(limit = 1000.0, balance = 1000.0))
    }

    /**
     * The limit rule only applies to a customer given credit.
     *
     * With credit off there is no limit to be under, so the figures are not compared -
     * otherwise turning credit off on a customer who owes money would be impossible.
     */
    @Test
    fun theLimitRuleDoesNotApplyWhenCreditIsOff() {
        assertNull(refusal(creditEnabled = false, limit = 0.0, balance = 1000.0))
    }

    /**
     * The phone is answered before the name, and the name before the limit.
     *
     * One message at a time, on the field furthest up the form, so fixing what it
     * points at moves the operator down rather than sideways.
     */
    @Test
    fun theFirstThingWrongIsTheOneReported() {
        assertEquals(
            Field.PHONE,
            refusal(phone = "1", name = "", limit = 0.0, balance = 1000.0)!!.field
        )
        assertEquals(Field.NAME, refusal(name = "", limit = 0.0, balance = 1000.0)!!.field)
    }

    // ---- What gets written back ----------------------------------------------------

    /**
     * Switching credit off keeps what the customer already owes.
     *
     * The balance field is disabled with credit off, so there is no typed figure to
     * save - and zeroing it would settle a debt by flicking a switch.
     */
    @Test
    fun turningCreditOffCarriesTheBalanceOverRatherThanZeroingIt() {
        assertEquals(
            1000.0,
            CreditCustomerForm.balanceToSave(creditEnabled = false, typed = 0.0, onFile = 1000.0),
            0.005
        )
    }

    /**
     * And the record it is carried from is the one the PHONE belongs to.
     *
     * The bug this pins: the dialog read the balance off whatever customer the sale
     * carried when it opened, which on a walk-in is nobody. Passing that null here
     * stands for that case - it must not be how a real customer's balance is decided,
     * so the caller has to look the number up. A null is the absence of a record, and
     * a customer with no record owes nothing.
     */
    @Test
    fun noRecordMeansNothingOwed() {
        assertEquals(
            0.0,
            CreditCustomerForm.balanceToSave(creditEnabled = false, typed = 0.0, onFile = null),
            0.005
        )
    }

    @Test
    fun withCreditOnTheTypedBalanceIsWhatIsSaved() {
        assertEquals(
            250.0,
            CreditCustomerForm.balanceToSave(creditEnabled = true, typed = 250.0, onFile = 1000.0),
            0.005
        )
    }
}
