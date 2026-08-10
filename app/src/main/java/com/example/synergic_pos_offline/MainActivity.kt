package com.example.synergic_pos_offline

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.fragments.*
import com.example.synergic_pos_offline.utils.*

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            android.util.Log.e("SynergicPOS", "MainActivity CLASS LOADED")
        }

        /**
         * A short wait before the first automatic-backup check, so a launch is not
         * competing with the seeder and the first screen for the database.
         */
        private const val AUTO_BACKUP_FIRST_CHECK_MS = 30_000L

        /**
         * How often the question is asked after that. Well under the shortest
         * interval that can be set, so an hourly backup lands within a few minutes
         * of its hour rather than up to an hour late.
         */
        private const val AUTO_BACKUP_CHECK_MS = 5 * 60_000L
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvSidebar: RecyclerView
    private lateinit var tvSidebarUser: TextView
    private lateinit var sidebarHeader: View

    // Global header
    private lateinit var headerBar: View
    private lateinit var btnBack: ImageButton
    private lateinit var tvHeaderTitle: TextView
    private lateinit var tvHeaderSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.e("SynergicPOS", "!!! MainActivity onCreate START !!!")
        // The UI is designed light-only (hardcoded white backgrounds). Force day
        // mode so uncolored EditText input text stays dark and remains visible.
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // The APK ships with the master table *structure* only — no demo/master
        // data is bundled. Tables are created empty by DatabaseHelper.onCreate and
        // filled by registration/login sync and hand entry, so nothing is seeded here.

        // Automatic backup, if it is switched on. Started here and stopped in
        // onDestroy, so it runs for as long as the till is open and not a moment
        // after - see [AutoBackup] for what that does and does not cover.
        startAutoBackupWatch()

        drawerLayout = findViewById(R.id.drawerLayout)
        rvSidebar = findViewById(R.id.rvSidebar)
        tvSidebarUser = findViewById(R.id.tvSidebarUser)
        sidebarHeader = findViewById(R.id.sidebarHeader)

        headerBar = findViewById(R.id.headerBar)
        btnBack = findViewById(R.id.btnBack)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        tvHeaderSubtitle = findViewById(R.id.tvHeaderSubtitle)

        // Global header actions
        findViewById<View>(R.id.btnMenu).setOnClickListener { openDrawer() }
        btnBack.setOnClickListener { supportFragmentManager.popBackStack() }
        findViewById<View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnTheme).setOnClickListener { showThemePopup(it) }
        findViewById<View>(R.id.btnLogout).setOnClickListener { confirmLogout() }

        rvSidebar.layoutManager = LinearLayoutManager(this)
        rvSidebar.adapter = SidebarAdapter(buildMenuTree()) { leafTitle -> handleLeaf(leafTitle) }

        applyThemeEverywhere()

        // The drawer/header are only available once the user is logged in.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    if (f is LoginFragment || f is RegistrationFragment) {
                        headerBar.visibility = View.GONE
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            drawerLayout.closeDrawer(GravityCompat.START, false)
                        }
                    } else {
                        headerBar.visibility = View.VISIBLE
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                        updateHeader(f)
                        applyThemeEverywhere()
                    }
                }
            }, false
        )

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, LoginFragment())
                .commit()
        }
        android.util.Log.e("SynergicPOS", "!!! MainActivity onCreate FINISHED !!!")
    }

    // ---- Automatic backup ---------------------------------------------------

    private val autoBackupHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Asks [AutoBackup] whether a backup is due, now and every [AUTO_BACKUP_CHECK_MS]
     * after.
     *
     * A check rather than a timer set to the interval: the app is opened and closed
     * through the day, and a timer started at launch would only ever fire for a till
     * left running for the whole gap. Asking often and letting the elapsed time
     * decide catches up a backup missed while the app was shut, on the next launch.
     *
     * The check itself is a single settings read on the main thread; only a backup
     * that is actually due goes to the worker.
     */
    private fun startAutoBackupWatch() {
        val tick = object : Runnable {
            override fun run() {
                Thread {
                    val outcome = runCatching { AutoBackup.runIfDue(applicationContext) }
                        .getOrElse { AutoBackup.Outcome(taken = false, error = it.message) }
                    if (outcome.taken) {
                        android.util.Log.i("AutoBackup", "backed up to ${outcome.savedTo}")
                    } else outcome.error?.let {
                        android.util.Log.e("AutoBackup", "automatic backup failed: $it")
                    }
                }.start()
                autoBackupHandler.postDelayed(this, AUTO_BACKUP_CHECK_MS)
            }
        }
        autoBackupHandler.postDelayed(tick, AUTO_BACKUP_FIRST_CHECK_MS)
    }

    override fun onDestroy() {
        autoBackupHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Updates the global header title/subtitle and back-button for [f]. */
    private fun updateHeader(f: Fragment) {
        tvHeaderTitle.text = titleFor(f)
        val user = SessionManager.currentUser
        tvHeaderSubtitle.text = "Hello, ${user?.userId ?: "User"}"
        // Back is hidden on whatever screen is the root - the Sale screen after login,
        // or the Dashboard after the home button - and shown on anything pushed over
        // it. Decided from the back stack rather than by naming screens, so a screen
        // that is the root in one place and a sub-page in another (Sale is both) gets
        // the right answer either way.
        btnBack.visibility =
            if (supportFragmentManager.backStackEntryCount == 0) View.GONE else View.VISIBLE
    }

    private fun titleFor(f: Fragment): String = when (f) {
        is com.example.synergic_pos_offline.fragments.TitledScreen -> f.screenTitle
        is com.example.synergic_pos_offline.fragments.MenuFragment -> "Dashboard"
        is MasterFragment -> "Master"
        is SettingsFragment -> "Settings"
        is InventoryFragment -> "Stock & Inventory"
        is ReportsFragment -> "Reports"
        is SalesFragment -> "Sales"
        is ItemwiseSearchFragment -> "Item Search"
        is HeaderFooterFragment -> "Header & Footer"
        is DatabaseSettingsFragment -> "Database Settings"
        else -> "Synergic POS"
    }

    private fun confirmLogout() {
        DialogUtils.showConfirm(
            context = this,
            title = "Logout",
            message = "Are you sure you want to log out of Synergic POS?",
            positiveText = "Logout",
            negativeText = "Cancel",
            iconRes = android.R.drawable.ic_lock_power_off,
            destructive = true
        ) { logout() }
    }

    private fun logout() {
        SessionManager.logout()
        // Clear the whole back stack and put the login form back up. Shown outright
        // rather than popped back to: the screen logged into is the root of the stack,
        // so there is nothing underneath to pop to.
        val fm = supportFragmentManager
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.beginTransaction()
            .replace(R.id.fragment_container, LoginFragment())
            .commit()
    }

    // ---- Drawer control, called from any fragment's hamburger button --------

    fun openDrawer() {
        val user = SessionManager.currentUser
        tvSidebarUser.text = if (user != null) "Active User: ${user.userId}" else "Main Menu"
        
        // Refresh the menu tree in case the app mode (Grocery/Restaurant) changed.
        rvSidebar.adapter = SidebarAdapter(buildMenuTree()) { leafTitle -> handleLeaf(leafTitle) }

        refreshSidebarTheme()
        drawerLayout.openDrawer(GravityCompat.START)
    }

    /**
     * Called when the user picks a color from the palette. Persists the choice
     * and immediately re-themes the whole app in real time.
     */
    fun onThemeColorSelected(colorHex: String) {
        ThemeManager.setThemeColor(this, colorHex)
        applyThemeEverywhere()
        // Some screens use raw accent colors (not tracked by ThemeManager), so
        // notify them to recolor when the theme changes while they are showing.
        when (val f = supportFragmentManager.findFragmentById(R.id.fragment_container)) {
            is DashboardFragment -> f.onThemeChanged()
            is com.example.synergic_pos_offline.fragments.RestaurantOrdersFragment -> f.onThemeChanged()
            is com.example.synergic_pos_offline.fragments.RestaurantCheckoutFragment -> f.onThemeChanged()
            else -> {}
        }
    }

    /** Re-tints every currently inflated view + the status bar + the drawer. */
    @Suppress("DEPRECATION")
    fun applyThemeEverywhere() {
        val color = ThemeManager.getThemeColor(this)
        window.statusBarColor = color
        // Walk the entire live view hierarchy (current fragment + drawer + chrome).
        ThemeManager.applyTheme(window.decorView)
        tvHeaderTitle.setTextColor(color)
        refreshSidebarTheme()
    }

    /** Shows the theme-color dropdown anchored under the palette icon. */
    private fun showThemePopup(anchor: View) {
        val density = resources.displayMetrics.density

        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_theme_picker, null)
        val grid = popupView.findViewById<GridLayout>(R.id.glColors)

        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable → dismiss on outside tap
        )
        popup.elevation = 16f
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val selected = ThemeManager.getThemeColor(this)
        val cellSize = (52 * density).toInt()
        val dotSize = (40 * density).toInt()
        val checkSize = (22 * density).toInt()
        val margin = (6 * density).toInt()

        for (colorHex in ThemeManager.PALETTE) {
            val colorInt = Color.parseColor(colorHex)
            val isSelected = colorInt == selected

            val cell = FrameLayout(this)
            cell.layoutParams = GridLayout.LayoutParams().apply {
                width = cellSize
                height = cellSize
                setMargins(margin, margin, margin, margin)
            }
            cell.isClickable = true
            cell.isFocusable = true

            val dot = View(this)
            dot.layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorInt)
                setStroke((1 * density).toInt(), Color.parseColor("#22000000"))
            }
            cell.addView(dot)

            if (isSelected) {
                val check = ImageView(this)
                check.layoutParams = FrameLayout.LayoutParams(checkSize, checkSize, Gravity.CENTER)
                check.setImageResource(R.drawable.ic_check)
                check.imageTintList = ColorStateList.valueOf(Color.WHITE)
                cell.addView(check)
            }

            cell.setOnClickListener {
                onThemeColorSelected(colorHex)
                popup.dismiss()
                Toast.makeText(this, "Theme updated!", Toast.LENGTH_SHORT).show()
            }

            grid.addView(cell)
        }

        // Show dropdown anchored to the palette icon, aligned to its right edge.
        val xOffset = (0 * density).toInt()
        val yOffset = (8 * density).toInt()
        popup.showAsDropDown(anchor, xOffset, yOffset, Gravity.END)
    }

    private fun refreshSidebarTheme() {
        val color = ThemeManager.getThemeColor(this)
        sidebarHeader.setBackgroundColor(color)
        rvSidebar.adapter?.notifyDataSetChanged()
    }

    private fun closeDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun navigateTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Clears the back stack and shows the Dashboard as the single root screen. */
    private fun goHome() {
        closeDrawer()
        val fm = supportFragmentManager
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.beginTransaction()
            .replace(R.id.fragment_container, DashboardFragment())
            .commit()
    }

    private fun handleLeaf(title: String) {
        closeDrawer()
        when (title) {
            "Master" -> navigateTo(MasterFragment())
            "Settings" -> navigateTo(SettingsFragment())
            "General Settings" -> navigateTo(GeneralSettingsFragment())
            "Bill Settings" -> navigateTo(BillSettingsFragment())
            "Tax Settings" -> navigateTo(TaxSettingsFragment())
            "App Settings" -> navigateTo(AppSettingsFragment())
            // Opens on Connections; the Print Template tab is the other half of it.
            "Printer Settings" -> navigateTo(PrintSettingsFragment())
            "Stock & Inventory" -> navigateTo(InventoryFragment())
            "Stock In" -> navigateTo(StockListFragment.newInstance(StockListFragment.Mode.IN))
            "Write Off" -> navigateTo(StockListFragment.newInstance(StockListFragment.Mode.OUT))
            "Reports" -> navigateTo(ReportsFragment())
            // Serves both modes: a settled restaurant order is written to td_bills
            // by the same call a grocery sale is, so there is one report to open.
            "Bill Wise Report" -> navigateTo(BillWiseReportFragment())
            "Item Wise Report" -> navigateTo(ItemWiseReportFragment())
            "Stock Report" -> navigateTo(StockReportFragment())
            "Sale" -> navigateTo(
                if (SettingsCache.value(this, "G", "Mode") == "R")
                    com.example.synergic_pos_offline.fragments.RestaurantOrdersFragment()
                else PosBillingFragment()
            )
            "Advance Payment" -> navigateTo(AdvancePaymentFragment())
            "Customer Ledger" -> navigateTo(CustomerLedgerFragment())
            "Sale Return" -> {
                val screen = SaleReturnRouter.screenFor(this)
                if (screen == null) {
                    android.widget.Toast.makeText(
                        this, SaleReturnRouter.DISABLED_MESSAGE, android.widget.Toast.LENGTH_LONG
                    ).show()
                } else navigateTo(screen)
            }
            "Header & Footer" -> navigateTo(HeaderFooterFragment())
            "Captions" -> navigateTo(CaptionFragment())
            "User Management" -> navigateTo(UserManagementFragment())
            "Bill Header & Footer" -> navigateTo(BillHeaderFooterFragment())
            "Bill Header Footer Logo" -> navigateTo(BillLogoFragment())
            "Database Settings" -> navigateTo(DatabaseSettingsFragment())
            "Category/Department" -> navigateTo(CategoryDepartmentFragment())
            "Products" -> navigateTo(ProductsFragment())
            "Customers" -> navigateTo(CustomerFragment())
            "Description/Ledger" -> navigateTo(DescriptionLedgerFragment())
            "Units" -> navigateTo(UnitFragment())
            "Waiter" -> navigateTo(WaiterFragment())
            // Every other leaf routes to a clean placeholder page (no dead clicks).
            else -> navigateTo(ComingSoonFragment.newInstance(title))
        }
    }

    // ---- Menu tree ----------------------------------------------------------

    private fun buildMenuTree(): List<TreeNode> {
        val context = this
        val isGrocery = SettingsCache.value(context, "G", "Mode") == "G"
        // Sale Return and Advance Payment are grocery-only flows; hidden in Restaurant.
        val isRestaurant = SettingsCache.value(context, "G", "Mode") == "R"

        // Stock Report is dropped where stock is not tracked, the same way the tile
        // on the Reports screen is: with no count kept there is nothing to report.
        val reportTitles = listOf(
            "Bill Wise Report", "Item Wise Report", "Operator Wise Report", "Void Bill Report",
            "Tax Report", "Duplicate Bill Report", "Stock Report", "Item Bill Report",
            "Returned Bill Report", "UDF-Wise Report", "Payment-Wise Report", "Unsold Product Report",
            "Opr Bill Report", "Category/Dept Wise Bill Report", "Payment & Receipt", "Customer Payment",
            "Customer Ledger", "Profit & Loss Report", "KOT Cancel Report", "Day-Wise Report",
            "Month Wise Report", "Year Wise Report", "UDF Wise Item Report", "Customer Item Wise RPT",
            "Time Wise Item Report"
        ).filter { it != "Stock Report" || GeneralSettingsDao.isStockEnabled(context) }

        val databaseSettingsNodes = mutableListOf(
            TreeNode("Category/Department"),
            TreeNode("Products"),
            TreeNode("Customers"),
            TreeNode("Description/Ledger"),
            TreeNode("Units")
        )
        if (!isGrocery) {
            databaseSettingsNodes.add(TreeNode("Waiter"))
        }

        // Stock & Inventory only exists while stock tracking is on - the drawer has
        // to agree with the menu grid, or the tile is "hidden" in one place only.
        val stockNodes = if (GeneralSettingsDao.isStockEnabled(context)) listOf(
            // Matches the Stock & Inventory tile grid: only the two built screens are
            // listed, and the rest stay parked in [InventoryFragment] until they are.
            TreeNode("Stock & Inventory", listOf(
                TreeNode("Stock In"),
                TreeNode("Write Off")
            ))
        ) else emptyList()

        return listOf(
            TreeNode("Master", listOf(
                TreeNode("Captions"),
                TreeNode("Header & Footer", buildList {
                    // Bill header/footer + logo always; the KOT pair only in Restaurant.
                    add(TreeNode("Bill Header & Footer"))
                    if (isRestaurant) add(TreeNode("KOT Header & Footer"))
                    add(TreeNode("Bill Header Footer Logo"))
                    if (isRestaurant) add(TreeNode("KOT Header Footer Logo"))
                }),
                TreeNode("User Management"),
                TreeNode("Database Settings", databaseSettingsNodes)
            )),
            TreeNode("Settings", listOf(
                TreeNode("General Settings"),
                TreeNode("Bill Settings"),
                TreeNode("Tax Settings"),
               // TreeNode("Inventory & Stock Settings"),
                // Same position it holds in the Settings tile grid, so the two ways
                // in list the screens in one order.
                TreeNode("Printer Settings"),
                TreeNode("App Settings")
            )),
            *stockNodes.toTypedArray(),
            TreeNode("Sale"),
            // Sale Return + Advance Payment are grocery-only; omitted in Restaurant.
            *(if (isRestaurant) emptyArray<TreeNode>() else arrayOf(
                TreeNode("Sale Return"),
                TreeNode("Advance Payment")
            )),
            // TreeNode("Duplicate Bill"),
            // TreeNode("Delete Bill"),
            TreeNode("Reports", reportTitles.map { TreeNode(it) })
        )
    }

    class TreeNode(
        val title: String,
        val children: List<TreeNode> = emptyList()
    ) {
        var expanded = false
        val hasChildren: Boolean get() = children.isNotEmpty()
    }

    private data class VisibleNode(val node: TreeNode, val depth: Int)

    private inner class SidebarAdapter(
        private val roots: List<TreeNode>,
        private val onLeafClick: (String) -> Unit
    ) : RecyclerView.Adapter<SidebarAdapter.ViewHolder>() {

        private val visible = mutableListOf<VisibleNode>()
        private val indentPx = (16 * resources.displayMetrics.density).toInt()
        private val basePaddingPx = (16 * resources.displayMetrics.density).toInt()
        private val verticalPaddingPx = (10 * resources.displayMetrics.density).toInt()

        init {
            rebuildVisible()
        }

        private fun rebuildVisible() {
            visible.clear()
            fun add(nodes: List<TreeNode>, depth: Int) {
                for (n in nodes) {
                    visible.add(VisibleNode(n, depth))
                    if (n.expanded && n.hasChildren) add(n.children, depth + 1)
                }
            }
            add(roots, 0)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: LinearLayout = view.findViewById(R.id.llNodeRoot)
            val ivChevron: ImageView = view.findViewById(R.id.ivChevron)
            val tvTitle: TextView = view.findViewById(R.id.tvNodeTitle)

            init {
                root.setOnClickListener {
                    val pos = adapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val vn = visible[pos]
                    if (vn.node.hasChildren) {
                        vn.node.expanded = !vn.node.expanded
                        rebuildVisible()
                        notifyDataSetChanged()
                    } else {
                        onLeafClick(vn.node.title)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sidebar_node, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val vn = visible[position]
            holder.tvTitle.text = vn.node.title

            val themeColor = ThemeManager.getThemeColor(this@MainActivity)
            holder.ivChevron.imageTintList = ColorStateList.valueOf(themeColor)

            holder.root.setPaddingRelative(
                basePaddingPx + vn.depth * indentPx,
                verticalPaddingPx,
                basePaddingPx,
                verticalPaddingPx
            )

            if (vn.node.hasChildren) {
                holder.ivChevron.visibility = View.VISIBLE
                holder.ivChevron.rotation = if (vn.node.expanded) 90f else 0f
            } else {
                holder.ivChevron.visibility = View.INVISIBLE
            }

            holder.tvTitle.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (vn.depth == 0) R.color.text_main else R.color.text_secondary
                )
            )
        }

        override fun getItemCount() = visible.size
    }
}
