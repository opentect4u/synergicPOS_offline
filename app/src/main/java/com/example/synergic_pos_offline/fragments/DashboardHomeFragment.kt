package com.example.synergic_pos_offline.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.RenewalStatus
import com.example.synergic_pos_offline.database.DashboardDao
import com.example.synergic_pos_offline.utils.StockAlerts
import com.example.synergic_pos_offline.utils.ThemeManager
import org.json.JSONObject

/**
 * The dashboard: today's takings, how they were paid, what sold, who sold it.
 *
 * ## Why this one screen is a web page
 *
 * The design arrived as HTML and Chart.js, and is meant to be adjusted - moving a card
 * or restyling a chart is a stylesheet edit here rather than a rebuild of a view tree.
 * Five charts also want a charting library, and the page brings its own: Chart.js sits
 * in `assets/dashboard/` and is loaded from the APK, so this draws with no network at
 * all, which is the only kind of screen a till may have.
 *
 * It is the one screen built this way, deliberately. Everything an operator uses to
 * *work* - the sale, the bill, the settings - stays native, because those have to be
 * fast and to feel like the app. A dashboard is read, not worked, and its whole job is
 * to look like the picture somebody drew of it.
 *
 * ## Where the figures come from
 *
 * [DashboardDao], in one pass, so the cards and the charts under them are one reading
 * of the books rather than nine. The page never queries and never navigates - it asks
 * [Bridge], which is the only thing it can reach.
 */
class DashboardHomeFragment : Fragment() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout

    /** True once the page has loaded and will accept data. */
    private var pageReady = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard_home, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipe = view.findViewById(R.id.swipeRefresh)
        web = view.findViewById(R.id.webDashboard)

        swipe.setColorSchemeColors(ThemeManager.getThemeColor(requireContext()))
        swipe.setOnRefreshListener { refresh() }

        // Scripts, because the charts are drawn by one. Nothing else is granted:
        // no file access beyond the assets the page was loaded from, and no network -
        // see the client below, which refuses to leave the APK.
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.setBackgroundColor(Color.TRANSPARENT)
        web.addJavascriptInterface(Bridge(), "POS")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                refresh()
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
            // Value (₹) of sales by bill type (cash / credit) today.
            fun billAmount(type: String) =
                num("SELECT COALESCE(SUM(net_amount),0) FROM td_bills WHERE $billOk AND bill_type = '$type'")
            val stockOf = "COALESCE((SELECT SUM(current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s WHERE s.product_id = p.id),0)"
            // Nothing is "low" without a quantity that says so, and Stock Alert is the
            // switch that supplies one - the same gate [StockAlerts] and [StockDao] use.
            val settings = GeneralSettingsDao(requireContext()).load()
            val alertQty = if (settings.stockFlag && settings.stockAlert) settings.stockAlertQty else 0
            Stats(
                salesToday = sales,
                billsToday = bills,
                avgBill = if (bills > 0) sales / bills else 0.0,
                collected = pay(null),
                // UPI and online collections read together — online payments settle via UPI rails.
                upi = pay("UPI") + pay("ONLINE"),
                card = pay("CARD"),
                cashAmount = billAmount("CASH"),
                creditAmount = billAmount("CREDIT"),
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
                // Measured against General Settings' Alert Quantity, which is the rule
                // [StockDao.levels] applies to the badges on the sale screen and
                // [StockAlerts] applies to the header count. This card used to read
                // md_products.stock_alert_qty instead - a column nothing on the product
                // form can set - so the dashboard could report five items low while
                // the grid showed four badges, with nothing on either screen to say
                // which was right.
                lowStock = if (alertQty <= 0) 0 else num(
                    "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p " +
                        "WHERE $stockOf > 0 AND $stockOf <= $alertQty"
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
        // Read here rather than once on create, so every rebuild (pull-to-refresh,
        // theme change, coming back from settings) picks the flag up.
        val stockOn = GeneralSettingsDao.isStockEnabled(ctx)
        content.removeAllViews()

        // Above everything, including the stock alerts: a registration about to run out
        // stops the whole till, not one line of one order, and it is the one thing here
        // that cannot be dealt with at the counter - somebody has to be called.
        renewalAlert(ctx)?.let { content.addView(it) }

        // Then what is out or running low: the one thing on this dashboard that is not
        // a figure to read but a job to do, and it is worth less the further down it sits.
        if (stockOn) content.addView(stockAlertPanel(ctx))

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
                    "CASH SALES" to money(stats.cashAmount),
                    "CREDIT SALES" to money(stats.creditAmount),
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

        // Inventory (full width). Attention count = low + out of stock. Only while
        // stock tracking is on - off, there is no quantity on hand to report against.
        if (stockOn) {
            val attention = stats.lowStock + stats.outOfStock
            content.addView(
                statCard(
                    ctx, accent, "INVENTORY", "NEEDS ATTENTION", attention.toString(), "$attention items",
                    if (attention > 0) red else textSec, "of ${stats.totalSkus} SKUs",
                    listOf("LOW STOCK" to stats.lowStock.toString(), "OUT OF STOCK" to stats.outOfStock.toString()),
                    onMore = { navigate(InventoryFragment()) },
                    // The counts open the list of what they are counting. A figure on a
                    // dashboard says there is a problem; the names say whether to deal
                    // with it now, and that is the whole reason to look.
                    onStat = { which -> showStockList(low = which == "LOW STOCK") }
                ).apply { fullWidth() }
            )
        }

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
             * Nothing leaves the APK.
             *
             * The page has no links and no remote anything, so a request for another
             * URL means something has gone wrong - a stray tap on some text a browser
             * decided was a link, or an asset that should have been bundled and was
             * not. Either way a till must not sit waiting on a network it may not have.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean = !(request?.url?.toString()?.startsWith(ASSET_BASE) ?: false)
        }
        web.loadUrl(ASSET_BASE + "index.html")
    }

    /** Re-reads the figures and hands them to the page. */
    fun refresh() {
        if (!isAdded || !pageReady) return
        val context = requireContext().applicationContext
        val accent = String.format("#%06X", 0xFFFFFF and ThemeManager.getThemeColor(requireContext()))
        Thread {
            val data = runCatching {
                DashboardDao(context).snapshot().put("accent", accent)
            }.getOrElse { JSONObject().put("accent", accent) }
            web.post {
                if (!isAdded) return@post
                // Passed as a JSON string literal rather than spliced into the call,
                // so a product named with an apostrophe cannot end the argument early.
                web.evaluateJavascript("render(${JSONObject.quote(data.toString())});", null)
                swipe.isRefreshing = false
            }
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

    /**
     * The renewal warning, or null while the date is far enough off to say nothing.
     *
     * Shown from a month before the registration runs out (see [RenewalStatus]), and
     * kept up once it has passed - an expired till is not less urgent than one about to
     * expire. Amber while there is still time, red once there is not, so the two read
     * apart at a glance rather than needing the sentence read.
     */
    private fun renewalAlert(ctx: Context): View? {
        val status = RenewalStatus.of(ctx)?.takeIf { it.needsAttention } ?: return null
        val urgent = status.expired || status.daysLeft <= 7
        val tone = if (urgent) 0xFFDC2626.toInt() else 0xFFD97706.toInt()
        val wash = if (urgent) 0xFFFDECEC.toInt() else 0xFFFEF6E0.toInt()

        val card = card(ctx).apply {
            setCardBackgroundColor(wash)
            setStrokeColor(tone)
            fullWidth()
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isBaselineAligned = false
        }
        body.addView(android.widget.ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                .also { it.topMargin = dp(2); it.marginEnd = dp(12) }
            setImageResource(android.R.drawable.ic_dialog_alert)
            imageTintList = android.content.res.ColorStateList.valueOf(tone)
        })
        body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(ctx, RenewalStatus.headline(status), 15f, tone, bold = true))
            addView(
                text(ctx, RenewalStatus.detail(status), 12.5f, textSec).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(2) }
                }
            )
        })
        card.addView(body)
        return card
    }

    private fun accentBar(ctx: Context, color: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        setBackgroundColor(color)
    }

    private fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(hairline)
        }.start()
    }

    /** Re-reads the theme accent and redraws (called on a palette change). */
    fun refreshTheme() = refresh()

    override fun onResume() {
        super.onResume()
        // Coming back from a sale, or from Stock In: the figures have moved.
        refresh()
    }

    /**
     * The only thing the page can reach.
     *
     * Deliberately four small methods rather than anything general: a bridge that
     * could open an arbitrary screen, or run an arbitrary query, would make the page's
     * JavaScript as trusted as the app, and it is the one part of this that is not
     * compiled. Everything here is a fixed choice from a fixed list.
     *
     * Every method lands on a WebView thread, so each hops back to the main one.
     */
    private inner class Bridge {

        @JavascriptInterface
        fun open(target: String) {
            web.post {
                if (!isAdded) return@post
                when (target) {
                    "bills" -> navigate(BillListFragment())
                    "reports" -> navigate(ReportsFragment())
                    "customers" -> navigate(CustomerFragment())
                    "lowstock" -> navigate(LowStockReportFragment())
                    "inventory" -> navigate(InventoryFragment())
                }
            }
        }

        @JavascriptInterface
        fun dismissAlert(productId: Int) {
            val context = requireContext().applicationContext
            Thread {
                // Re-read to find the item, so what is dismissed is dismissed against
                // the count it actually has now - see [StockAlerts.dismiss].
                StockAlerts.find(context).items
                    .firstOrNull { it.id == productId.toLong() }
                    ?.let { StockAlerts.dismiss(context, it) }
                web.post { refresh() }
            }.start()
        }
    }

    private fun navigate(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private companion object {
        /** Where the page and its charting library live inside the APK. */
        const val ASSET_BASE = "file:///android_asset/dashboard/"
    }
}
