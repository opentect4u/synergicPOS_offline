package com.example.synergic_pos_offline.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.card.MaterialCardView
import java.text.NumberFormat
import java.util.Locale

/**
 * The live "snapshot" dashboard (per the design spec): a header, three KPI cards
 * (Sales / Payments / Customers), an Inventory card, and industry widgets that
 * switch between Restaurant and Grocery. The Operations section is intentionally
 * omitted. Values are illustrative placeholders pending real data wiring.
 */
class DashboardHomeFragment : Fragment() {

    // Semantic colours (kept regardless of theme): green = up/positive, red = down/alert.
    private val green = Color.parseColor("#1E8E3E")
    private val red = Color.parseColor("#D93025")
    private val hairline = Color.parseColor("#E2E6E6")

    // The app's dynamic accent drives every card bar/label so the dashboard matches theme.
    private var accent = 0
    private var textMain = 0
    private var textSec = 0

    private lateinit var content: LinearLayout
    private var restaurant = true
    private lateinit var stats: Stats

    /** Live figures pulled from the data tables; everything else stays 0. */
    private data class Stats(
        val salesToday: Double = 0.0, val billsToday: Int = 0, val avgBill: Double = 0.0,
        val collected: Double = 0.0, val upi: Double = 0.0, val card: Double = 0.0,
        /** Count of cash / credit sales today. */
        val cashCount: Int = 0, val creditCount: Int = 0,
        val customersToday: Int = 0, val repeatToday: Int = 0, val newMembers: Int = 0,
        val totalSkus: Int = 0, val lowStock: Int = 0, val outOfStock: Int = 0
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        accent = ThemeManager.getThemeColor(ctx)
        textMain = ContextCompat.getColor(ctx, R.color.text_main)
        textSec = ContextCompat.getColor(ctx, R.color.text_secondary)
        restaurant = GeneralSettingsDao(ctx).load().mode == GeneralSettingsDao.Mode.RESTAURANT
        stats = loadStats()
        content = view.findViewById(R.id.llDashboardContent)
        build()

        // Pull-to-refresh: re-read Mode + figures and rebuild.
        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefresh).apply {
            setColorSchemeColors(accent)
            setOnRefreshListener {
                accent = ThemeManager.getThemeColor(ctx)
                restaurant = GeneralSettingsDao(ctx).load().mode == GeneralSettingsDao.Mode.RESTAURANT
                stats = loadStats()
                build()
                isRefreshing = false
            }
        }
    }

    /** Re-reads the theme accent and rebuilds the cards (called on palette change). */
    fun refreshTheme() {
        if (!isAdded || !::content.isInitialized) return
        accent = ThemeManager.getThemeColor(requireContext())
        build()
    }

    /** Computes today's figures from td_bills / td_payments / md_products / md_batch_stock. */
    private fun loadStats(): Stats {
        return try {
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
            fun num(sql: String): Double = db.rawQuery(sql, null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else 0.0
            }
            val today = "date('now','localtime')"
            // A voided bill never counted, and a bill that has come back on a sale
            // return stops counting - see [BillDao.countableBillClause], which is the
            // same rule the reports are to use so today's figures and a later report
            // over today cannot disagree.
            val billOk = "date(COALESCE(bill_date_time, bill_date)) = $today AND " +
                BillDao.countableBillClause()
            val bills = num("SELECT COUNT(*) FROM td_bills WHERE $billOk").toInt()
            val sales = num("SELECT COALESCE(SUM(net_amount),0) FROM td_bills WHERE $billOk")
            fun pay(mode: String?) = num(
                "SELECT COALESCE(SUM(p.amount_paid),0) FROM td_payments p " +
                    "JOIN td_bills b ON b.receipt_no = p.bill_id " +
                    "WHERE date(COALESCE(b.bill_date_time,b.bill_date)) = $today AND " +
                    BillDao.countableBillClause("b") +
                    (if (mode != null) " AND p.payment_mode = '$mode'" else "")
            )
            // Count of sales by bill type (cash / credit) today.
            fun billCount(type: String) =
                num("SELECT COUNT(*) FROM td_bills WHERE $billOk AND bill_type = '$type'").toInt()
            val stockOf = "COALESCE((SELECT SUM(current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s WHERE s.product_id = p.id),0)"
            Stats(
                salesToday = sales,
                billsToday = bills,
                avgBill = if (bills > 0) sales / bills else 0.0,
                collected = pay(null),
                // UPI and online collections read together — online payments settle via UPI rails.
                upi = pay("UPI") + pay("ONLINE"),
                card = pay("CARD"),
                cashCount = billCount("CASH"),
                creditCount = billCount("CREDIT"),
                customersToday = num("SELECT COUNT(DISTINCT customer_id) FROM td_bills WHERE $billOk AND customer_id IS NOT NULL").toInt(),
                // Repeat = customers served today who also have a real (countable) purchase on an earlier day.
                repeatToday = num(
                    "SELECT COUNT(DISTINCT customer_id) FROM td_bills WHERE $billOk AND customer_id IS NOT NULL " +
                        "AND customer_id IN (SELECT customer_id FROM td_bills " +
                        "WHERE date(COALESCE(bill_date_time,bill_date)) < $today AND customer_id IS NOT NULL AND " +
                        BillDao.countableBillClause() + ")"
                ).toInt(),
                newMembers = num("SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} WHERE date(created_at) = $today").toInt(),
                totalSkus = num("SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS}").toInt(),
                outOfStock = num("SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p WHERE $stockOf <= 0").toInt(),
                lowStock = num(
                    "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p " +
                        "WHERE p.stock_alert_qty > 0 AND $stockOf > 0 AND $stockOf <= p.stock_alert_qty"
                ).toInt()
            )
        } catch (_: Exception) {
            Stats()
        }
    }

    private fun money(v: Double): String =
        "₹" + NumberFormat.getInstance(Locale.Builder().setLanguage("en").setRegion("IN").build()).format(v.toLong())

    // ---- Assembly ----------------------------------------------------------

    private fun build() {
        val ctx = requireContext()
        content.removeAllViews()

        content.addView(snapshotHeader(ctx))

        // KPI row: Sales / Payments / Customers. Delta% has no source yet -> 0%.
        val kpis = row(ctx)
        kpis.addView(
            statCard(
                ctx, accent, "SALES", "VS YESTERDAY", money(stats.salesToday), "0%", textSec, "${stats.billsToday} bills",
                listOf("BILLS" to stats.billsToday.toString(), "AVERAGE BILL" to money(stats.avgBill))
            ) { navigate(BillListFragment()) }.weighted()
        )
        kpis.addView(
            statCard(
                ctx, accent, "PAYMENTS", "COLLECTED", money(stats.collected), "0%", textSec, "₹0 pending",
                listOf(
                    "CASH SALES" to stats.cashCount.toString(),
                    "CREDIT SALES" to stats.creditCount.toString(),
                    "UPI/ONLINE" to money(stats.upi),
                    "CARD" to money(stats.card)
                )
            ) { navigate(ReportsFragment()) }.weighted()
        )
        kpis.addView(
            statCard(
                ctx, accent, "CUSTOMERS", "SERVED TODAY", stats.customersToday.toString(), "0%", textSec, "${stats.repeatToday} repeat",
                listOf("REPEAT" to stats.repeatToday.toString(), "NEW MEMBERS" to stats.newMembers.toString())
            ) { navigate(CustomerFragment()) }.weighted()
        )
        content.addView(kpis)

        // Inventory (full width). Attention count = low + out of stock.
        val attention = stats.lowStock + stats.outOfStock
        content.addView(
            statCard(
                ctx, accent, "INVENTORY", "NEEDS ATTENTION", attention.toString(), "$attention items",
                if (attention > 0) red else textSec, "of ${stats.totalSkus} SKUs",
                listOf("LOW STOCK" to stats.lowStock.toString(), "OUT OF STOCK" to stats.outOfStock.toString())
            ) { navigate(InventoryFragment()) }.apply { fullWidth() }
        )

        // Industry widgets section.
        content.addView(industryHeader(ctx))
        val industry = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        industry.id = View.generateViewId()
        content.addView(industry)
        fillIndustry(ctx, industry)
    }

    private fun fillIndustry(ctx: Context, into: LinearLayout) {
        into.removeAllViews()
        // Industry widgets have no live data source yet -> everything shows 0.
        if (restaurant) {
            val r = row(ctx)
            r.addView(industryCard(ctx, accent, "TABLES", "FLOOR", "0", "occupied",
                listOf("Avg turn time" to "0", "Waitlist" to "0", "Reserved" to "0")
            ) { navigate(ComingSoonFragment.newInstance("Tables")) }.weighted())
            r.addView(industryCard(ctx, accent, "KITCHEN", "LIVE", "0", "orders in queue",
                listOf("Avg prep time" to "0", "Delayed" to "0", "Stations busy" to "0")
            ) { navigate(ComingSoonFragment.newInstance("Kitchen")) }.weighted())
            into.addView(r)
            into.addView(industryCard(ctx, accent, "KOT STATUS", "TICKETS", "0", "pending",
                listOf("Preparing" to "0", "Ready to serve" to "0", "Served today" to "0")
            ) { navigate(ComingSoonFragment.newInstance("KOT Status")) }.apply { fullWidth() })
        } else {
            val r = row(ctx)
            r.addView(industryCard(ctx, accent, "COUNTERS", "BILLING", "0", "lanes open",
                listOf("Avg wait" to "0", "Queues" to "0", "Idle lanes" to "0")
            ) { navigate(ComingSoonFragment.newInstance("Counters")) }.weighted())
            r.addView(industryCard(ctx, accent, "PURCHASE", "ORDERS", "0", "pending",
                listOf("Received today" to "0", "To approve" to "0", "Suppliers" to "0")
            ) { navigate(ComingSoonFragment.newInstance("Purchase")) }.weighted())
            into.addView(r)
            into.addView(industryCard(ctx, accent, "TOP CATEGORIES", "TODAY", "—", "leads sales",
                listOf("—" to "₹0", "—" to "₹0", "—" to "₹0")
            ) { navigate(ComingSoonFragment.newInstance("Top Categories")) }.apply { fullWidth() })
        }
    }

    // ---- Building blocks ---------------------------------------------------

    private fun snapshotHeader(ctx: Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        box.addView(label(ctx, "SNAPSHOT · LIVE", 11f, textSec, bold = true, spacing = 0.08f))
        val today = java.text.SimpleDateFormat("d MMMM", Locale.ENGLISH).format(java.util.Date())
        val titleRow = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text(ctx, "Today, $today", 24f, textMain, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(text(ctx, "REFRESHED JUST NOW", 10.5f, textSec))
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(6) }
            background = GradientDrawable().apply { setColor(red); cornerRadius = dp(2f) }
        }
        titleRow.addView(dot)
        box.addView(titleRow)
        return box
    }

    private fun industryHeader(ctx: Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(4))
        }
        box.addView(label(ctx, "INDUSTRY WIDGETS", 11f, textSec, bold = true, spacing = 0.08f))
        val r = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = text(ctx, if (restaurant) "Restaurant floor & kitchen" else "Store & stock", 19f, textMain, bold = true)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r.addView(title)
        r.addView(industryToggle(ctx, title))
        box.addView(r)
        return box
    }

    /**
     * RESTAURANT | GROCERY indicator. Locked to the app Mode: the active industry
     * is highlighted, the other is disabled (greyed, not clickable) so it can't be
     * switched to from the dashboard.
     */
    private fun industryToggle(ctx: Context, @Suppress("UNUSED_PARAMETER") title: TextView): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(Color.parseColor("#E4EAEA")) }
        }
        val disabled = Color.parseColor("#B0B6B6")
        fun pill() = GradientDrawable().apply { cornerRadius = dp(16f); setColor(accent) }

        val segRest = seg(ctx, "RESTAURANT")
        val segGroc = seg(ctx, "GROCERY")

        // Active segment = the app Mode; the other is a disabled, no-op label.
        segRest.background = if (restaurant) pill() else null
        segGroc.background = if (restaurant) null else pill()
        segRest.setTextColor(if (restaurant) Color.WHITE else disabled)
        segGroc.setTextColor(if (restaurant) disabled else Color.WHITE)
        segRest.isEnabled = restaurant
        segGroc.isEnabled = !restaurant
        segRest.isClickable = false
        segGroc.isClickable = false

        bar.addView(segRest)
        bar.addView(segGroc)
        return bar
    }

    private fun seg(ctx: Context, t: String): TextView = TextView(ctx).apply {
        text = t
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
    }

    /** A KPI/summary card: accent bar, label, big value, delta, a stat box, MORE. */
    private fun statCard(
        ctx: Context, accent: Int, label: String, topRight: String, value: String,
        delta: String, deltaColor: Int, deltaSub: String,
        stats: List<Pair<String, String>>, onMore: () -> Unit
    ): MaterialCardView {
        val card = card(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(accentBar(ctx, accent))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14)) }

        val head = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(ctx, label, 11f, accent, bold = true, spacing = 0.05f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(text(ctx, topRight, 10f, textSec))
        body.addView(head)

        body.addView(text(ctx, value, 24f, textMain, bold = true).apply {
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )).also { it.topMargin = dp(6); layoutParams = it }
        })

        val deltaRow = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, 0) }
        deltaRow.addView(text(ctx, delta, 13f, deltaColor, bold = true))
        deltaRow.addView(text(ctx, deltaSub, 12f, textSec).apply {
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )).also { it.marginStart = dp(6); layoutParams = it }
        })
        body.addView(deltaRow)

        body.addView(statBox(ctx, stats))
        // MORE button hidden for now.
        // body.addView(moreButton(ctx, onMore))
        col.addView(body)
        card.addView(col)
        return card
    }

    /** An industry widget card: accent bar, label, big value + caption, rows, MORE. */
    private fun industryCard(
        ctx: Context, accent: Int, label: String, topRight: String, big: String, bigSub: String,
        rows: List<Pair<String, String>>, onMore: () -> Unit
    ): MaterialCardView {
        val card = card(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(accentBar(ctx, accent))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14)) }

        val head = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(ctx, label, 11f, accent, bold = true, spacing = 0.05f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(text(ctx, topRight, 10f, textSec))
        body.addView(head)

        val bigRow = row(ctx).apply { gravity = Gravity.BOTTOM; setPadding(0, dp(4), 0, dp(8)) }
        bigRow.addView(text(ctx, big, 22f, textMain, bold = true))
        bigRow.addView(text(ctx, bigSub, 12f, textSec).apply {
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )).also { it.marginStart = dp(6); it.bottomMargin = dp(3); layoutParams = it }
        })
        body.addView(bigRow)
        body.addView(divider(ctx))

        for ((k, v) in rows) {
            val rr = row(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
            rr.addView(text(ctx, k, 14f, textMain).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            rr.addView(text(ctx, v, 14f, textMain, bold = true))
            body.addView(rr)
        }
        // MORE button hidden for now.
        // body.addView(moreButton(ctx, onMore))
        col.addView(body)
        card.addView(col)
        return card
    }

    private fun statBox(ctx: Context, stats: List<Pair<String, String>>): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f); setColor(Color.parseColor("#F7F8FA"))
                setStroke(dp(1), hairline)
            }
            (LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                .also { it.topMargin = dp(12); layoutParams = it }
        }
        stats.forEachIndexed { i, (k, v) ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) setPadding(dp(10), 0, 0, 0)
            }
            cell.addView(label(ctx, k, 10f, textSec, bold = false, spacing = 0.04f))
            cell.addView(text(ctx, v, 15f, textMain, bold = true))
            box.addView(cell)
        }
        return box
    }

    private fun moreButton(ctx: Context, onClick: () -> Unit): View = TextView(ctx).apply {
        text = "MORE →"
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(textMain)
        setPadding(0, dp(10), 0, dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(8f); setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor("#DADCE0"))
        }
        (LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            .also { it.topMargin = dp(12); layoutParams = it }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun accentBar(ctx: Context, color: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        setBackgroundColor(color)
    }

    private fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(hairline)
    }

    private fun card(ctx: Context): MaterialCardView = MaterialCardView(ctx).apply {
        radius = dp(12f)
        cardElevation = dp(1f)
        setCardBackgroundColor(Color.WHITE)
        strokeWidth = dp(1)
        setStrokeColor(hairline)
    }

    private fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) }
        isBaselineAligned = false
    }

    private fun text(ctx: Context, t: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t; textSize = sizeSp; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun label(ctx: Context, t: String, sizeSp: Float, color: Int, bold: Boolean, spacing: Float): TextView =
        text(ctx, t, sizeSp, color, bold).apply { letterSpacing = spacing }

    // ---- Card sizing helpers ----------------------------------------------

    private fun MaterialCardView.weighted(): MaterialCardView = apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.setMargins(dp(4), 0, dp(4), 0) }
    }

    private fun MaterialCardView.fullWidth() {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(10) }
    }

    private fun navigate(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
