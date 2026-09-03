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
 * DashboardHomeFragment: Renders a WebView-based dashboard using Chart.js.
 */
class DashboardHomeFragment : Fragment() {

    private var webView: WebView? = null
    private var swipeLayout: SwipeRefreshLayout? = null
    private var isPageLoaded: Boolean = false

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
            override fun onPageFinished(view: WebView?, url: String?) {
                isPageLoaded = true
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
        if (!isAdded || !isPageLoaded) return
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
                    if (isAdded) {
                        w.evaluateJavascript("render(" + JSONObject.quote(jsonPayload) + ");", null)
                        swipeLayout?.isRefreshing = false
                    }
                }
            } catch (e: Exception) {
                w.post {
                    if (isAdded) swipeLayout?.isRefreshing = false
                }
            }
        }.start()
    }

    private fun navigate(target: String) {
        if (!isAdded) return
        val fragment: Fragment? = when (target) {
            "bills" -> BillListFragment()
            "reports" -> ReportsFragment()
            "customers" -> CustomerFragment()
            "lowstock" -> LowStockReportFragment()
            "inventory" -> InventoryFragment()
            else -> null
        }
        
        if (fragment != null) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
        }
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
