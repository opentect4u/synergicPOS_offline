package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.MainActivity
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    private lateinit var rvSettings: RecyclerView
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var tvNoResults: TextView

    /**
     * One searchable setting: its search [name], the screen (path) that holds it,
     * and [match] — the on-screen title used to highlight the exact row.
     */
    private data class SettingEntry(
        val name: String, val screen: String, val open: () -> Fragment, val match: String = name
    )

    /** Every setting across the sub-screens, with the screen it lives on. */
    private val catalog: List<SettingEntry> by lazy {
        val general = { GeneralSettingsFragment() as Fragment }
        val bill = { BillSettingsFragment() as Fragment }
        val tax = { TaxSettingsFragment() as Fragment }
        val app = { AppSettingsFragment() as Fragment }
        val printer = { PrintSettingsFragment() as Fragment }
        val printTemplate = {
            PrintSettingsFragment.newInstance(PrintSettingsFragment.TAB_TEMPLATE) as Fragment
        }
        val printLanguage = { PrintLanguageFragment() as Fragment }
        fun e(name: String, screen: String, open: () -> Fragment, match: String = name) =
            SettingEntry(name, screen, open, match)
        listOf(
            // General Settings
            e("Mode", "General Settings", general),
            e("Change Password", "General Settings", general),
            e("Sale Return", "General Settings", general),
            e("Sale Return Days", "General Settings", general, match = "Return within"),
            e("Last Bill Status", "General Settings", general),
            e("Enter Quantity Status", "General Settings", general),
            e("Item Rate", "General Settings", general),
            e("After Login", "General Settings", general),
            e("Landing Screen", "General Settings", general, match = "After Login"),
            e("Stock", "General Settings", general),
            e("Stock Alert", "General Settings", general),
            e("Alert Quantity", "General Settings", general),
            // Bill Settings
            e("Bill No. Character", "Bill Settings", bill),
            e("Reset Bill No.", "Bill Settings", bill),
            e("Set Bill No.", "Bill Settings", bill),
            e("Bill Format", "Bill Settings", bill, match = "Bill format"),
            e("Amount in Words", "Bill Settings", bill, match = "Amount in words"),
            e("Total Amount Font Size", "Bill Settings", bill, match = "Total amount Font Size"),
            e("Two Copy Bill", "Bill Settings", bill, match = "Two copy bill"),
            e("Coupon Splitting", "Bill Settings", bill),
            e("Coupon", "Bill Settings", bill, match = "Coupon Splitting"),
            e("Round Off", "Bill Settings", bill),
            e("HSN Code", "Bill Settings", bill),
            e("Product Serial Number", "Bill Settings", bill),
            e("Time on Bill", "Bill Settings", bill),
            e("Customer Address Printing", "Bill Settings", bill),
            e("Customer Details", "Bill Settings", bill),
            e("UPI QR", "Bill Settings", bill, match = "UPI QR on bill"),
            e("UPI ID", "Bill Settings", bill),
            e("QR Code", "Bill Settings", bill, match = "UPI QR on bill"),
            e("Scan to Pay", "Bill Settings", bill, match = "UPI QR on bill"),
            // Tax Settings
            e("Discount", "Tax Settings", tax),
            e("Discount Type", "Tax Settings", tax),
            e("Discount Position", "Tax Settings", tax, match = "Discount position"),
            e("GST", "Tax Settings", tax),
            e("GST Type", "Tax Settings", tax, match = "GST"),
            e("VAT", "Tax Settings", tax),
            e("VAT Type", "Tax Settings", tax, match = "VAT"),
            // App Settings
            e("Manual Rate", "App Settings", app),
            e("Cash Reception", "App Settings", app),
            e("Other Charges", "App Settings", app),
            e("Payment Mode", "App Settings", app),
            e("Biometric Login", "App Settings", app),
            e("Shift", "App Settings", app),
            e("Fingerprint Login", "App Settings", app, match = "Biometric Login"),
            // Printer
            e("Printer", "Printer Settings", printer),
            e("Print Template", "Printer Settings", printTemplate),
            e("Bill Template", "Printer Settings", printTemplate, match = "Print Template"),
            // Print Language
            e("Print Language", "Language", printLanguage, match = "PRINT LANGUAGE"),
            e("Language", "Language", printLanguage, match = "APP LANGUAGE"),
            e("Bill Language", "Language", printLanguage, match = "PRINT LANGUAGE"),
            e("Report Language", "Language", printLanguage, match = "PRINT LANGUAGE"),
            e("App Language", "Language", printLanguage, match = "APP LANGUAGE"),
            e("Screen Language", "Language", printLanguage, match = "APP LANGUAGE")
        ) + restaurantAppSettings(app)
    }

    /** Restaurant-only App Settings toggles — searchable only when Mode = Restaurant. */
    private fun restaurantAppSettings(app: () -> Fragment): List<SettingEntry> {
        if (SettingsCache.value(requireContext(), "G", "Mode") != "R") return emptyList()
        return listOf(
            SettingEntry("Coupon Mode", "App Settings", app),
            SettingEntry("KOT", "App Settings", app),
            SettingEntry("Table Merge", "App Settings", app),
            SettingEntry("Table Shift", "App Settings", app),
            SettingEntry("Table Split", "App Settings", app)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvSettings = view.findViewById(R.id.rvSettings)
        rvSearchResults = view.findViewById(R.id.rvSearchResults)
        tvNoResults = view.findViewById(R.id.tvNoResults)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvSettings.layoutManager = GridLayoutManager(requireContext(), columns)

        // ---- Global settings search ----
        rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        val results = mutableListOf<SettingEntry>()
        val searchAdapter = SearchAdapter(results) { entry ->
            val fragment = entry.open().apply {
                arguments = android.os.Bundle().apply {
                    putString(com.example.synergic_pos_offline.utils.SettingsHighlighter.ARG_SETTING, entry.match)
                }
            }
            openFragment(fragment)
        }
        rvSearchResults.adapter = searchAdapter

        fun applySearch(raw: String) {
            val q = raw.trim()
            val searching = q.isNotEmpty()
            rvSettings.isVisible = !searching
            rvSearchResults.isVisible = searching
            results.clear()
            if (searching) {
                results.addAll(catalog.filter {
                    it.name.contains(q, true) || it.screen.contains(q, true)
                })
            }
            searchAdapter.notifyDataSetChanged()
            tvNoResults.isVisible = searching && results.isEmpty()
        }
        view.findViewById<TextInputEditText>(R.id.etSettingsSearch).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = applySearch(s?.toString().orEmpty())
                override fun afterTextChanged(s: Editable?) {}
            }
        )
        ThemeManager.applyTheme(view)

        val settingsItems = listOf(
            SettingsItem("General Settings", android.R.drawable.ic_menu_preferences, R.color.menu_settings, R.color.menu_settings_icon),
            SettingsItem("Bill Settings", android.R.drawable.ic_menu_edit, R.color.menu_master, R.color.menu_master_icon),
            SettingsItem("Tax Settings", android.R.drawable.ic_menu_sort_by_size, R.color.menu_report, R.color.menu_report_icon),
           // SettingsItem("Inventory & Stock Settings", android.R.drawable.ic_menu_agenda, R.color.menu_inventory, R.color.menu_inventory_icon),
            // The old md_printer picker's tile is hidden (kept, not deleted, in case it's
            // needed again) - "Operating Printer" now takes over the "Printer Settings" name
            // and spot in the grid, routed via its own key so the two don't collide below.
            SettingsItem("Printer Settings", R.drawable.ic_print, R.color.menu_report, R.color.menu_report_icon, key = "Operating Printer"),
            SettingsItem("App Settings", android.R.drawable.ic_menu_manage, R.color.menu_sale, R.color.menu_sale_icon),
            SettingsItem("About App", android.R.drawable.ic_menu_info_details, R.color.menu_inventory, R.color.menu_inventory_icon),
            // Beside About App: it is about how the till prints rather than about a
            // part of the sale, which is what the four tiles above it are each for.
            SettingsItem("Language", android.R.drawable.ic_menu_sort_alphabetically, R.color.menu_sale, R.color.menu_sale_icon)
        ).filter {
            // About App is granted separately in General Settings ▸ Access Control: an
            // admin always sees it, a general user only when it has been switched on.
            it.key != "About App" || com.example.synergic_pos_offline.database.GeneralSettingsDao
                .canAccessSection(requireContext(), com.example.synergic_pos_offline.database.GeneralSettingsDao.KEY_ACCESS_ABOUT_APP)
        }

        // One decision, on the tile's key. It used to be two `when` blocks running
        // one after the other, so opening any settings screen also fell through the
        // second and toasted "Opening ..." over the screen it had just opened.
        rvSettings.adapter = SettingsAdapter(settingsItems) { item ->
            when (item.key) {
                "General Settings" -> openFragment(GeneralSettingsFragment())
                "Bill Settings" -> openFragment(BillSettingsFragment())
                "Tax Settings" -> openFragment(TaxSettingsFragment())
                "App Settings" -> openFragment(AppSettingsFragment())
                "Language" -> openFragment(PrintLanguageFragment())
                // The tile is filtered out when access is off; refused here as well so
                // the rule holds however the screen is reached.
                "About App" ->
                    if (com.example.synergic_pos_offline.database.GeneralSettingsDao.canAccessSection(
                            requireContext(),
                            com.example.synergic_pos_offline.database.GeneralSettingsDao.KEY_ACCESS_ABOUT_APP
                        )
                    ) openFragment(AboutAppFragment())
                    else Toast.makeText(
                        requireContext(), "About App is not available for your login", Toast.LENGTH_SHORT
                    ).show()
                "Printer Settings" -> openFragment(PrinterSettingsFragment())
                "Operating Printer" -> openFragment(PrintSettingsFragment())
                else -> Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFragment(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    data class SettingsItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int,
        // Distinct from the display title so a tile can be relabelled without
        // changing which screen it opens.
        val key: String = title
    )

    private inner class SettingsAdapter(
        private val items: List<SettingsItem>,
        private val onItemClick: (SettingsItem) -> Unit
    ) : RecyclerView.Adapter<SettingsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivMenuIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvMenuTitle)
            val cardIconContainer: MaterialCardView = view.findViewById(R.id.cardIconContainer)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemClick(items[pos])
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_menu_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.ivIcon.setImageResource(item.iconRes)

            val iconColor = ContextCompat.getColor(requireContext(), item.iconColorRes)
            val bgColor = ContextCompat.getColor(requireContext(), item.bgColorRes)

            holder.ivIcon.imageTintList = ColorStateList.valueOf(iconColor)
            holder.cardIconContainer.setCardBackgroundColor(bgColor)
        }

        override fun getItemCount() = items.size
    }

    /** Renders the search results: setting name + its "Settings › <screen>" path. */
    private inner class SearchAdapter(
        private val items: List<SettingEntry>,
        private val onClick: (SettingEntry) -> Unit
    ) : RecyclerView.Adapter<SearchAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvSettingName)
            val tvPath: TextView = view.findViewById(R.id.tvSettingPath)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_settings_search, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvPath.text = "Settings › ${item.screen}"
        }

        override fun getItemCount() = items.size
    }
}
