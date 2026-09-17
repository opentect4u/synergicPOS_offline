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
 * Dashboard fragment using a WebView to display Chart.js visualizations.
 */
class DashboardHomeFragment : Fragment() {

    var webView: WebView? = null
    var swipeLayout: SwipeRefreshLayout? = null
    var pageLoaded: Boolean = false

    companion object {
        private const val ASSET_BASE = "file:///android_asset/dashboard/"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard_home, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val w = view.findViewById<WebView>(R.id.webDashboard)
        val s = view.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        
        webView = w
        swipeLayout = s
        
        s.setColorSchemeColors(ThemeManager.getThemeColor(requireContext()))
        s.setOnRefreshListener { refresh() }
        
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = true
        w.setBackgroundColor(Color.TRANSPARENT)
        
        w.addJavascriptInterface(Bridge(), "POS")
        
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                pageLoaded = true
                refresh()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, 
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return !url.startsWith(ASSET_BASE)
            }
        }
        
        w.loadUrl("${ASSET_BASE}index.html")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refreshTheme() {
        refresh()
    }

    fun refresh() {
        if (!isAdded || !pageLoaded) return
        val w = webView ?: return

        val context = requireContext().applicationContext
        val colorInt = ThemeManager.getThemeColor(requireContext())
        val accentStr = String.format("#%06X", 0xFFFFFF and colorInt)

        Thread {
            try {
                val data = DashboardDao(context).snapshot()
                data.put("accent", accentStr)
                val jsonPayload = data.toString()

                w.post {
                    // webView is nulled in onDestroyView, so this also covers the case
                    // where the view went away while the snapshot was being read - the
                    // fragment can still be "added" with its view already destroyed.
                    if (isAdded && webView != null) {
                        w.evaluateJavascript("render(" + JSONObject.quote(jsonPayload) + ");", null)
                        swipeLayout?.isRefreshing = false
                    }
                }
            } catch (e: Exception) {
                // Logged, not swallowed. The dashboard reads a dozen figures out of the
                // books; one bad query left the spinner stopping with the old numbers
                // still on screen and nothing anywhere to say the screen was stale.
                android.util.Log.e("DashboardHome", "Could not build the dashboard snapshot", e)
                w.post {
                    if (isAdded) swipeLayout?.isRefreshing = false
                }
            }
        }.start()
    }

    /**
     * Lets the WebView go when the screen does.
     *
     * A WebView holds its JavascriptInterface for as long as it lives, and [Bridge] is
     * an inner class - it holds this fragment, which holds the WebView. Left alone that
     * is a cycle rooted in a view that has been destroyed, so every visit to the
     * dashboard leaked the one before it along with its whole view tree.
     *
     * The JavaScript interface is removed before the WebView is destroyed so that a
     * callback already in flight cannot land on a fragment whose view has gone.
     */
    override fun onDestroyView() {
        webView?.let { w ->
            runCatching { w.removeJavascriptInterface("POS") }
            w.stopLoading()
            w.webViewClient = WebViewClient()
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        webView = null
        swipeLayout = null
        pageLoaded = false
        super.onDestroyView()
    }

    /**
     * Opens the screen a dashboard card stands for.
     *
     * ## Why this uses the ACTIVITY's fragment manager
     *
     * This fragment is a CHILD of [DashboardFragment], added to that screen's own
     * `dashboardTabContainer` through its `childFragmentManager`. So
     * `parentFragmentManager` here is not the activity's - it is the dashboard's child
     * manager, and the only container it can see is the one inside the dashboard's own
     * view.
     *
     * `R.id.fragment_container` is in activity_main, an ANCESTOR of that view. Asking
     * the child manager to replace it threw `IllegalArgumentException: No view found
     * for id ... fragment_container`, which is why every card on the dashboard crashed
     * the app the moment it was tapped rather than opening anything.
     *
     * The activity's manager is also the right one on its own merits: these cards lead
     * to whole screens - Bill History, Reports - which replace the dashboard rather
     * than opening inside it, and they go on the same back stack the drawer's own
     * destinations use, so Back returns here exactly as it does from a drawer route.
     */
    private fun navigate(target: String) {
        if (!isAdded) return
        val fragment: Fragment = when (target) {
            "bills" -> BillListFragment()
            "reports" -> ReportsFragment()
            "customers" -> CustomerFragment()
            "lowstock" -> LowStockReportFragment()
            "inventory" -> InventoryFragment()
            // A card naming a screen this build does not have. Ignored rather than
            // crashed on - the page is an asset and can name a target the app has not
            // caught up with.
            else -> return
        }

        // commitAllowingStateLoss: this arrives from a WebView callback, which can land
        // after the activity has been backgrounded (the operator taps a card and the
        // screen locks). A plain commit throws there; losing this one navigation does
        // not matter, because the dashboard is still what they come back to.
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    /**
     * Bridge class to allow JavaScript in the WebView to call native app methods.
     */
    private inner class Bridge {
        
        @JavascriptInterface
        fun open(target: String) {
            webView?.post {
                navigate(target)
            }
        }

        @JavascriptInterface
        fun dismissAlert(productId: Int) {
            val ctx = context?.applicationContext ?: return
            Thread {
                val alerts = StockAlerts.find(ctx).items
                val item = alerts.firstOrNull { it.id == productId.toLong() }
                if (item != null) {
                    StockAlerts.dismiss(ctx, item)
                }
                webView?.post {
                    if (isAdded) refresh()
                }
            }.start()
        }
    }
}
