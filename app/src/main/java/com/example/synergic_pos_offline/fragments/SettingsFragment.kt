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
        val printer = { OperatingPrinterFragment() as Fragment }
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
            // Bill Settings
            e("Bill No. Character", "Bill Settings", bill),
            e("Reset Bill No.", "Bill Settings", bill),
            e("Set Bill No.", "Bill Settings", bill),
            e("Bill Format", "Bill Settings", bill, match = "Bill format"),
            e("Amount in Words", "Bill Settings", bill, match = "Amount in words"),
            e("Total Amount Font Size", "Bill Settings", bill, match = "Total amount Font Size"),
            e("Two Copy Bill", "Bill Settings", bill, match = "Two copy bill"),
            e("Round Off", "Bill Settings", bill),
            e("HSN Code", "Bill Settings", bill),
            e("Customer Address Printing", "Bill Settings", bill),
            e("Customer Details", "Bill Settings", bill),
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
            // Printer
            e("Printer", "Printer Settings", printer)
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
            SettingsItem("App Settings", android.R.drawable.ic_menu_manage, R.color.menu_sale, R.color.menu_sale_icon)
        )

        rvSettings.adapter = SettingsAdapter(settingsItems) { item ->
            val fragment: Fragment? = when (item.title) {
                "General Settings" -> GeneralSettingsFragment()
                "Bill Settings" -> BillSettingsFragment()
                "Tax Settings" -> TaxSettingsFragment()
                "App Settings" -> AppSettingsFragment()
                else -> null
            }
            if (fragment != null) openFragment(fragment)
            else Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
            when (item.key) {
                "Printer Settings" -> openFragment(PrinterSettingsFragment())
                "Operating Printer" -> openFragment(OperatingPrinterFragment())
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
