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
import com.example.synergic_pos_offline.fragments.BillHeaderFooterFragment
import com.example.synergic_pos_offline.fragments.BillLogoFragment
import com.example.synergic_pos_offline.fragments.CategoryDepartmentFragment
import com.example.synergic_pos_offline.fragments.CustomerFragment
import com.example.synergic_pos_offline.fragments.CategoryProductsFragment
import com.example.synergic_pos_offline.fragments.DatabaseSettingsFragment
import com.example.synergic_pos_offline.fragments.DescriptionLedgerFragment
import com.example.synergic_pos_offline.fragments.HeaderFooterFragment
import com.example.synergic_pos_offline.fragments.InventoryFragment
import com.example.synergic_pos_offline.fragments.ItemwiseSearchFragment
import com.example.synergic_pos_offline.fragments.LoginFragment
import com.example.synergic_pos_offline.fragments.MasterFragment
import com.example.synergic_pos_offline.fragments.PosBillingFragment
import com.example.synergic_pos_offline.fragments.ProductsFragment
import com.example.synergic_pos_offline.fragments.RegistrationFragment
import com.example.synergic_pos_offline.fragments.ReportsFragment
import com.example.synergic_pos_offline.fragments.SalesFragment
import com.example.synergic_pos_offline.fragments.SettingsFragment
import com.example.synergic_pos_offline.fragments.UnitFragment
import com.example.synergic_pos_offline.fragments.UserManagementFragment
import com.example.synergic_pos_offline.fragments.WaiterFragment
import com.example.synergic_pos_offline.utils.DatabaseSeeder
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager

class MainActivity : AppCompatActivity() {

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
        // The UI is designed light-only (hardcoded white backgrounds). Force day
        // mode so uncolored EditText input text stays dark and remains visible.
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Fill the master tables with demo data on first run (idempotent: only
        // ever fills empty tables, never overwrites hand-entered rows).
        Thread { DatabaseSeeder.seedIfEmpty(applicationContext) }.start()

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
    }

    /** Updates the global header title/subtitle and back-button for [f]. */
    private fun updateHeader(f: Fragment) {
        tvHeaderTitle.text = titleFor(f)
        val user = SessionManager.currentUser
        tvHeaderSubtitle.text = "Hello, ${user?.userId ?: "User"}"
        // Back is hidden on the Dashboard (root), shown on sub-pages.
        btnBack.visibility = if (f is com.example.synergic_pos_offline.fragments.MenuFragment) {
            View.GONE
        } else {
            View.VISIBLE
        }
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
        // Clear the whole back stack to return to the login screen.
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    // ---- Drawer control, called from any fragment's hamburger button --------

    fun openDrawer() {
        val user = SessionManager.currentUser
        tvSidebarUser.text = if (user != null) "Active User: ${user.userId}" else "Main Menu"
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
    }

    /** Re-tints every currently inflated view + the status bar + the drawer. */
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

    private fun handleLeaf(title: String) {
        closeDrawer()
        when (title) {
            "Master" -> navigateTo(MasterFragment())
            "Settings" -> navigateTo(SettingsFragment())
            "Stock & Inventory" -> navigateTo(InventoryFragment())
            "Reports" -> navigateTo(ReportsFragment())
            "Sale" -> navigateTo(SalesFragment())
            "Header & Footer" -> navigateTo(HeaderFooterFragment())
            "Sale" -> navigateTo(PosBillingFragment())
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
            else -> Toast.makeText(this, "Opening $title...", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Menu tree ----------------------------------------------------------

    private fun buildMenuTree(): List<TreeNode> {
        val reportTitles = listOf(
            "Bill Wise Report", "Item Wise Report", "Operator Wise Report", "Void Bill Report",
            "Tax Report", "Duplicate Bill Report", "Stock Report", "Item Bill Report",
            "Returned Bill Report", "UDF-Wise Report", "Payment-Wise Report", "Unsold Product Report",
            "Opr Bill Report", "Category/Dept Wise Bill Report", "Payment & Receipt", "Customer Payment",
            "Customer Ledger", "Profit & Loss Report", "KOT Cancel Report", "Day-Wise Report",
            "Month Wise Report", "Year Wise Report", "UDF Wise Item Report", "Customer Item Wise RPT",
            "Time Wise Item Report"
        )

        return listOf(
            TreeNode("Master", listOf(
                TreeNode("Header & Footer", listOf(
                    TreeNode("Bill Header & Footer"),
                    TreeNode("KOT Header & Footer"),
                    TreeNode("Bill Header Footer Logo"),
                    TreeNode("KOT Header Footer Logo")
                )),
                TreeNode("Date & Time"),
                TreeNode("User Management"),
                TreeNode("Database Settings", listOf(
                    TreeNode("Category/Department"),
                    TreeNode("Products"),
                    TreeNode("Customers"),
                    TreeNode("Description/Ledger"),
                    TreeNode("Units"),
                    TreeNode("Waiter")
                ))
            )),
            TreeNode("Settings", listOf(
                TreeNode("General Settings"),
                TreeNode("Bill Settings"),
                TreeNode("Tax Settings"),
                TreeNode("Inventory & Stock Settings"),
                TreeNode("App Settings")
            )),
            TreeNode("Stock & Inventory", listOf(
                TreeNode("Purchase Item"),
                TreeNode("Purchase Return"),
                TreeNode("Generate Barcode"),
                TreeNode("Print Barcode"),
                TreeNode("Write Off Damage Item"),
                TreeNode("Reset Stock")
            )),
            TreeNode("Sale"),
            TreeNode("Sale Return"),
            TreeNode("Advance Payment"),
            TreeNode("Duplicate Bill"),
            TreeNode("Delete Bill"),
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
