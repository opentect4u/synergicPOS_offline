package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CRUD for [DatabaseHelper.Tables.MD_CHARGES] - the shop's own extra charges, added
 * to a bill on top of what was sold: service, packing, delivery.
 *
 * ## What a charge is
 *
 * A NAME and a PERCENTAGE, plus a switch. The percentage is taken on the bill's ITEM
 * LINES BEFORE ANY TAX - see [amountsOn] - so two items at 100 and 200 give a 5%
 * charge of 15, whatever tax is then charged on the goods themselves.
 *
 * ## Why three
 *
 * [MAX_CHARGES] is a limit on a slip, not on the database. Every enabled charge is a
 * line the customer reads between the goods and the total, and a bill that lists six
 * additions before its total stops reading as a bill for the things that were bought.
 * Three covers what a shop actually levies - service, packing, delivery - and the
 * master refuses a fourth rather than quietly printing it.
 *
 * Store-scoped by the signed-in user's store, like every other master.
 */
class ChargeDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val table = DatabaseHelper.Tables.MD_CHARGES

    enum class Type {
        PERCENTAGE, AMOUNT
    }

    /**
     * The order types a charge applies to.
     *
     * ## Why this is a SET and not one word
     *
     * The column used to hold one of BOTH / TAKEAWAY / DINE_IN / NONE, which was enough
     * while a restaurant had two modes. With QSR there are three, and a single word
     * cannot say "Dine In and QSR but not the counter" - the case that has no spelling.
     * "BOTH" could not even say which two it meant.
     *
     * So the column now holds a comma list of [Mode] names - "DINE_IN,QSR" - and this
     * type is the set behind it. Everything that used to compare against one enum value
     * now asks [applies].
     *
     * ## Reading what is already there
     *
     * The old words are still understood, so no migration is needed and a shop's
     * charges keep working across the upgrade:
     *
     *  * `BOTH` -> every mode. A charge the shop said applies everywhere should apply
     *    to QSR too; it was set before QSR existed, and "everywhere" is what it meant.
     *  * `TAKEAWAY` / `DINE_IN` -> that one mode.
     *  * `NONE`, or anything unreadable -> nothing, which is the safe way to be wrong:
     *    a charge that fails to appear is noticed and fixed, one that appears on bills
     *    it should not have been on is money taken by mistake.
     */
    enum class Mode {
        DINE_IN, TAKEAWAY, QSR;

        companion object {
            /** The order type strings [amountsOn] is called with. */
            fun of(orderType: String?): Mode? = when (orderType?.uppercase()) {
                "DINE_IN" -> DINE_IN
                "TAKEAWAY" -> TAKEAWAY
                "QSR" -> QSR
                else -> null
            }
        }
    }

    @JvmInline
    value class Applicability(val modes: Set<Mode>) {

        /** Whether this charge belongs on an order of [mode]. */
        fun applies(mode: Mode?): Boolean = mode != null && mode in modes

        /** Nothing ticked - the charge is set up but not in use anywhere. */
        val none: Boolean get() = modes.isEmpty()

        /**
         * Every mode ticked - what the old `BOTH` meant, and what a GROCERY sale asks
         * for. See [amountsOn].
         */
        val all: Boolean get() = modes.containsAll(Mode.entries)

        /**
         * How the column stores it: the mode names, comma separated, in enum order.
         *
         * NOTHING TICKED IS WRITTEN AS "NONE", not as an empty string. A blank column
         * means the field was never written - a row from before it existed - and that
         * is read back as "everywhere", which is what those rows meant. Writing an
         * empty string for "applies nowhere" would collide with it, and the charge
         * would come back applying to every mode: the exact opposite of what was saved.
         */
        fun store(): String =
            if (modes.isEmpty()) "NONE"
            else Mode.entries.filter { it in modes }.joinToString(",") { it.name }

        companion object {
            val ALL = Applicability(Mode.entries.toSet())
            val NONE = Applicability(emptySet())

            /**
             * Reads the column, in either the new form or the old.
             *
             * A value with no comma might be one of the legacy words or a single mode
             * name; both are handled by falling through to the mode lookup after the
             * legacy names have had their turn.
             */
            fun parse(raw: String?): Applicability {
                val text = raw?.trim().orEmpty()
                if (text.isEmpty()) return ALL
                when (text.uppercase()) {
                    "BOTH" -> return ALL
                    "NONE" -> return NONE
                }
                val modes = text.split(",")
                    .mapNotNull { Mode.of(it.trim()) }
                    .toSet()
                return Applicability(modes)
            }
        }
    }

    /**
     * Which of the two masters a row belongs to - the same table holds both, told
     * apart by this flag rather than split into a second table, since a charge is a
     * charge either way: a name, a value, a type, a switch, an audience.
     *
     * [PARCEL] is a shop's single Parcel Charge - see [ChargesFragment] for where its
     * name is fixed rather than typed, and why it can only ever be a [TAKEAWAY] or
     * [DINE_IN] audience, never [BOTH]: it never reaches a grocery bill.
     */
    enum class Kind {
        EXTRA, PARCEL
    }

    data class Charge(
        val id: Long,
        val name: String,
        val value: Double,  // percentage value (e.g., 5) or amount value (e.g., 50)
        val type: Type,      // PERCENTAGE or AMOUNT
        val enabled: Boolean,
        val applicability: Applicability = Applicability.ALL,
        val kind: Kind = Kind.EXTRA
    )

    /** One charge worked out against a particular bill. */
    data class Applied(
        val name: String, val value: Double, val type: Type, val amount: Double,
        val applicability: Applicability = Applicability.ALL, val kind: Kind = Kind.EXTRA
    )

    /** Every charge in the master, enabled or not - what the master screen lists. */
    fun getAll(): List<Charge> {
        val list = mutableListOf<Charge>()
        val store = currentStoreId()
        helper.readableDatabase.query(
            table,
            arrayOf("id", "charge_name", "percentage", "charge_type", "is_enabled", "applicability", "charge_kind"),
            (if (store != null) "store_id = ? AND is_active = 1" else "is_active = 1"),
            store?.let { arrayOf(it.toString()) },
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Charge(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        value = c.getDouble(2),
                        type = try {
                            Type.valueOf(c.getString(3)?.uppercase() ?: "PERCENTAGE")
                        } catch (e: Exception) {
                            Type.PERCENTAGE
                        },
                        enabled = c.getInt(4) != 0,
                        // Reads the new comma list and the old single words alike -
                        // see [Applicability.parse].
                        applicability = Applicability.parse(c.getString(5)),
                        kind = try {
                            Kind.valueOf(c.getString(6)?.uppercase() ?: "EXTRA")
                        } catch (e: Exception) {
                            Kind.EXTRA
                        }
                    )
                )
            }
        }
        return list
    }

    /**
     * The charges that actually apply to a sale: switched on, and worth something.
     *
     * A charge left at 0 is dropped as well as a disabled one - it would print a line
     * saying nothing was added, which is a line the customer has to read to learn it
     * did not matter.
     */
    fun enabled(): List<Charge> = getAll().filter { it.enabled && it.value > 0.0001 }

    /**
     * [enabled] charges worked out against [itemsTotal] - the sum of the bill's item
     * lines, before any tax.
     *
     * Percentage charges are calculated as a percentage of itemsTotal.
     * Amount charges are fixed amounts added to the bill.
     *
     * [orderType] filters by [Applicability]: pass "DINE_IN", "TAKEAWAY" or "QSR" for a
     * restaurant order, so a charge comes back only on the modes it was ticked for.
     *
     * ## Grocery passes null, and asks a different question
     *
     * A grocery sale is not one of the three modes, so it cannot be filtered as though
     * it were. It gets the charges ticked for EVERY mode - which is exactly what the
     * old `BOTH` meant, and what `BOTH` still parses to, so a grocery bill carries the
     * same charges after this change as before it.
     *
     * Not "applies anywhere", which was the first thing tried here and is wrong: a
     * charge ticked for Dine In alone would then have appeared on grocery bills, which
     * is money taken by mistake from the till that never asked for it.
     *
     * A PARCEL charge never reaches a grocery bill whatever it is ticked for. It is a
     * restaurant charge by definition - the screen that defines it barred the old
     * "Both" for exactly this reason - so the rule belongs here, where it holds however
     * the master is edited, rather than in what the form allows.
     */
    fun amountsOn(itemsTotal: Double, orderType: String? = null): List<Applied> {
        if (itemsTotal <= 0.0) return emptyList()
        val mode = Mode.of(orderType)
        return enabled().filter {
            if (mode == null) it.kind == Kind.EXTRA && it.applicability.all
            else it.applicability.applies(mode)
        }.map {
            val amount = when (it.type) {
                Type.PERCENTAGE -> round2(itemsTotal * it.value / 100.0)
                Type.AMOUNT -> round2(it.value)
            }
            Applied(it.name, it.value, it.type, amount, it.applicability, it.kind)
        }
    }

    /** What [amountsOn] adds up to - the figure that joins the grand total. */
    fun totalOn(itemsTotal: Double): Double = round2(amountsOn(itemsTotal).sumOf { it.amount })

    fun insert(
        name: String, value: Double, type: Type, enabled: Boolean,
        applicability: Applicability = Applicability.ALL, kind: Kind = Kind.EXTRA
    ): Long {
        val v = ContentValues().apply {
            put("store_id", currentStoreId())
            put("charge_name", name)
            put("percentage", value)
            put("charge_type", type.name)
            put("is_enabled", if (enabled) 1 else 0)
            put("applicability", applicability.store())
            put("charge_kind", kind.name)
            put("is_active", 1)
            put("created_at", now())
            put("created_by", currentUser())
        }
        return helper.writableDatabase.insert(table, null, v)
    }

    fun update(
        id: Long, name: String, value: Double, type: Type, enabled: Boolean,
        applicability: Applicability = Applicability.ALL, kind: Kind = Kind.EXTRA
    ): Int {
        val v = ContentValues().apply {
            put("charge_name", name)
            put("percentage", value)
            put("charge_type", type.name)
            put("is_enabled", if (enabled) 1 else 0)
            put("applicability", applicability.store())
            put("charge_kind", kind.name)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        return helper.writableDatabase.update(table, v, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Removes charges by clearing is_active rather than deleting the rows.
     *
     * Bills already printed carry the charge they were printed with, and a report that
     * goes looking for the name behind an old figure should still find it.
     */
    fun delete(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val v = ContentValues().apply {
            put("is_active", 0)
            put("modified_at", now())
            put("modified_by", currentUser())
        }
        var n = 0
        ids.forEach { n += helper.writableDatabase.update(table, v, "id = ?", arrayOf(it.toString())) }
        return n
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private fun currentUser(): String? = SessionManager.auditUser
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** The most charges a shop may define - see the note on the class. */
        const val MAX_CHARGES = 3
    }
}
