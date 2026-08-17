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
import com.example.synergic_pos_offline.database.StockDao
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

        /** Named once: the drawer lists this leaf by it and [handleLeaf] opens it by it. */
        private const val CHANGE_MODE = "Change Mode"

        /** Named once: the drawer lists this leaf by it and [handleLeaf] opens it by it. */
        private const val CALCULATOR = "Calculator"

        /** Anything out of stock, which cannot be sold at all. */
        private const val STOCK_OUT_COLOUR = "#D93025"

        /** Only running low - the same amber the sale screen's stock pill uses. */
        private const val STOCK_LOW_COLOUR = "#F9AB00"
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvSidebar: RecyclerView
    private lateinit var tvSidebarUser: TextView
    private lateinit var sidebarHeader: View

    // Global header
    private lateinit var headerBar: View
    private lateinit var btnBack: ImageButton
    private lateinit var tvHeaderTitle: TextView

    /** The stock badge in the header - see [refreshStockAlert]. */
    private lateinit var tvStockAlert: TextView
    private lateinit var tvHeaderSubtitle: TextView

    /** Title of the page currently open, used to highlight its sidebar item and keep
     *  its parent group expanded so the drawer reflects where the user is. */
    private var activeLeafTitle: String? = null

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
        tvStockAlert = findViewById(R.id.tvStockAlert)
        tvStockAlert.setOnClickListener { showStockAlerts() }

        // Global header actions
        findViewById<View>(R.id.btnMenu).setOnClickListener { openDrawer() }
        btnBack.setOnClickListener { supportFragmentManager.popBackStack() }
        findViewById<View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnTheme).setOnClickListener { showThemePopup(it) }
        findViewById<View>(R.id.btnLogout).setOnClickListener { confirmLogout() }

        rvSidebar.layoutManager = LinearLayoutManager(this)
        refreshSidebar()

        applyThemeEverywhere()

        // The drawer/header are only available once the user is logged in.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    if (f is LoginFragment || f is RegistrationFragment) {
                        headerBar.visibility = View.GONE
                        // Nobody is signed in, so there is no store to count and the
                        // badge must not survive a logout into the next login.
                        stockAlerts = StockAlerts.NONE
                        tvStockAlert.visibility = View.GONE
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            drawerLayout.closeDrawer(GravityCompat.START, false)
                        }
                    } else {
                        headerBar.visibility = View.VISIBLE
                        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                        updateHeader(f)
                        // Remember which page is open so the drawer can highlight its
                        // sidebar item (and expand its group) the next time it opens.
                        activeLeafTitle = titleFor(f)
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
        stockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- Low stock ----------------------------------------------------------

    /** What the last look at the stock found, so the badge and the list agree. */
    private var stockAlerts: StockAlerts.Summary = StockAlerts.NONE

    private val stockHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Whether a count is already running - see [refreshStockAlert]. Main thread only. */
    private var stockRefreshing = false

    /**
     * Re-counts what is out or running low and shows it in the header.
     *
     * Called on every screen change rather than once, because the count moves as the
     * till is used: a sale takes the last of something, a Stock In entry puts it back,
     * and a badge fixed at what login found would be wrong within the hour.
     *
     * Read on a worker thread - it is a query over the product master, and the screen
     * change that triggered it is not something to hold up. [after] runs on the main
     * thread once the count is in, for the caller that wants to do something with it.
     *
     * A count already running means this one can be dropped: navigating twice quickly
     * would otherwise start a thread per screen, and the second answer would be the
     * same as the first. [force] is for the caller that cannot be dropped - the login
     * announcement, which has to run even if the landing screen's own refresh got
     * there first, or the operator would never be told.
     */
    private fun refreshStockAlert(
        force: Boolean = false,
        after: (StockAlerts.Summary) -> Unit = {}
    ) {
        if (!StockAlerts.enabled(this)) {
            stockAlerts = StockAlerts.NONE
            tvStockAlert.visibility = View.GONE
            after(StockAlerts.NONE)
            return
        }
        if (stockRefreshing && !force) return
        stockRefreshing = true
        Thread {
            val found = StockAlerts.find(applicationContext)
            stockHandler.post {
                stockRefreshing = false
                if (isFinishing || isDestroyed) return@post
                stockAlerts = found
                showStockBadge(found)
                after(found)
            }
        }.start()
    }

    /**
     * Draws the badge, or takes it away when there is nothing to say.
     *
     * Coloured for the worse of the two states it is reporting: red while anything is
     * out of stock, amber when the shelf is only thinning. An operator reads the
     * colour before the number.
     */
    private fun showStockBadge(summary: StockAlerts.Summary) {
        if (summary.isEmpty) {
            tvStockAlert.visibility = View.GONE
            return
        }
        val colour = Color.parseColor(if (summary.out.isNotEmpty()) STOCK_OUT_COLOUR else STOCK_LOW_COLOUR)
        tvStockAlert.visibility = View.VISIBLE
        tvStockAlert.text = "⚠  ${summary.total}"
        tvStockAlert.contentDescription = summary.headline
        tvStockAlert.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10 * resources.displayMetrics.density
            setColor(colour)
        }
    }

    /**
     * Lists what needs attention, and offers the screen that does something about it.
     *
     * Named rather than counted. "7 items need attention" tells an operator to go and
     * look; the names tell them whether it is worth going now, and that is the whole
     * value of raising this at the start of a shift rather than when the shelf is
     * already empty.
     */
    private fun showStockAlerts() {
        StockAlerts.showList(
            context = this,
            title = "Stock needs attention",
            headline = stockAlerts.headline,
            items = stockAlerts.items
        ) { navigateTo(InventoryFragment()) }
    }

    /**
     * The one look at the stock that the operator did not ask for.
     *
     * Raised once, just after login, because that is when it can still be acted on -
     * before the shop opens rather than when a customer is at the counter. Everything
     * after that is the header badge, which waits to be tapped.
     */
    fun announceStockAlertsAfterLogin() {
        refreshStockAlert(force = true) { summary -> if (!summary.isEmpty) showStockAlerts() }
    }

    /** Updates the global header title/subtitle and back-button for [f]. */
    private fun updateHeader(f: Fragment) {
        tvHeaderTitle.text = titleFor(f)
        refreshStockAlert()
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

    /**
     * Switches what kind of till this is, and signs out so it takes effect.
     *
     * The mode decides the landing screen and the whole menu, both of which are built
     * once when a session starts - so the only honest way to apply a change is to end
     * the session. Signing back in opens the till in whichever mode was chosen.
     */
    private fun chooseMode() {
        val modes = GeneralSettingsDao.Mode.entries
        val current = GeneralSettingsDao(this).load().mode
        DialogUtils.showList(
            context = this,
            title = "Change mode",
            items = modes.map {
                DialogUtils.ListItem(it.label, trailing = if (it == current) "Current" else "")
            }
        ) { index ->
            val chosen = modes[index]
            if (chosen == current) return@showList
            DialogUtils.showConfirm(
                context = this,
                title = "Switch to ${chosen.label}?",
                message = "You will be signed out. Sign back in and the till opens in " +
                    "${chosen.label} mode.",
                positiveText = "Switch",
                destructive = true
            ) {
                val dao = GeneralSettingsDao(this)
                dao.save(dao.load().copy(mode = chosen))
                // The menus and the landing screen read the cache, not the table.
                SettingsCache.storeFromDb(this)
                logout()
            }
        }
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
        refreshSidebar()

        refreshSidebarTheme()
        drawerLayout.openDrawer(GravityCompat.START)
    }

    /**
     * Rebuilds the sidebar from the current menu tree, expanding the group that holds
     * the open page and telling the adapter which leaf to highlight, so the drawer
     * always reflects where the user is.
     */
    private fun refreshSidebar() {
        val tree = buildMenuTree()
        activeLeafTitle?.let { expandToActive(tree, it) }
        rvSidebar.adapter = SidebarAdapter(tree, activeLeafTitle) { leafTitle -> handleLeaf(leafTitle) }
    }

    /**
     * Expands every ancestor group of the leaf titled [active] so it is visible in the
     * drawer. Returns whether [active] was found under [nodes].
     */
    private fun expandToActive(nodes: List<TreeNode>, active: String): Boolean {
        var contains = false
        for (n in nodes) {
            val childHasIt = n.hasChildren && expandToActive(n.children, active)
            if (childHasIt) n.expanded = true
            if (n.title == active || childHasIt) contains = true
        }
        return contains
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
    /**
     * Back to the top.
     *
     * In Calculator mode the top *is* the calculator: there is no dashboard behind it
     * to go back to, and sending Home there would be the one tap that escaped a mode
     * whose whole point is that it has one screen.
     */
    private fun goHome() {
        closeDrawer()
        val home: Fragment =
            if (SettingsCache.value(this, "G", "Mode") == "C") CalculatorFragment()
            else DashboardFragment()
        val fm = supportFragmentManager
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.beginTransaction()
            .replace(R.id.fragment_container, home)
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
            "About App" -> navigateTo(AboutAppFragment())
            // Opens on Connections; the Print Template tab is the other half of it.
            "Printer Settings" -> navigateTo(PrintSettingsFragment())
            CALCULATOR -> navigateTo(CalculatorFragment())
            CHANGE_MODE -> chooseMode()
            "Stock & Inventory" -> navigateTo(InventoryFragment())
            "Stock In" -> navigateTo(StockListFragment.newInstance(StockListFragment.Mode.IN))
            "Write Off" -> navigateTo(StockListFragment.newInstance(StockListFragment.Mode.OUT))
            "Reports" -> navigateTo(ReportsFragment())
            // Serves both modes: a settled restaurant order is written to td_bills
            // by the same call a grocery sale is, so there is one report to open.
            "Bill Wise Report" -> navigateTo(BillWiseReportFragment())
            "Item Wise Report" -> navigateTo(ItemWiseReportFragment())
            "Operator Wise Report" -> navigateTo(OperatorWiseReportFragment())
            "Tax Report" -> navigateTo(TaxReportFragment())
            "Payment-Wise Report" -> navigateTo(PaymentWiseReportFragment())
            "Returned Bill Report" -> navigateTo(ReturnedBillReportFragment())
            "Unsold Product Report" -> navigateTo(UnsoldProductReportFragment())
            "Category/Dept Wise Bill Report" -> navigateTo(CategoryWiseReportFragment())
            "Opr Bill Report" -> navigateTo(OperatorBilledReportFragment())
            "Item Bill Report" -> navigateTo(ItemBillReportFragment())
            "Time Wise Item Report" -> navigateTo(TimeWiseItemReportFragment())
            "Duplicate Bill Report" -> navigateTo(DuplicateReportFragment())
            "Void Bill Report" -> navigateTo(VoidBillReportFragment())
            "Profit & Loss Report" -> navigateTo(ProfitLossReportFragment())
            "Day-Wise Report" -> navigateTo(DayWiseReportFragment())
            "Month Wise Report" -> navigateTo(MonthWiseReportFragment())
            "Year Wise Report" -> navigateTo(YearWiseReportFragment())
            "Customer Payment" -> navigateTo(CustomerPaymentReportFragment())
            "UDF-Wise Report" -> navigateTo(UdfWiseReportFragment())
            "UDF Wise Item Report" -> navigateTo(UdfWiseItemReportFragment())
            "Customer Item Wise RPT" -> navigateTo(CustomerItemWiseReportFragment())
            "KOT Cancel Report" -> navigateTo(KotCancelReportFragment())
            "Calculator Report" -> navigateTo(CalculatorReportFragment())
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
        // Calculator mode is one screen and two settings. Everything else the drawer
        // offers - masters, reports, the other sale screens - belongs to a till that
        // has products, and this one does not; listing them would open pages that
        // could only be empty or, worse, could change what the calculator prints.
        //
        // What is left is what a calculator till still needs: somewhere to set the
        // printer up, and a way back out of the mode.
        if (SettingsCache.value(context, "G", "Mode") == "C") {
            return listOf(
                // The way back: Printer Settings is the only page a calculator till
                // can reach, and without this there is nothing to reach the
                // calculator from once it is open.
                TreeNode(CALCULATOR),
                TreeNode("Calculator Report"),
                TreeNode("Settings", listOf(TreeNode("Printer Settings"), TreeNode(CHANGE_MODE)))
            )
        }

        val isGrocery = SettingsCache.value(context, "G", "Mode") == "G"
        // Sale Return and Advance Payment are grocery-only flows; hidden in Restaurant.
        val isRestaurant = SettingsCache.value(context, "G", "Mode") == "R"

        // Admin-set section access: a section switched off is hidden from a general
        // user's drawer; an admin always sees all three.
        val canMaster = GeneralSettingsDao.canAccessSection(context, GeneralSettingsDao.KEY_ACCESS_MASTER)
        val canSettings = GeneralSettingsDao.canAccessSection(context, GeneralSettingsDao.KEY_ACCESS_SETTINGS)
        val canReports = GeneralSettingsDao.canAccessSection(context, GeneralSettingsDao.KEY_ACCESS_REPORTS)

        // Filtered by the same rule the Reports grid filters its tiles with, so the
        // drawer and the grid cannot disagree about which reports this till has -
        // see [ReportsFragment.isVisible].
        val reportTitles = listOf(
            "Bill Wise Report", "Item Wise Report", "Operator Wise Report", "Void Bill Report",
            "Tax Report", "Duplicate Bill Report", "Stock Report", "Item Bill Report",
            "Returned Bill Report", "UDF-Wise Report", "Payment-Wise Report", "Unsold Product Report",
            "Opr Bill Report", "Category/Dept Wise Bill Report", "Payment & Receipt", "Customer Payment",
            "Customer Ledger", "Profit & Loss Report", "KOT Cancel Report", "Day-Wise Report",
            "Month Wise Report", "Year Wise Report", "UDF Wise Item Report", "Customer Item Wise RPT",
            "Time Wise Item Report"
        ).filter { ReportsFragment.isVisible(context, it) }

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

        return buildList {
            if (canMaster) add(TreeNode("Master", listOf(
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
            )))
            if (canSettings) add(TreeNode("Settings", listOf(
                TreeNode("General Settings"),
                TreeNode("Bill Settings"),
                TreeNode("Tax Settings"),
               // TreeNode("Inventory & Stock Settings"),
                // Same position it holds in the Settings tile grid, so the two ways
                // in list the screens in one order.
                TreeNode("Printer Settings"),
                TreeNode("App Settings"),
                TreeNode("About App")
            )))
            addAll(stockNodes)
            add(TreeNode("Sale"))
            // Sale Return + Advance Payment are grocery-only; omitted in Restaurant.
            if (!isRestaurant) {
                add(TreeNode("Sale Return"))
                add(TreeNode("Advance Payment"))
            }
            // TreeNode("Duplicate Bill"); TreeNode("Delete Bill")
            if (canReports) add(TreeNode("Reports", reportTitles.map { TreeNode(it) }))
        }
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
        private val activeTitle: String?,
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

            // The open page's leaf is highlighted (accent tint + accent bold text) so
            // the drawer shows where the user is; every other row uses its normal style.
            val isActive = !vn.node.hasChildren && vn.node.title == activeTitle
            if (isActive) {
                holder.root.setBackgroundColor(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(themeColor, 28)
                )
                holder.tvTitle.setTextColor(themeColor)
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                holder.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                holder.tvTitle.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (vn.depth == 0) R.color.text_main else R.color.text_secondary
                    )
                )
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }

        override fun getItemCount() = visible.size
    }
}
