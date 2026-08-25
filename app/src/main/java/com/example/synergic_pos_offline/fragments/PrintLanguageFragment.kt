package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.utils.PrintLanguage
import com.example.synergic_pos_offline.utils.ProductName
import com.example.synergic_pos_offline.utils.SettingsHighlighter
import com.example.synergic_pos_offline.utils.ThemeManager

/**
 * Print Language - which language the printer labels a slip in.
 *
 * One radio per language, and picking one stores it there and then. There is no Save
 * button on purpose: there is a single value on this screen and nothing to be
 * consistent with, so a choice that needed confirming would only be a choice an
 * operator could leave half-made. The line under the list reports what is *stored*,
 * so the screen and the printer never disagree.
 *
 * The radios are built from [PrintLanguage.Language] rather than declared in the
 * layout - a language exists once it has a row in the dictionary, and this screen
 * should not be a second place it also has to be added.
 */
class PrintLanguageFragment : Fragment(), TitledScreen {

    override val screenTitle = "Language"

    private lateinit var group: RadioGroup
    private lateinit var appGroup: RadioGroup

    /** Held so the writes below are not attributed to whatever fires the listener. */
    private var applying = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_print_language, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        group = view.findViewById(R.id.rgPrintLanguage)
        val current = PrintLanguage.of(requireContext())
        val accent = ThemeManager.getThemeColor(requireContext())

        PrintLanguage.Language.values().forEach { language ->
            group.addView(radioFor(language, accent))
        }
        group.check(idOf(current))

        group.setOnCheckedChangeListener { _, checkedId ->
            if (applying) return@setOnCheckedChangeListener
            PrintLanguage.Language.values().getOrNull(checkedId - 1)?.let { choose(it) }
        }

        // The app's own screens, chosen separately from the paper. Its radio ids are
        // offset past the print group's so the two sets cannot collide in one tree.
        appGroup = view.findViewById(R.id.rgAppLanguage)
        PrintLanguage.Language.values().forEach { language ->
            appGroup.addView(radioFor(language, accent).also { it.id = appIdOf(language) })
        }
        appGroup.check(appIdOf(com.example.synergic_pos_offline.utils.AppLanguage.of(requireContext())))
        appGroup.setOnCheckedChangeListener { _, checkedId ->
            if (applying) return@setOnCheckedChangeListener
            PrintLanguage.Language.values().getOrNull(checkedId - APP_ID_BASE)?.let { chooseApp(it) }
        }

        showProductNames(view, current)
        showScope(view, current)
        ThemeManager.applyTheme(view)
        SettingsHighlighter.apply(view, arguments?.getString(SettingsHighlighter.ARG_SETTING))
    }

    /**
     * One language's row: its English name, with the name it calls itself beside it.
     *
     * Both, rather than either alone. The English name is what somebody setting the
     * till up is looking for in a list they were told to find "Tamil" in; the native
     * name is what the person the slips are actually for will recognise, and it also
     * shows at a glance whether this device has the font to print that script at all -
     * a row of empty boxes here is a row of empty boxes on the paper.
     */
    private fun radioFor(language: PrintLanguage.Language, accent: Int): RadioButton =
        RadioButton(requireContext()).apply {
            // Ordinal + 1: a view id of 0 is indistinguishable from "no id", and
            // RadioGroup treats it as nothing checked - which would leave English,
            // the first language in the list, unable to show as the chosen one.
            id = idOf(language)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(paddingLeft, pad, paddingRight, pad)
            textSize = 15f
            setTextColor(resources.getColor(R.color.text_main, null))
            buttonTintList = ColorStateList.valueOf(accent)
            text = if (language == PrintLanguage.DEFAULT) {
                "${language.englishName} (Default)"
            } else if (language.nativeName == language.englishName) {
                language.englishName
            } else {
                "${language.englishName}  ·  ${language.nativeName}"
            }
        }

    /** Radio id for [language] in the app-language group. */
    private fun appIdOf(language: PrintLanguage.Language): Int = APP_ID_BASE + language.ordinal

    /**
     * Stores the screen language and relabels the app there and then.
     *
     * Applied immediately rather than on the next screen, because this screen is
     * itself the proof: an operator who picks Hindi should see this page turn, and
     * one who picked it by accident should be able to read their way back out.
     */
    private fun chooseApp(language: PrintLanguage.Language) {
        val stored = runCatching {
            GeneralSettingsDao(requireContext()).saveAppLanguage(language.code)
            true
        }.getOrElse { error ->
            android.util.Log.e(TAG, "Could not store the app language", error)
            false
        }

        if (!stored) {
            applying = true
            appGroup.check(appIdOf(com.example.synergic_pos_offline.utils.AppLanguage.of(requireContext())))
            applying = false
            toast("Could not save the app language")
            return
        }
        (activity as? com.example.synergic_pos_offline.MainActivity)?.applyLanguageEverywhere()
        toast("The app will read in ${language.englishName}")
    }

    /** Stores the choice, then says on the screen what is now stored. */
    private fun choose(language: PrintLanguage.Language) {
        val stored = runCatching {
            GeneralSettingsDao(requireContext()).savePrintLanguage(language.code)
            true
        }.getOrElse { error ->
            android.util.Log.e(TAG, "Could not store the print language", error)
            false
        }

        if (!stored) {
            // Put the radio back on what is actually stored rather than leaving the
            // screen claiming a language the printer will not use.
            applying = true
            group.check(idOf(PrintLanguage.of(requireContext())))
            applying = false
            toast("Could not save the print language")
            return
        }
        view?.let {
            showProductNames(it, language)
            showScope(it, language)
        }
        toast("Bills and reports will print in ${language.englishName}")
    }

    /**
     * This shop's own product names, beside how the bill will spell them.
     *
     * The reason this screen exists in the shape it does. A product name is not
     * translated - it is respelled in the other script, letter for sound - and how
     * well that reads depends entirely on what is in *this* catalogue. Told about it
     * in the abstract, an operator has no way to judge it; shown six of their own
     * products, they can tell in a second whether it is worth having.
     *
     * Real names, not examples, wherever the till has any. A demonstration on
     * invented products would be a demonstration of nothing.
     */
    private fun showProductNames(view: View, language: PrintLanguage.Language) {
        val card = view.findViewById<View>(R.id.cardProductNames)
        val rows = view.findViewById<LinearLayout>(R.id.llProductNames)
        val note = view.findViewById<TextView>(R.id.tvProductNamesNote)
        rows.removeAllViews()

        // Nothing changes in English, so there is nothing to show.
        if (!ProductName.applies(language)) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE

        val names = productNames()
        note.text = if (names.isEmpty()) {
            "No products on this till yet. These are examples of what happens to a name."
        } else {
            "How the bill will print these. Everyday words are translated into " +
                "${language.englishName}; brand names are spelled in its letters, since a " +
                "brand is the same in every language."
        }
        val sample = names.ifEmpty { EXAMPLES }
        sample.forEach { name ->
            rows.addView(nameRow(name, ProductName.inPrintLanguage(language, name)))
        }
    }

    /** "PARLE-G  →  पार्ले-जी" - what was typed, and what will print. */
    private fun nameRow(from: String, to: String): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (5 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = from
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(resources.getColor(R.color.text_secondary, null))
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                text = "→"
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                setPadding(pad, 0, pad, 0)
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = to
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(resources.getColor(R.color.text_main, null))
            })
        }

    /**
     * A handful of this till's product names, longest-selling first would be better
     * but the master is not ordered that way - so simply the first few it holds.
     */
    private fun productNames(): List<String> = runCatching {
        val found = mutableListOf<String>()
        DatabaseHelper.getInstance(requireContext()).readableDatabase.query(
            DatabaseHelper.Tables.MD_PRODUCTS, arrayOf("product_name"),
            "product_name IS NOT NULL AND product_name <> ''", null, null, null, "id ASC", "6"
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { found.add(it.uppercase()) }
            }
        }
        found
    }.getOrDefault(emptyList())

    /** Shown only on a till with no catalogue yet - chosen to show the range. */
    private val EXAMPLES = listOf(
        "PARLE-G 100G", "TOOTHPASTE", "BASMATI RICE 5KG", "ATTA", "COLGATE", "PEPSI 500ML"
    )

    /**
     * What the stored choice reaches, and what it deliberately does not.
     *
     * Said here because it is the question this screen raises and cannot be answered
     * from the list itself: an operator who picks Tamil and then finds their product
     * names still in English has to be able to see that this was the intent rather
     * than a fault. The shop's own words stay the shop's own words.
     */
    private fun showScope(view: View, language: PrintLanguage.Language) {
        val english = language == PrintLanguage.Language.ENGLISH
        view.findViewById<TextView>(R.id.tvPrintLanguageScope).text = buildString {
            if (english) {
                append("Bills, receipts and reports print with English labels.")
            } else {
                append("Printed labels - column headings, totals, captions and report ")
                append("titles - come out in ${language.englishName} on bills, receipts ")
                append("and reports.")
            }
            if (!english) {
                append("\n\nProduct names are handled a word at a time. Everyday retail ")
                append("words - rice, sugar, soap, oil - are translated into ")
                append("${language.englishName}. Anything else, a brand above all, is ")
                append("spelled in ${language.englishName} letters instead: a brand is the ")
                append("same name in every language, and a customer looking for it on a ")
                append("shelf would not find it translated.")
            }
            append("\n\nLeft as they are, in every language:")
            append("\n•  The store name, address and GSTIN, and your header and footer lines.")
            append("\n•  Customer and operator names, and your own bill captions.")
            append("\n•  Quantities and units - 500ML stays 500ML, beside the figure it matches.")
            append("\n•  GST, SGST, CGST, IGST, VAT, HSN, KOT and UDF - the statutory short forms.")
            append("\n•  Amount in words, dates, figures and the app's own screens.")
        }
    }

    /** This language's radio id - see where it is set for why it is not the ordinal. */
    private fun idOf(language: PrintLanguage.Language): Int = language.ordinal + 1

    private fun toast(message: String) =
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()

    private companion object {
        /**
         * Where the app-language radios start numbering.
         *
         * Far enough past the print group's ids (ordinal + 1) that the two sets of
         * radios can share one view tree without either finding the other's.
         */
        const val APP_ID_BASE = 101

        const val TAG = "PrintLanguage"
    }
}
