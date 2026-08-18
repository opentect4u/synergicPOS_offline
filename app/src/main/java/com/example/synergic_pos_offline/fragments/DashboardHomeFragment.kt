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
