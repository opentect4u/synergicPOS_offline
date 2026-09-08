package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the Tax & Discount settings as key/value rows in
 * [DatabaseHelper.Tables.MD_APP_SETTINGS], scoped to the current store.
 *
 * Every row uses setting_type 'T' (tax settings). Booleans are stored as "1"/"0"
 * and enum choices as their name.
 */
class TaxSettingsDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_APP_SETTINGS

    /** Whether GST amounts are included in the price or added on top. Persisted as [code]. */
    enum class GstMode(val code: String) {
        INCLUSIVE("I"), EXCLUSIVE("E");
        companion object {
            fun fromCode(value: String?): GstMode? =
                value?.let { v -> values().firstOrNull { it.code.equals(v, true) || it.name.equals(v, true) } }
        }
    }

    /** The single selected discount type (radio). Persisted as its [code] 1-2. */
    enum class DiscountType(val code: Int) {
        ITEM_WISE(1), BILL_WISE(2);
        companion object {
            fun fromCode(value: String?): DiscountType? =
                value?.toIntOrNull()?.let { c -> values().firstOrNull { it.code == c } }
        }
    }

    /** The single selected discount position (radio). Persisted as its [code] 1-2. */
    enum class DiscountPosition(val code: Int) {
        PRE_TAX(1), POST_TAX(2);
        companion object {
            fun fromCode(value: String?): DiscountPosition? =
                value?.toIntOrNull()?.let { c -> values().firstOrNull { it.code == c } }
        }
    }

    /**
     * Full tax/discount configuration.
     *
     * Discount: [discountEnabled] gates a single [discountType], radio-selected when
     * discount is on. [discountPosition] says whether the discount comes off before or
     * after tax, and only ONE of the four combinations is actually a choice:
     *
     * - Item-wise, either mode: PRE-TAX. The discount is attached to a product, so it
     *   comes off that product and the line is taxed on what is left.
     * - Bill-wise + MRP: POST-TAX. An inclusive price is what the customer pays, so a
     *   whole-bill discount comes off the payable figure, which is after tax by
     *   definition - the taxable value is worked back out of it.
     * - Bill-wise + Exclusive: the operator's to pick. The bill genuinely has a
     *   before-tax figure and an after-tax one, and shops differ on which they mean.
     *
     * It is what the rest of the app prices against, so [load] is where those rules
     * are enforced rather than the screen - every caller reads this, not the radio.
     *
     * Tax: [taxEnabled] switches tax on or off store-wide; [taxMode] is the one
     * shared Inclusive/Exclusive setting. Which tax a given sale carries - GST or
     * VAT - is not decided here any more: it is the product's own business, read
     * off whichever of its rate fields (cgst/sgst vs vat) it actually has set. See
     * [com.example.synergic_pos_offline.utils.GstCalculator.regimeOf].
     *
     * Defaults to MRP with tax on and any discount post-tax - what a till reads with
     * nothing ever saved (a fresh install) or once [resetToFreshTillDefault] has run
     * (every bill erased) - not Exclusive with tax off, which used to be both.
     */
    data class TaxSettings(
        val discountEnabled: Boolean = false,
        val discountType: DiscountType = DiscountType.ITEM_WISE,
        val discountPosition: DiscountPosition = DiscountPosition.POST_TAX,
        // Tax
        val taxEnabled: Boolean = true,
        val taxMode: GstMode = GstMode.INCLUSIVE
    )

    /**
     * Reads every tax setting for the current store, applying defaults - including
     * the saved discount position, bounded by the tax mode it was saved under; see
     * [discountPosition][TaxSettings.discountPosition].
     *
     * [taxEnabled]/[taxMode] fall back to the old separate GST/VAT keys when the
     * unified ones have never been written for this store - a store saved before
     * GST and VAT collapsed into one switch. Once this store saves again, [save]
     * writes only the unified keys and this fallback stops mattering for it.
     */
    fun load(): TaxSettings {
        val m = readAll()
        val d = TaxSettings()
        val type = DiscountType.fromCode(m[KEY_DISCOUNT_TYPE]) ?: d.discountType
        val legacyVatOn = m[KEY_LEGACY_VAT_ENABLED]?.toBool() == true
        val legacyGstOn = m[KEY_LEGACY_GST_ENABLED]?.toBool() == true
        val taxMode = GstMode.fromCode(m[KEY_TAX_MODE])
            ?: GstMode.fromCode(if (legacyVatOn) m[KEY_LEGACY_VAT_MODE] else m[KEY_LEGACY_GST_MODE])
            ?: d.taxMode
        // WHAT WAS PICKED. This used to be pinned to POST_TAX here, from when Pre-tax
        // was disabled outright - and it stayed pinned after the screen made Pre-tax a
        // real choice again. [save] wrote the operator's answer and this threw it away
        // on the way back in, so Tax Settings reopened on Post-tax however Pre-tax was
        // set, and every consumer priced post-tax because they all read it from here.
        //
        // "0" is what [save] writes when discount is off, and fromCode has no 0 - so
        // that falls to the default, which is what a bill with no discount would use
        // anyway.
        val position = DiscountPosition.fromCode(m[KEY_DISCOUNT_POSITION]) ?: d.discountPosition
        return TaxSettings(
            discountEnabled = m[KEY_DISCOUNT_ENABLED]?.toBool() ?: d.discountEnabled,
            discountType = type,
            // WHICH OF THE TWO MOMENTS, settled here rather than only on the screen,
            // because every caller that prices a sale reads this and not the radio.
            //
            // ITEM-WISE IS PRE-TAX, UNDER EITHER TAX MODE - which is what the screen
            // now offers, so this is where a row saved under the older rule is read the
            // way the screen would save it today.
            //
            // A discount configured against a PRODUCT comes off that product, and the
            // line is taxed on what is left. That is what pre-tax means, and it is true
            // whether the price it comes off was quoted with the tax inside it (MRP) or
            // added on top (Exclusive) - an inclusive price has a genuine before-tax
            // base to discount too, see
            // com.example.synergic_pos_offline.utils.GstCalculator.taxableBase. The mode
            // decides where the base figure comes from, not when the discount lands.
            //
            // This carried an EXCLUSIVE qualifier before, so Item-wise under MRP read
            // back whatever was picked and could price post-tax. Settled here rather
            // than only on the screen, because every caller that prices a sale reads
            // this and not the radio - a till left on that combination is corrected on
            // the way in rather than pricing one way while the screen shows another.
            // AND BILL-WISE UNDER MRP IS POST-TAX, the mirror of it - which leaves the
            // two MRP combinations with one answer each and nothing to read back.
            //
            // An MRP is what the customer pays, tax already inside. A whole-bill
            // discount under that comes off the payable figure, and that figure is
            // after tax by definition: the bill's taxable value is worked back out of
            // it, so there is no before-tax bill to discount. Item-wise escapes this
            // because it has a PRODUCT's own price to strip to a base; a whole-bill
            // discount has no equivalent.
            discountPosition = when {
                type == DiscountType.ITEM_WISE -> DiscountPosition.PRE_TAX
                taxMode == GstMode.INCLUSIVE -> DiscountPosition.POST_TAX
                else -> position
            },
            taxEnabled = m[KEY_TAX_ENABLED]?.toBool() ?: (legacyGstOn || legacyVatOn),
            taxMode = taxMode
        )
    }

    /**
     * Resets Tax Settings to what a till with no bills is meant to read: MRP, with
     * tax on and any discount taken post-tax - not whatever mode the till happened to
     * be configured in before its bills went.
     *
     * Called once the erase-bills flow ([com.example.synergic_pos_offline.utils.BillErase.erase])
     * has actually left the till with none, so a wiped till reads exactly as a
     * brand-new one would. Discount on/off and its type are left exactly as they
     * were - erasing the bills is not a reason to also drop a shop's own discount
     * configuration.
     */
    fun resetToFreshTillDefault() {
        val current = load()
        save(
            current.copy(
                taxEnabled = true,
                taxMode = GstMode.INCLUSIVE,
                discountPosition = DiscountPosition.POST_TAX
            )
        )
    }

    /** Writes every tax setting for the current store (upsert per key). When discount
     *  is disabled, the type is stored as 0; when enabled, it holds the selected value. */
    fun save(s: TaxSettings) {
        put(KEY_DISCOUNT_ENABLED, s.discountEnabled.b())
        put(KEY_DISCOUNT_TYPE, if (s.discountEnabled) s.discountType.code.toString() else "0")
        // What was picked, not what the type implies - see the note on load().
        put(
            KEY_DISCOUNT_POSITION,
            if (s.discountEnabled) s.discountPosition.code.toString() else "0"
        )
        put(KEY_TAX_ENABLED, s.taxEnabled.b())
        // ALWAYS STORED, even while tax is switched off.
        //
        // It used to be nulled when tax was off, on the reasoning that a mode means
        // nothing with no tax to apply. That became unsafe once changing the mode
        // started costing the bills (see TaxSettingsFragment.onTaxModeChosen): a shop
        // on MRP could switch tax off, leave the screen, and come back to a till that
        // had quietly fallen to the Exclusive default - the mode changed, the bills
        // still there, and nobody asked. Keeping the answer means the only way to
        // change it is the way that asks.
        //
        // Nothing is charged differently for it: whether tax applies at all is
        // [KEY_TAX_ENABLED]'s business, and it still says no.
        put(KEY_TAX_MODE, s.taxMode.code)
        helper.regroupAppSettingsByType()
        com.example.synergic_pos_offline.utils.SettingsCache.storeFromDb(appContext, "Tax settings save (type T)")
    }

    // ---- Low-level key/value access ----------------------------------------

    private fun readAll(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val store = currentStoreId()
        val (where, args) = if (store != null) "store_id=?" to arrayOf(store.toString()) else null to null
        helper.readableDatabase.query(
            table, arrayOf("setting_name", "setting_value"),
            where, args, null, null, "setting_type ASC, setting_name ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                map[name] = c.getString(1).orEmpty()
            }
        }
        return map
    }

    /** Inserts or updates a single setting row for the current store (type 'T').
     *  A null [value] is stored as SQL NULL. */
    private fun put(name: String, value: String?) {
        val db = helper.writableDatabase
        val store = currentStoreId()
        val values = ContentValues().apply {
            put("setting_name", name)
            if (value == null) putNull("setting_value") else put("setting_value", value)
            put("setting_type", "T")
            put("device_id", currentDeviceId())
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        val where = if (store != null) "setting_name=? AND store_id=?" else "setting_name=?"
        val args = if (store != null) arrayOf(name, store.toString()) else arrayOf(name)
        val updated = db.update(table, values, where, args)
        if (updated == 0) {
            values.put("store_id", store)
            values.put("created_by", currentUser())
            db.insert(table, null, values)
        }
    }

    private fun Boolean.b(): String = if (this) "1" else "0"
    private fun String.toBool(): Boolean = this == "1" || equals("true", true) || equals("yes", true)

    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** Device id captured at registration, mirrored onto each settings row. */
    private fun currentDeviceId(): String? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("device_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val KEY_DISCOUNT_ENABLED = "Discount"
        private const val KEY_DISCOUNT_TYPE = "Discount Type"
        private const val KEY_DISCOUNT_POSITION = "Discount Position"
        private const val KEY_TAX_ENABLED = "Tax"
        private const val KEY_TAX_MODE = "Tax Type"
        // No longer written - read only, as a fallback for a store that saved
        // settings before GST and VAT collapsed into one switch. See load().
        private const val KEY_LEGACY_GST_ENABLED = "GST"
        private const val KEY_LEGACY_GST_MODE = "GST Type"
        private const val KEY_LEGACY_VAT_ENABLED = "VAT"
        private const val KEY_LEGACY_VAT_MODE = "VAT Type"
    }
}
