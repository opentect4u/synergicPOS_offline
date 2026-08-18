package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Typeface
import java.util.Locale

/**
 * The language every printed slip is labelled in - bills, receipts and reports.
 *
 * ## What this does and does not translate
 *
 * It translates the *labels the app prints*: column headings, totals, captions and
 * report titles. It does not translate the shop's own data, because that data is not
 * the app's to rewrite - the store's name, its header and footer lines, product and
 * customer names, and the operator's captions all print exactly as they were typed
 * in. Nor does it touch the app's own screens: this is what comes off the printer,
 * not what the operator reads on the tablet.
 *
 * ## How a label is translated
 *
 * By phrase, from [WORDS] - not word by word through a machine. A bill is a legal
 * document and a mistranslated total is worse than an untranslated one, so anything
 * this file has never been told about is printed in English rather than guessed at.
 * [phrase] will assemble a two-word label out of two known words ("TOTAL" + "TAX"),
 * which is what lets a new report's headings arrive already translated, but only
 * when *every* word in it is known.
 *
 * The shapes a printed label actually comes in are handled around that lookup:
 *
 *  * `LABEL : value` - the label is translated, the value is left alone unless it is
 *    itself a known word ("PAY MODE : CASH");
 *  * `SGST @ 2.50%` - the rate is a figure, so only what precedes the @ is looked up;
 *  * `37 bill(s)` - the count is a figure, the noun after it is looked up;
 *  * `01-08-2026 to 11-08-2026` - only the joining word is translated;
 *  * anything with a line break is translated a line at a time.
 *
 * ## Scripts and the printer
 *
 * These are all Indic scripts, which the bundled Roboto Mono does not carry. See
 * [typeface]: a translated slip is set in the platform's monospace family instead,
 * whose fallback chain does carry them - so the figures stay in a fixed-width face
 * and the labels still print rather than coming out as a row of empty boxes.
 */
object PrintLanguage {

    /**
     * The languages a slip can be printed in.
     *
     * [code] is what is stored, and it is what the enum is read back by - the names
     * are display text and may be reworded without orphaning every till that has
     * already chosen one.
     */
    enum class Language(val code: String, val englishName: String, val nativeName: String) {
        ENGLISH("EN", "English", "English"),
        HINDI("HI", "Hindi", "हिन्दी"),
        MARATHI("MR", "Marathi", "मराठी"),
        TAMIL("TA", "Tamil", "தமிழ்"),
        BENGALI("BN", "Bengali", "বাংলা"),
        TELUGU("TE", "Telugu", "తెలుగు"),
        KANNADA("KN", "Kannada", "ಕನ್ನಡ"),
        GUJARATI("GU", "Gujarati", "ગુજરાતી"),
        ODIA("OR", "Odia", "ଓଡ଼ିଆ"),
        PUNJABI("PA", "Punjabi", "ਪੰਜਾਬੀ"),
        ASSAMESE("AS", "Assamese", "অসমীয়া");

        /** Where this language's word sits in a [WORDS] row; -1 for English. */
        internal val slot: Int get() = ordinal - 1

        companion object {
            /** Accepts the stored code, or the English name. Unknown reads as English. */
            fun fromStored(value: String?): Language = value?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { v -> values().firstOrNull { it.code.equals(v, true) || it.englishName.equals(v, true) } }
                ?: ENGLISH
        }
    }

    /** The setting_name this is stored under, type 'G' - see `GeneralSettingsDao`. */
    const val SETTING_KEY = "Print Language"

    /** What a till prints in until somebody chooses otherwise. */
    val DEFAULT = Language.ENGLISH

    /**
     * The language this till prints in.
     *
     * Read from the login cache, which is where every other setting a renderer needs
     * is read from - a slip is rendered on the main thread and cannot afford a query
     * per label. A till that has never chosen prints English.
     */
    fun of(context: Context): Language = runCatching {
        Language.fromStored(SettingsCache.value(context, "G", SETTING_KEY))
    }.getOrDefault(DEFAULT)

    /**
     * The face a slip in [lang] is set in.
     *
     * English keeps [english], the bundled Roboto Mono the bills were laid out
     * against. Every other language is set in the platform's own monospace family:
     * Roboto Mono carries no Devanagari, Tamil, Bengali, Telugu, Kannada, Gujarati,
     * Odia or Gurmukhi, and a face with no fallback behind it prints those as empty
     * boxes. The platform family has the system fallback chain behind it, so Latin
     * and the figures stay fixed-width and the Indic labels come out as letters.
     */
    fun typeface(lang: Language, english: Typeface): Typeface =
        if (lang == Language.ENGLISH) english else Typeface.MONOSPACE

    // ---- Translating ----------------------------------------------------------

    /** [raw] in [lang], or [raw] itself where there is nothing to translate it to. */
    fun tr(lang: Language, raw: String?): String {
        val text = raw ?: return ""
        if (lang == Language.ENGLISH || text.isBlank()) return text
        return runCatching { translate(lang, text) }.getOrDefault(text)
    }

    /** Every entry of [list] translated - a report's column headings, say. */
    fun tr(lang: Language, list: List<String>): List<String> =
        if (lang == Language.ENGLISH) list else list.map { tr(lang, it) }

    /**
     * The *label* of each pair translated, the value left as it is.
     *
     * A summary is label/value pairs where the value is already formatted money or a
     * count, so only the left-hand side is language at all.
     */
    fun trLabels(lang: Language, pairs: List<Pair<String, String>>): List<Pair<String, String>> =
        if (lang == Language.ENGLISH) pairs else pairs.map { (label, value) -> tr(lang, label) to value }

    private fun translate(lang: Language, text: String): String {
        // A label set over two lines is two labels as far as this is concerned.
        if (text.contains('\n')) {
            return text.split('\n').joinToString("\n") { translate(lang, it) }
        }
        val body = text.trim()
        if (body.isEmpty()) return text
        val lead = text.takeWhile { it == ' ' }

        // "3 operator(s) · 41 bill(s)" - each side stands on its own.
        if (body.contains(" · ")) {
            return lead + body.split(" · ").joinToString(" · ") { translate(lang, it) }
        }

        // "01-08-2026  to  11-08-2026" - the dates are dates; only "to" is language.
        RANGE.matchEntire(body)?.let { m ->
            word(lang, "TO")?.let { joiner ->
                return lead + m.groupValues[1] + "  " + joiner + "  " + m.groupValues[2]
            }
        }

        // "AMT : 640.00   QTY : 12" - a run of spaces is what a slip uses to set two
        // labelled figures on one line, so each side is translated on its own and the
        // spacing between them is carried through untouched.
        if (GAP.containsMatchIn(body)) {
            val out = StringBuilder()
            var from = 0
            GAP.findAll(body).forEach { gap ->
                out.append(translate(lang, body.substring(from, gap.range.first)))
                out.append(gap.value)
                from = gap.range.last + 1
            }
            out.append(translate(lang, body.substring(from)))
            return lead + out
        }

        // "LABEL : value", and the bare "LABEL :" that ends a totals block. The
        // separator is carried through exactly as it was written, so a slip that
        // aligns on "NAME  :" still aligns on it.
        LABELLED.matchEntire(body)?.let { m ->
            phrase(lang, m.groupValues[1])?.let { label ->
                val value = m.groupValues[3]
                val translated = if (value.isBlank()) value else phrase(lang, value) ?: value
                return lead + label + m.groupValues[2] + translated
            }
        }

        // "SGST @ 2.50%" - a rate is a figure, whatever language the slip is in.
        val at = body.indexOf('@')
        if (at > 0) {
            phrase(lang, body.substring(0, at))?.let {
                return lead + it + " " + body.substring(at)
            }
        }

        // "37 bill(s)", "6 slab(s)" - the count is a figure and the noun is not.
        COUNTED.matchEntire(body)?.let { m ->
            phrase(lang, m.groupValues[2])?.let {
                return lead + m.groupValues[1] + " " + it
            }
        }

        return phrase(lang, body)?.let { lead + it } ?: text
    }

    /**
     * [raw] as a whole phrase, or built from its words - null when it is not known.
     *
     * The word-by-word half only fires when every word is known, and it is what makes
     * the compounds these slips are full of ("TOTAL TAX", "CASH SALES", "STOCK
     * REPORT") come out translated without each having to be written down. It holds
     * because the compounds are all modifier-then-head, which is the order these
     * languages build them in too.
     */
    private fun phrase(lang: Language, raw: String): String? {
        val key = normalise(raw)
        if (key.isEmpty()) return null
        word(lang, key)?.let { return it }

        val words = key.split(' ')
        if (words.size < 2) return null
        // An acronym stands for itself, so it counts as known: that is what lets
        // "TOTAL SGST" and "VAT AMOUNT" come out translated around a short form that
        // must not be. Only these exact tokens qualify - an unrecognised word still
        // stops the whole label from being translated.
        val parts = words.map { w -> if (w in ACRONYMS) w else word(lang, w) ?: return null }
        // …but a label made of nothing but acronyms has not been translated at all.
        if (parts.none { it !in ACRONYMS }) return null
        return parts.joinToString(" ")
    }

    /**
     * The short forms that print as themselves in every language.
     *
     * They are what the paperwork these slips are checked against calls them - a GST
     * return is filed against SGST and CGST, not against their translations - so
     * rewriting them would make the bill harder to reconcile, not easier to read.
     */
    private val ACRONYMS = setOf(
        "GST", "SGST", "CGST", "IGST", "UTGST", "VAT", "CESS", "GST%", "VAT%",
        "GSTIN", "HSN", "SAC", "KOT", "UDF", "MRP", "POS", "ID", "M.ID"
    )

    /** The single entry for [key], already normalised. */
    private fun word(lang: Language, key: String): String? {
        val slot = lang.slot
        if (slot < 0) return null
        return WORDS[normalise(key)]?.getOrNull(slot)?.takeIf { it.isNotEmpty() }
    }

    /** Trim, collapse runs of space, upper-case - the form [WORDS] is keyed in. */
    private fun normalise(raw: String): String =
        raw.trim().replace(WHITESPACE, " ").uppercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
    private val RANGE = Regex("^(.*\\S)\\s+to\\s+(\\S.*)$", RegexOption.IGNORE_CASE)
    private val LABELLED = Regex("^([^:]+?)(\\s*:\\s*)(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val COUNTED = Regex("^([0-9][0-9.,]*)\\s+(\\S.*)$")
    private val GAP = Regex("\\s{2,}")

    // ---- The dictionary -------------------------------------------------------

    /**
     * Every label the printer is allowed to translate, in the order of
     * [Language] after English: Hindi, Marathi, Tamil, Bengali, Telugu, Kannada,
     * Gujarati, Odia, Punjabi, Assamese.
     *
     * Statutory acronyms are deliberately absent - GST, SGST, CGST, IGST, VAT, GSTIN,
     * HSN, KOT, UDF and the machine's own M.ID print as themselves in every language,
     * because that is what they are called on the paperwork these slips are checked
     * against. "TOTAL SGST" still translates: the TOTAL is a word and the SGST is not,
     * and only the word is looked up.
     */
    private val WORDS: Map<String, Array<String>> = buildMap {
        // ---- The words the compounds are built out of -------------------------
        w("TOTAL", "कुल", "एकूण", "மொத்த", "মোট", "మొత్తం", "ಒಟ್ಟು", "કુલ", "ମୋଟ", "ਕੁੱਲ", "মুঠ")
        w("GRAND TOTAL", "महायोग", "एकूण बेरीज", "மொத்தக் கூடுதல்", "সর্বমোট", "స్థూల మొత్తం", "ಒಟ್ಟು ಮೊತ್ತ", "કુલ સરવાળો", "ସର୍ବମୋଟ", "ਕੁੱਲ ਜੋੜ", "সৰ্বমুঠ")
        w("AMOUNT", "राशि", "रक्कम", "தொகை", "টাকা", "సొమ్ము", "ಮೊತ್ತ", "રકમ", "ରାଶି", "ਰਕਮ", "টকা")
        w("AMT", "राशि", "रक्कम", "தொகை", "টাকা", "సొమ్ము", "ಮೊತ್ತ", "રકમ", "ରାଶି", "ਰਕਮ", "টকা")
        w("QTY", "मात्रा", "प्रमाण", "அளவு", "পরিমাণ", "పరిమాణం", "ಪ್ರಮಾಣ", "જથ્થો", "ପରିମାଣ", "ਮਾਤਰਾ", "পৰিমাণ")
        w("QUANTITY", "मात्रा", "प्रमाण", "அளவு", "পরিমাণ", "పరిమాణం", "ಪ್ರಮಾಣ", "જથ્થો", "ପରିମାଣ", "ਮਾਤਰਾ", "পৰিমাণ")
        w("PRICE", "मूल्य", "किंमत", "விலை", "দাম", "ధర", "ಬೆಲೆ", "કિંમત", "ମୂଲ୍ୟ", "ਕੀਮਤ", "দাম")
        w("RATE", "दर", "दर", "விலை", "দর", "రేటు", "ದರ", "દર", "ଦର", "ਦਰ", "দৰ")
        w("PER UNIT PRICE", "प्रति इकाई मूल्य", "प्रति नग किंमत", "ஒரு அலகு விலை", "প্রতি এককের দাম", "యూనిట్ ధర", "ಪ್ರತಿ ಘಟಕ ಬೆಲೆ", "એકમ કિંમત", "ପ୍ରତି ଏକକ ମୂଲ୍ୟ", "ਪ੍ਰਤੀ ਇਕਾਈ ਕੀਮਤ", "প্ৰতি এককৰ দাম")
        w("ITEM", "वस्तु", "वस्तू", "பொருள்", "পণ্য", "వస్తువు", "ವಸ್ತು", "વસ્તુ", "ସାମଗ୍ରୀ", "ਵਸਤੂ", "সামগ্ৰী")
        w("ITEMS", "वस्तुएँ", "वस्तू", "பொருட்கள்", "পণ্য", "వస్తువులు", "ವಸ್ತುಗಳು", "વસ્તુઓ", "ସାମଗ୍ରୀ", "ਵਸਤੂਆਂ", "সামগ্ৰী")
        w("ITEM(S)", "वस्तुएँ", "वस्तू", "பொருட்கள்", "পণ্য", "వస్తువులు", "ವಸ್ತುಗಳು", "વસ્તુઓ", "ସାମଗ୍ରୀ", "ਵਸਤੂਆਂ", "সামগ্ৰী")
        w("NAME", "नाम", "नाव", "பெயர்", "নাম", "పేరు", "ಹೆಸರು", "નામ", "ନାମ", "ਨਾਮ", "নাম")
        w("ITEM NAME", "वस्तु का नाम", "वस्तूचे नाव", "பொருள் பெயர்", "পণ্যের নাম", "వస్తువు పేరు", "ವಸ್ತುವಿನ ಹೆಸರು", "વસ્તુનું નામ", "ସାମଗ୍ରୀର ନାମ", "ਵਸਤੂ ਦਾ ਨਾਮ", "সামগ্ৰীৰ নাম")
        w("SR.NO ITEM", "क्र. वस्तु", "अ.क्र. वस्तू", "வ.எண் பொருள்", "ক্র. পণ্য", "క్ర.సం వస్తువు", "ಕ್ರ.ಸಂ ವಸ್ತು", "ક્રમ વસ્તુ", "କ୍ର.ସଂ ସାମଗ୍ରୀ", "ਲੜੀ ਵਸਤੂ", "ক্ৰ. সামগ্ৰী")
        w("BILL", "बिल", "बिल", "பில்", "বিল", "బిల్లు", "ಬಿಲ್", "બિલ", "ବିଲ", "ਬਿੱਲ", "বিল")
        w("BILLS", "बिल", "बिल", "பில்கள்", "বিল", "బిల్లులు", "ಬಿಲ್‌ಗಳು", "બિલ", "ବିଲ", "ਬਿੱਲ", "বিল")
        w("BILL(S)", "बिल", "बिल", "பில்கள்", "বিল", "బిల్లులు", "ಬಿಲ್‌ಗಳು", "બિલ", "ବିଲ", "ਬਿੱਲ", "বিল")
        w("NO", "नं", "क्र", "எண்", "নং", "నం", "ಸಂ", "નં", "ନଂ", "ਨੰ", "নং")
        w("BILL NO", "बिल नं", "बिल क्र", "பில் எண்", "বিল নং", "బిల్లు నం", "ಬಿಲ್ ಸಂ", "બિલ નં", "ବିଲ ନଂ", "ਬਿੱਲ ਨੰ", "বিল নং")
        w("B. NO", "बिल नं", "बिल क्र", "பில் எண்", "বিল নং", "బిల్లు నం", "ಬಿಲ್ ಸಂ", "બિલ નં", "ବିଲ ନଂ", "ਬਿੱਲ ਨੰ", "বিল নং")
        w("B.NO", "बिल नं", "बिल क्र", "பில் எண்", "বিল নং", "బిల్లు నం", "ಬಿಲ್ ಸಂ", "બિલ નં", "ବିଲ ନଂ", "ਬਿੱਲ ਨੰ", "বিল নং")
        w("S NO.", "क्र.सं.", "अ.क्र.", "வ.எண்", "ক্র.নং", "క్ర.సం", "ಕ್ರ.ಸಂ", "ક્રમ", "କ୍ର.ସଂ", "ਲੜੀ ਨੰ", "ক্ৰ.নং")
        w("S.NO", "क्र.सं.", "अ.क्र.", "வ.எண்", "ক্র.নং", "క్ర.సం", "ಕ್ರ.ಸಂ", "ક્રમ", "କ୍ର.ସଂ", "ਲੜੀ ਨੰ", "ক্ৰ.নং")
        w("SR.NO", "क्र.सं.", "अ.क्र.", "வ.எண்", "ক্র.নং", "క్ర.సం", "ಕ್ರ.ಸಂ", "ક્રમ", "କ୍ର.ସଂ", "ਲੜੀ ਨੰ", "ক্ৰ.নং")
        w("DATE", "दिनांक", "दिनांक", "தேதி", "তারিখ", "తేదీ", "ದಿನಾಂಕ", "તારીખ", "ତାରିଖ", "ਮਿਤੀ", "তাৰিখ")
        w("TIME", "समय", "वेळ", "நேரம்", "সময়", "సమయం", "ಸಮಯ", "સમય", "ସମୟ", "ਸਮਾਂ", "সময়")
        w("TO", "से", "ते", "முதல்", "থেকে", "నుండి", "ರಿಂದ", "થી", "ରୁ", "ਤੋਂ", "পৰা")

        // ---- Customer -----------------------------------------------------------
        w("CUSTOMER", "ग्राहक", "ग्राहक", "வாடிக்கையாளர்", "ক্রেতা", "కస్టమర్", "ಗ್ರಾಹಕ", "ગ્રાહક", "ଗ୍ରାହକ", "ਗਾਹਕ", "গ্ৰাহক")
        w("MOBILE", "मोबाइल", "मोबाईल", "கைபேசி", "মোবাইল", "మొబైల్", "ಮೊಬೈಲ್", "મોબાઇલ", "ମୋବାଇଲ", "ਮੋਬਾਈਲ", "মোবাইল")
        w("ADDRESS", "पता", "पत्ता", "முகவரி", "ঠিকানা", "చిరునామా", "ವಿಳಾಸ", "સરનામું", "ଠିକଣା", "ਪਤਾ", "ঠিকনা")
        w("CUSTOMER ID", "ग्राहक क्रमांक", "ग्राहक क्रमांक", "வாடிக்கையாளர் எண்", "ক্রেতা নং", "కస్టమర్ నం", "ಗ್ರಾಹಕ ಸಂ", "ગ્રાહક નં", "ଗ୍ରାହକ ନଂ", "ਗਾਹਕ ਨੰ", "গ্ৰাহক নং")
        w("C.ID", "ग्राहक क्रमांक", "ग्राहक क्रमांक", "வாடிக்கையாளர் எண்", "ক্রেতা নং", "కస్టమర్ నం", "ಗ್ರಾಹಕ ಸಂ", "ગ્રાહક નં", "ଗ୍ରାହକ ନଂ", "ਗਾਹਕ ਨੰ", "গ্ৰাহক নং")
        w("CID", "ग्राहक क्रमांक", "ग्राहक क्रमांक", "வாடிக்கையாளர் எண்", "ক্রেতা নং", "కస్టమర్ నం", "ಗ್ರಾಹಕ ಸಂ", "ગ્રાહક નં", "ଗ୍ରାହକ ନଂ", "ਗਾਹਕ ਨੰ", "গ্ৰাহক নং")
        w("C.NAME", "ग्राहक नाम", "ग्राहक नाव", "வாடிக்கையாளர் பெயர்", "ক্রেতার নাম", "కస్టమర్ పేరు", "ಗ್ರಾಹಕ ಹೆಸರು", "ગ્રાહક નામ", "ଗ୍ରାହକ ନାମ", "ਗਾਹਕ ਨਾਮ", "গ্ৰাহকৰ নাম")

        // ---- Tax, discount and the totals block ---------------------------------
        w("TAX", "कर", "कर", "வரி", "কর", "పన్ను", "ತೆರಿಗೆ", "કર", "କର", "ਟੈਕਸ", "কৰ")
        w("TAX%", "कर%", "कर%", "வரி%", "কর%", "పన్ను%", "ತೆರಿಗೆ%", "કર%", "କର%", "ਟੈਕਸ%", "কৰ%")
        w("B.AMT", "आधार राशि", "आधार रक्कम", "அடிப்படைத் தொகை", "ভিত্তি টাকা", "ఆధార సొమ్ము", "ಮೂಲ ಮೊತ್ತ", "આધાર રકમ", "ମୂଳ ରାଶି", "ਆਧਾਰ ਰਕਮ", "ভিত্তি টকা")
        w("DISCOUNT", "छूट", "सवलत", "தள்ளுபடி", "ছাড়", "తగ్గింపు", "ರಿಯಾಯಿತಿ", "ડિસ્કાઉન્ટ", "ରିହାତି", "ਛੋਟ", "ৰেহাই")
        w("DISC", "छूट", "सवलत", "தள்ளுபடி", "ছাড়", "తగ్గింపు", "ರಿಯಾಯಿತಿ", "ડિસ્કાઉન્ટ", "ରିହାତି", "ਛੋਟ", "ৰেহাই")
        w("DISC.", "छूट", "सवलत", "தள்ளுபடி", "ছাড়", "తగ్గింపు", "ರಿಯಾಯಿತಿ", "ડિસ્કાઉન્ટ", "ରିହାତି", "ਛੋਟ", "ৰেহাই")
        w("NET AMT", "शुद्ध राशि", "निव्वळ रक्कम", "நிகரத் தொகை", "নিট টাকা", "నికర సొమ్ము", "ನಿವ್ವಳ ಮೊತ್ತ", "ચોખ્ખી રકમ", "ନିଟ ରାଶି", "ਸ਼ੁੱਧ ਰਕਮ", "নিট টকা")
        w("ROUND OFF", "पूर्णांकन", "पूर्णांकन", "முழுமையாக்கல்", "রাউন্ড অফ", "రౌండ్ ఆఫ్", "ರೌಂಡ್ ಆಫ್", "રાઉન્ડ ઓફ", "ରାଉଣ୍ଡ ଅଫ", "ਰਾਊਂਡ ਆਫ", "ৰাউণ্ড অফ")
        w("ROUNDED OFF", "पूर्णांकन", "पूर्णांकन", "முழுமையாக்கல்", "রাউন্ড অফ", "రౌండ్ ఆఫ్", "ರೌಂಡ್ ಆಫ್", "રાઉન્ડ ઓફ", "ରାଉଣ୍ଡ ଅଫ", "ਰਾਊਂਡ ਆਫ", "ৰাউণ্ড অফ")
        w("ROUND OFF AMOUNT", "पूर्णांकन राशि", "पूर्णांकन रक्कम", "முழுமையாக்கல் தொகை", "রাউন্ড অফ টাকা", "రౌండ్ ఆఫ్ సొమ్ము", "ರೌಂಡ್ ಆಫ್ ಮೊತ್ತ", "રાઉન્ડ ઓફ રકમ", "ରାଉଣ୍ଡ ଅଫ ରାଶି", "ਰਾਊਂਡ ਆਫ ਰਕਮ", "ৰাউণ্ড অফ টকা")
        w("SERVICE CHARGE", "सेवा शुल्क", "सेवा शुल्क", "சேவைக் கட்டணம்", "সার্ভিস চার্জ", "సేవా రుసుము", "ಸೇವಾ ಶುಲ್ಕ", "સેવા ચાર્જ", "ସେବା ଶୁଳ୍କ", "ਸੇਵਾ ਚਾਰਜ", "সেৱা মাচুল")
        w("GROSS", "सकल", "स्थूल", "மொத்தம்", "সমষ্টি", "స్థూలం", "ಒಟ್ಟಾರೆ", "કુલ", "ସମୁଦାୟ", "ਕੁੱਲ", "সমষ্টি")
        w("REFUND", "वापसी", "परतावा", "திரும்பப் பணம்", "ফেরত", "వాపసు", "ಮರುಪಾವತಿ", "રિફંડ", "ଫେରସ୍ତ", "ਵਾਪਸੀ", "ঘূৰাই দিয়া")
        w("PROFIT", "लाभ", "नफा", "லாபம்", "লাভ", "లాభం", "ಲಾಭ", "નફો", "ଲାଭ", "ਲਾਭ", "লাভ")
        w("LOSS", "हानि", "तोटा", "நஷ்டம்", "ক্ষতি", "నష్టం", "ನಷ್ಟ", "નુકસાન", "କ୍ଷତି", "ਘਾਟਾ", "ক্ষতি")
        w("PROFIT-LOSS", "लाभ-हानि", "नफा-तोटा", "லாப-நஷ்ட", "লাভ-ক্ষতি", "లాభ-నష్ట", "ಲಾಭ-ನಷ್ಟ", "નફો-નુકસાન", "ଲାଭ-କ୍ଷତି", "ਲਾਭ-ਘਾਟਾ", "লাভ-ক্ষতি")

        // ---- Money in and money owed -------------------------------------------
        w("PAY MODE", "भुगतान", "पेमेंट", "கட்டண முறை", "পেমেন্ট", "చెల్లింపు", "ಪಾವತಿ", "ચુકવણી", "ଦେୟ", "ਭੁਗਤਾਨ", "পৰিশোধ")
        w("PAYMENT", "भुगतान", "पेमेंट", "கட்டணம்", "পেমেন্ট", "చెల్లింపు", "ಪಾವತಿ", "ચુકવણી", "ଦେୟ", "ਭੁਗਤਾਨ", "পৰিশোধ")
        w("CASH", "नकद", "रोख", "ரொக்கம்", "নগদ", "నగదు", "ನಗದು", "રોકડ", "ନଗଦ", "ਨਕਦ", "নগদ")
        w("CARD", "कार्ड", "कार्ड", "கார்டு", "কার্ড", "కార్డ్", "ಕಾರ್ಡ್", "કાર્ડ", "କାର୍ଡ", "ਕਾਰਡ", "কাৰ্ড")
        w("CREDIT", "उधार", "उधार", "கடன்", "বাকি", "అరువు", "ಸಾಲ", "ઉધાર", "ଉଧାର", "ਉਧਾਰ", "ধাৰ")
        w("SALE", "बिक्री", "विक्री", "விற்பனை", "বিক্রয়", "అమ్మకం", "ಮಾರಾಟ", "વેચાણ", "ବିକ୍ରୟ", "ਵਿਕਰੀ", "বিক্ৰী")
        w("SALES", "बिक्री", "विक्री", "விற்பனை", "বিক্রয়", "అమ్మకాలు", "ಮಾರಾಟ", "વેચાણ", "ବିକ୍ରୟ", "ਵਿਕਰੀ", "বিক্ৰী")
        w("CASH RECEIVED", "प्राप्त नकद", "मिळालेली रोख", "பெறப்பட்ட ரொக்கம்", "প্রাপ্ত নগদ", "అందిన నగదు", "ಸ್ವೀಕರಿಸಿದ ನಗದು", "મળેલ રોકડ", "ପ୍ରାପ୍ତ ନଗଦ", "ਪ੍ਰਾਪਤ ਨਕਦ", "পোৱা নগদ")
        w("CHANGE DUE", "वापस राशि", "परत रक्कम", "மீதித் தொகை", "ফেরত টাকা", "తిరిగి ఇచ్చే సొమ్ము", "ಹಿಂತಿರುಗಿಸುವ ಮೊತ್ತ", "પરત રકમ", "ଫେରସ୍ତ ରାଶି", "ਵਾਪਸ ਰਕਮ", "ঘূৰাই দিয়া টকা")
        w("OUTSTANDING", "बकाया", "थकबाकी", "நிலுவை", "বকেয়া", "బకాయి", "ಬಾಕಿ", "બાકી", "ବକେୟା", "ਬਕਾਇਆ", "বাকী")
        w("BALANCE", "शेष", "शिल्लक", "இருப்பு", "ব্যালেন্স", "బ్యాలెన్స్", "ಬಾಕಿ", "બાકી", "ବାକି", "ਬਕਾਇਆ", "বেলেন্স")
        w("TOTAL BALANCE", "कुल शेष", "एकूण शिल्लक", "மொத்த இருப்பு", "মোট ব্যালেন্স", "మొత్తం బ్యాలెన్స్", "ಒಟ್ಟು ಬಾಕಿ", "કુલ બાકી", "ମୋଟ ବାକି", "ਕੁੱਲ ਬਕਾਇਆ", "মুঠ বেলেন্স")
        w("PREVI BALANCE", "पिछला शेष", "मागील शिल्लक", "முந்தைய இருப்பு", "আগের ব্যালেন্স", "మునుపటి బ్యాలెన్స్", "ಹಿಂದಿನ ಬಾಕಿ", "પાછલી બાકી", "ପୂର୍ବ ବାକି", "ਪਿਛਲਾ ਬਕਾਇਆ", "আগৰ বেলেন্স")
        w("PREV. BALANCE", "पिछला शेष", "मागील शिल्लक", "முந்தைய இருப்பு", "আগের ব্যালেন্স", "మునుపటి బ్యాలెన్స్", "ಹಿಂದಿನ ಬಾಕಿ", "પાછલી બાકી", "ପୂର୍ବ ବାକି", "ਪਿਛਲਾ ਬਕਾਇਆ", "আগৰ বেলেন্স")
        w("OPENING BALANCE", "प्रारंभिक शेष", "प्रारंभिक शिल्लक", "தொடக்க இருப்பு", "প্রারম্ভিক ব্যালেন্স", "ప్రారంభ బ్యాలెన్స్", "ಆರಂಭಿಕ ಬಾಕಿ", "શરૂઆતની બાકી", "ପ୍ରାରମ୍ଭିକ ବାକି", "ਸ਼ੁਰੂਆਤੀ ਬਕਾਇਆ", "আৰম্ভণিৰ বেলেন্স")
        w("PAID", "भुगतान", "दिलेले", "செலுத்தியது", "পরিশোধিত", "చెల్లించినది", "ಪಾವತಿಸಿದ", "ચૂકવેલ", "ପ୍ରଦତ୍ତ", "ਅਦਾ ਕੀਤਾ", "পৰিশোধিত")
        w("DUE", "देय", "येणे", "நிலுவை", "বাকি", "బకాయి", "ಬಾಕಿ", "બાકી", "ଦେୟ", "ਬਕਾਇਆ", "বাকী")

        // ---- Slip captions and the foot of the page -----------------------------
        w("SALE RETURN", "बिक्री वापसी", "विक्री परतावा", "விற்பனை திரும்பல்", "বিক্রয় ফেরত", "అమ్మకం వాపసు", "ಮಾರಾಟ ಮರಳಿಕೆ", "વેચાણ પરત", "ବିକ୍ରୟ ଫେରସ୍ତ", "ਵਿਕਰੀ ਵਾਪਸੀ", "বিক্ৰী ঘূৰাই")
        w("RETURN", "वापसी", "परतावा", "திரும்பல்", "ফেরত", "వాపసు", "ಮರಳಿಕೆ", "પરત", "ଫେରସ୍ତ", "ਵਾਪਸੀ", "ঘূৰাই দিয়া")
        w("RETURNS", "वापसी", "परतावा", "திரும்பல்கள்", "ফেরত", "వాపసులు", "ಮರಳಿಕೆಗಳು", "પરત", "ଫେରସ୍ତ", "ਵਾਪਸੀਆਂ", "ঘূৰাই দিয়া")
        w("RETURN(S)", "वापसी", "परतावा", "திரும்பல்கள்", "ফেরত", "వాపసులు", "ಮರಳಿಕೆಗಳು", "પરત", "ଫେରସ୍ତ", "ਵਾਪਸੀਆਂ", "ঘূৰাই দিয়া")
        w("RETURNED", "वापस", "परत", "திரும்பிய", "ফেরত", "వాపసు", "ಮರಳಿದ", "પરત", "ଫେରସ୍ତ", "ਵਾਪਸ", "ঘূৰাই দিয়া")
        w("RETURN NO", "वापसी नं", "परतावा क्र", "திரும்பல் எண்", "ফেরত নং", "వాపసు నం", "ಮರಳಿಕೆ ಸಂ", "પરત નં", "ଫେରସ୍ତ ନଂ", "ਵਾਪਸੀ ਨੰ", "ঘূৰাই নং")
        w("AGAINST BILL", "बिल के विरुद्ध", "बिलाविरुद्ध", "பில்லுக்கு எதிராக", "বিলের বিপরীতে", "బిల్లుకు వ్యతిరేకంగా", "ಬಿಲ್ ವಿರುದ್ಧ", "બિલ સામે", "ବିଲ ବିପକ୍ଷରେ", "ਬਿੱਲ ਵਿਰੁੱਧ", "বিলৰ বিপৰীতে")
        w("CUSTOMER LEDGER", "ग्राहक खाता", "ग्राहक खातेवही", "வாடிக்கையாளர் கணக்கு", "ক্রেতার খতিয়ান", "కస్టమర్ ఖాతా", "ಗ್ರಾಹಕ ಖಾತೆ", "ગ્રાહક ખાતું", "ଗ୍ରାହକ ଖାତା", "ਗਾਹਕ ਖਾਤਾ", "গ্ৰাহক খতিয়ান")
        w("CUSTOMER BILL", "ग्राहक बिल", "ग्राहक बिल", "வாடிக்கையாளர் பில்", "ক্রেতার বিল", "కస్టమర్ బిల్లు", "ಗ್ರಾಹಕ ಬಿಲ್", "ગ્રાહક બિલ", "ଗ୍ରାହକ ବିଲ", "ਗਾਹਕ ਬਿੱਲ", "গ্ৰাহকৰ বিল")
        w("SUMMARY", "सारांश", "सारांश", "சுருக்கம்", "সারসংক্ষেপ", "సారాంశం", "ಸಾರಾಂಶ", "સારાંશ", "ସାରାଂଶ", "ਸਾਰ", "সাৰাংশ")
        w("CREATED BY", "बनाया", "तयार केले", "உருவாக்கியவர்", "তৈরি করেছেন", "రూపొందించినది", "ರಚಿಸಿದವರು", "બનાવનાર", "ପ୍ରସ୍ତୁତକର୍ତ୍ତା", "ਬਣਾਇਆ", "প্ৰস্তুতকৰ্তা")
        w("PRINTED BY", "मुद्रित द्वारा", "छापले", "அச்சிட்டவர்", "মুদ্রণ করেছেন", "ముద్రించినది", "ಮುದ್ರಿಸಿದವರು", "છાપનાર", "ମୁଦ୍ରଣକର୍ତ୍ତା", "ਛਾਪਿਆ", "মুদ্ৰণকৰ্তা")
        w("PRINTED ON", "मुद्रण दिनांक", "छपाई दिनांक", "அச்சிட்ட தேதி", "মুদ্রণের তারিখ", "ముద్రణ తేదీ", "ಮುದ್ರಣ ದಿನಾಂಕ", "છાપ્યાની તારીખ", "ମୁଦ୍ରଣ ତାରିଖ", "ਛਪਾਈ ਮਿਤੀ", "মুদ্ৰণৰ তাৰিখ")
        w("RETURNED BY", "वापस किया", "परत केले", "திருப்பியவர்", "ফেরত দিয়েছেন", "వాపసు చేసినది", "ಮರಳಿಸಿದವರು", "પરત કરનાર", "ଫେରସ୍ତକର୍ତ୍ତା", "ਵਾਪਸ ਕੀਤਾ", "ঘূৰাই দিওঁতা")

        // ---- Report vocabulary --------------------------------------------------
        w("REPORT", "रिपोर्ट", "अहवाल", "அறிக்கை", "রিপোর্ট", "నివేదిక", "ವರದಿ", "રિપોર્ટ", "ରିପୋର୍ଟ", "ਰਿਪੋਰਟ", "প্ৰতিবেদন")
        w("RPT", "रिपोर्ट", "अहवाल", "அறிக்கை", "রিপোর্ট", "నివేదిక", "ವರದಿ", "રિપોર્ટ", "ରିପୋର୍ଟ", "ਰਿਪੋਰਟ", "প্ৰতিবেদন")
        w("BILL-WISE", "बिल-वार", "बिलनिहाय", "பில் வாரியான", "বিল-ভিত্তিক", "బిల్లు వారీ", "ಬಿಲ್‌ವಾರು", "બિલ-વાર", "ବିଲ ଭିତ୍ତିକ", "ਬਿੱਲ-ਵਾਰ", "বিল-ভিত্তিক")
        w("ITEM-BILL", "वस्तु-बिल", "वस्तू-बिल", "பொருள்-பில்", "পণ্য-বিল", "వస్తువు-బిల్లు", "ವಸ್ತು-ಬಿಲ್", "વસ્તુ-બિલ", "ସାମଗ୍ରୀ-ବିଲ", "ਵਸਤੂ-ਬਿੱਲ", "সামগ্ৰী-বিল")
        w("OPERATOR-WISE", "संचालक-वार", "चालकनिहाय", "இயக்குநர் வாரியான", "অপারেটর-ভিত্তিক", "ఆపరేటర్ వారీ", "ನಿರ್ವಾಹಕವಾರು", "ઓપરેટર-વાર", "ଅପରେଟର ଭିତ୍ତିକ", "ਓਪਰੇਟਰ-ਵਾਰ", "অপাৰেটৰ-ভিত্তিক")
        w("PAYMENT-WISE", "भुगतान-वार", "पेमेंटनिहाय", "கட்டண வாரியான", "পেমেন্ট-ভিত্তিক", "చెల్లింపు వారీ", "ಪಾವತಿವಾರು", "ચુકવણી-વાર", "ଦେୟ ଭିତ୍ତିକ", "ਭੁਗਤਾਨ-ਵਾਰ", "পৰিশোধ-ভিত্তিক")
        w("UDF-WISE", "UDF-वार", "UDFनिहाय", "UDF வாரியான", "UDF-ভিত্তিক", "UDF వారీ", "UDFವಾರು", "UDF-વાર", "UDF ଭିତ୍ତିକ", "UDF-ਵਾਰ", "UDF-ভিত্তিক")
        w("CUSTOMER ITEMWISE", "ग्राहक वस्तु-वार", "ग्राहक वस्तूनिहाय", "வாடிக்கையாளர் பொருள் வாரியான", "ক্রেতা পণ্য-ভিত্তিক", "కస్టమర్ వస్తువు వారీ", "ಗ್ರಾಹಕ ವಸ್ತುವಾರು", "ગ્રાહક વસ્તુ-વાર", "ଗ୍ରାହକ ସାମଗ୍ରୀ ଭିତ୍ତିକ", "ਗਾਹਕ ਵਸਤੂ-ਵਾਰ", "গ্ৰাহক সামগ্ৰী-ভিত্তিক")
        w("STOCK", "स्टॉक", "स्टॉक", "சரக்கு", "স্টক", "స్టాక్", "ದಾಸ್ತಾನು", "સ્ટોક", "ଷ୍ଟକ", "ਸਟਾਕ", "ষ্টক")
        w("DEPARTMENT", "विभाग", "विभाग", "துறை", "বিভাগ", "విభాగం", "ವಿಭಾಗ", "વિભાગ", "ବିଭାଗ", "ਵਿਭਾਗ", "বিভাগ")
        w("DEPARTMENT(S)", "विभाग", "विभाग", "துறைகள்", "বিভাগ", "విభాగాలు", "ವಿಭಾಗಗಳು", "વિભાગો", "ବିଭାଗ", "ਵਿਭਾਗ", "বিভাগ")
        w("DEPT NAME", "विभाग का नाम", "विभागाचे नाव", "துறை பெயர்", "বিভাগের নাম", "విభాగం పేరు", "ವಿಭಾಗದ ಹೆಸರು", "વિભાગનું નામ", "ବିଭାଗର ନାମ", "ਵਿਭਾਗ ਦਾ ਨਾਮ", "বিভাগৰ নাম")
        w("DUPLICATE", "द्वितीय प्रति", "दुय्यम प्रत", "நகல்", "নকল", "నకలు", "ನಕಲು", "ડુપ્લિકેટ", "ନକଲ", "ਡੁਪਲੀਕੇਟ", "নকল")
        w("RECEIPT", "रसीद", "पावती", "ரசீது", "রসিদ", "రసీదు", "ರಸೀದಿ", "રસીદ", "ରସିଦ", "ਰਸੀਦ", "ৰচিদ")
        w("CANCEL", "रद्द", "रद्द", "ரத்து", "বাতিল", "రద్దు", "ರದ್ದು", "રદ", "ବାତିଲ", "ਰੱਦ", "বাতিল")
        w("VOID", "रद्द", "रद्द", "ரத்து", "বাতিল", "రద్దు", "ರದ್ದು", "રદ", "ବାତିଲ", "ਰੱਦ", "বাতিল")
        w("OPERATOR", "संचालक", "चालक", "இயக்குநர்", "অপারেটর", "ఆపరేటర్", "ನಿರ್ವಾಹಕ", "ઓપરેટર", "ଅପରେଟର", "ਓਪਰੇਟਰ", "অপাৰেটৰ")
        w("OPERATOR(S)", "संचालक", "चालक", "இயக்குநர்கள்", "অপারেটর", "ఆపరేటర్లు", "ನಿರ್ವಾಹಕರು", "ઓપરેટરો", "ଅପରେଟର", "ਓਪਰੇਟਰ", "অপাৰেটৰ")
        w("BILLED", "बिल किया", "बिल केलेले", "பில் செய்யப்பட்ட", "বিলকৃত", "బిల్లు చేసిన", "ಬಿಲ್ ಮಾಡಿದ", "બિલ કરેલ", "ବିଲ ହୋଇଥିବା", "ਬਿੱਲ ਕੀਤਾ", "বিল কৰা")
        w("UNSOLD", "अनबिका", "न विकलेले", "விற்கப்படாத", "অবিক্রীত", "అమ్మని", "ಮಾರಾಟವಾಗದ", "વેચાયા વગરની", "ଅବିକ୍ରିତ", "ਅਣਵਿਕੇ", "অবিক্ৰীত")
        w("PRODUCTS", "उत्पाद", "उत्पादने", "பொருட்கள்", "পণ্য", "ఉత్పత్తులు", "ಉತ್ಪನ್ನಗಳು", "ઉત્પાદનો", "ଉତ୍ପାଦ", "ਉਤਪਾਦ", "সামগ্ৰী")
        w("PRODUCT(S)", "उत्पाद", "उत्पादने", "பொருட்கள்", "পণ্য", "ఉత్పత్తులు", "ಉತ್ಪನ್ನಗಳು", "ઉત્પાદનો", "ଉତ୍ପାଦ", "ਉਤਪਾਦ", "সামগ্ৰী")
        w("CALCULATOR", "कैलकुलेटर", "कॅल्क्युलेटर", "கணிப்பான்", "ক্যালকুলেটর", "కాలిక్యులేటర్", "ಕ್ಯಾಲ್ಕುಲೇಟರ್", "કેલ્ક્યુલેટર", "କାଲକୁଲେଟର", "ਕੈਲਕੁਲੇਟਰ", "কেলকুলেটৰ")
        w("SECTION", "सेक्शन", "विभाग", "பிரிவு", "বিভাগ", "విభాగం", "ವಿಭಾಗ", "વિભાગ", "ବିଭାଗ", "ਸੈਕਸ਼ਨ", "বিভাগ")
        w("CANCELLED", "रद्द", "रद्द", "ரத்து", "বাতিল", "రద్దు", "ರದ್ದು", "રદ", "ବାତିଲ", "ਰੱਦ", "বাতিল")
        w("NOTE", "नोट", "नोंद", "குறிப்பு", "নোট", "గమనిక", "ಟಿಪ್ಪಣಿ", "નોંધ", "ଟିପ୍ପଣୀ", "ਨੋਟ", "টোকা")
        w("TABLE", "मेज", "टेबल", "மேசை", "টেবিল", "టేబుల్", "ಟೇಬಲ್", "ટેબલ", "ଟେବୁଲ", "ਟੇਬਲ", "টেবুল")
        w("SLAB(S)", "स्लैब", "स्लॅब", "அடுக்கு", "স্ল্যাব", "స్లాబ్", "ಸ್ಲ್ಯಾಬ್", "સ્લેબ", "ସ୍ଲାବ", "ਸਲੈਬ", "স্লেব")
        w("GROUP(S)", "समूह", "गट", "குழு", "গোষ্ঠী", "సమూహం", "ಗುಂಪು", "જૂથ", "ଗୋଷ୍ଠୀ", "ਸਮੂਹ", "গোট")
        w("AVERAGE", "औसत", "सरासरी", "சராசரி", "গড়", "సగటు", "ಸರಾಸರಿ", "સરેરાશ", "ହାରାହାରି", "ਔਸਤ", "গড়")
        w("LOW", "कम", "कमी", "குறைந்த", "কম", "తక్కువ", "ಕಡಿಮೆ", "ઓછો", "କମ", "ਘੱਟ", "কম")
        w("OUT OF STOCK", "स्टॉक समाप्त", "स्टॉक संपला", "சரக்கு இல்லை", "স্টক শেষ", "స్టాక్ లేదు", "ದಾಸ್ತಾನು ಇಲ್ಲ", "સ્ટોક ખતમ", "ଷ୍ଟକ ଶେଷ", "ਸਟਾਕ ਖਤਮ", "ষ্টক শেষ")
        w("NEW", "नया", "नवीन", "புதிய", "নতুন", "కొత్త", "ಹೊಸ", "નવા", "ନୂଆ", "ਨਵੇਂ", "নতুন")
        w("MEMBERS", "सदस्य", "सदस्य", "உறுப்பினர்கள்", "সদস্য", "సభ్యులు", "ಸದಸ್ಯರು", "સભ્યો", "ସଦସ୍ୟ", "ਮੈਂਬਰ", "সদস্য")
        w("REPEAT", "दोहराव", "पुनरावृत्ती", "மீண்டும்", "পুনরাবৃত্তি", "పునరావృతం", "ಪುನರಾವರ್ತನೆ", "પુનરાવર્તન", "ପୁନରାବୃତ୍ତି", "ਦੁਹਰਾਓ", "পুনৰাবৃত্তি")
        w("NO. OF TIMES", "बार", "वेळा", "முறை", "বার", "సార్లు", "ಬಾರಿ", "વખત", "ଥର", "ਵਾਰ", "বাৰ")
        w("S.QTY", "बिक्री मात्रा", "विक्री प्रमाण", "விற்ற அளவு", "বিক্রীত পরিমাণ", "అమ్మిన పరిమాణం", "ಮಾರಾಟ ಪ್ರಮಾಣ", "વેચાણ જથ્થો", "ବିକ୍ରୟ ପରିମାଣ", "ਵਿਕਰੀ ਮਾਤਰਾ", "বিক্ৰীত পৰিমাণ")
        w("OP.CODE", "संचालक कोड", "चालक कोड", "இயக்குநர் குறியீடு", "অপারেটর কোড", "ఆపరేటర్ కోడ్", "ನಿರ್ವಾಹಕ ಕೋಡ್", "ઓપરેટર કોડ", "ଅପରେଟର କୋଡ", "ਓਪਰੇਟਰ ਕੋਡ", "অপাৰেটৰ ক'ড")
        w("TOT.RCPTS", "कुल रसीदें", "एकूण पावत्या", "மொத்த ரசீதுகள்", "মোট রসিদ", "మొత్తం రసీదులు", "ಒಟ್ಟು ರಸೀದಿಗಳು", "કુલ રસીદો", "ମୋଟ ରସିଦ", "ਕੁੱਲ ਰਸੀਦਾਂ", "মুঠ ৰচিদ")
        w("PUR.STOCK", "खरीद स्टॉक", "खरेदी स्टॉक", "வாங்கிய சரக்கு", "ক্রয় স্টক", "కొనుగోలు స్టాక్", "ಖರೀದಿ ದಾಸ್ತಾನು", "ખરીદ સ્ટોક", "କ୍ରୟ ଷ୍ଟକ", "ਖਰੀਦ ਸਟਾਕ", "ক্ৰয় ষ্টক")
        w("SLD.STK.", "बिका स्टॉक", "विकलेला स्टॉक", "விற்ற சரக்கு", "বিক্রীত স্টক", "అమ్మిన స్టాక్", "ಮಾರಿದ ದಾಸ್ತಾನು", "વેચેલ સ્ટોક", "ବିକ୍ରିତ ଷ୍ଟକ", "ਵਿਕਿਆ ਸਟਾਕ", "বিক্ৰীত ষ্টক")
        w("C.STOCK", "शेष स्टॉक", "शिल्लक स्टॉक", "மீதி சரக்கு", "অবশিষ্ট স্টক", "మిగిలిన స్టాక్", "ಉಳಿದ ದಾಸ್ತಾನು", "બાકી સ્ટોક", "ଅବଶିଷ୍ଟ ଷ୍ଟକ", "ਬਾਕੀ ਸਟਾਕ", "অৱশিষ্ট ষ্টক")
        w("DAY", "दिन", "दिवस", "நாள்", "দিন", "రోజు", "ದಿನ", "દિવસ", "ଦିନ", "ਦਿਨ", "দিন")
        w("WEEK", "सप्ताह", "आठवडा", "வாரம்", "সপ্তাহ", "వారం", "ವಾರ", "અઠવાડિયું", "ସପ୍ତାହ", "ਹਫ਼ਤਾ", "সপ্তাহ")
        w("MONTH", "माह", "महिना", "மாதம்", "মাস", "నెల", "ತಿಂಗಳು", "મહિનો", "ମାସ", "ਮਹੀਨਾ", "মাহ")
        w("YEAR", "वर्ष", "वर्ष", "ஆண்டு", "বছর", "సంవత్సరం", "ವರ್ಷ", "વર્ષ", "ବର୍ଷ", "ਸਾਲ", "বছৰ")

        // ---- The lines a report prints when its period turned up nothing --------
        w("NOTHING IN THIS PERIOD.", "इस अवधि में कुछ नहीं।", "या कालावधीत काहीही नाही.", "இந்தக் காலகட்டத்தில் எதுவும் இல்லை.", "এই সময়ে কিছু নেই।", "ఈ కాలంలో ఏమీ లేదు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಏನೂ ಇಲ್ಲ.", "આ સમયગાળામાં કંઈ નથી.", "ଏହି ଅବଧିରେ କିଛି ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੁਝ ਨਹੀਂ।", "এই সময়ছোৱাত একো নাই।")
        w("NO BILLS IN THIS PERIOD.", "इस अवधि में कोई बिल नहीं।", "या कालावधीत बिल नाही.", "இந்தக் காலகட்டத்தில் பில் இல்லை.", "এই সময়ে কোনো বিল নেই।", "ఈ కాలంలో బిల్లులు లేవు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಬಿಲ್ ಇಲ್ಲ.", "આ સમયગાળામાં કોઈ બિલ નથી.", "ଏହି ଅବଧିରେ କୌଣସି ବିଲ ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਬਿੱਲ ਨਹੀਂ।", "এই সময়ছোৱাত কোনো বিল নাই।")
        w("NO BILLS IN THIS RANGE.", "इस सीमा में कोई बिल नहीं।", "या मर्यादेत बिल नाही.", "இந்த வரம்பில் பில் இல்லை.", "এই সীমার মধ্যে কোনো বিল নেই।", "ఈ పరిధిలో బిల్లులు లేవు.", "ಈ ವ್ಯಾಪ್ತಿಯಲ್ಲಿ ಬಿಲ್ ಇಲ್ಲ.", "આ શ્રેણીમાં કોઈ બિલ નથી.", "ଏହି ପରିସରରେ କୌଣସି ବିଲ ନାହିଁ।", "ਇਸ ਹੱਦ ਵਿੱਚ ਕੋਈ ਬਿੱਲ ਨਹੀਂ।", "এই সীমাৰ ভিতৰত কোনো বিল নাই।")
        w("NOTHING WAS SOLD IN THIS PERIOD.", "इस अवधि में कुछ नहीं बिका।", "या कालावधीत काहीही विकले नाही.", "இந்தக் காலகட்டத்தில் எதுவும் விற்கப்படவில்லை.", "এই সময়ে কিছু বিক্রি হয়নি।", "ఈ కాలంలో ఏమీ అమ్ముడుపోలేదు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಏನೂ ಮಾರಾಟವಾಗಿಲ್ಲ.", "આ સમયગાળામાં કંઈ વેચાયું નથી.", "ଏହି ଅବଧିରେ କିଛି ବିକ୍ରି ହୋଇନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੁਝ ਨਹੀਂ ਵਿਕਿਆ।", "এই সময়ছোৱাত একো বিক্ৰী হোৱা নাই।")
        w("EVERYTHING SOLD IN THIS PERIOD.", "इस अवधि में सब कुछ बिक गया।", "या कालावधीत सर्व विकले गेले.", "இந்தக் காலகட்டத்தில் அனைத்தும் விற்றுவிட்டன.", "এই সময়ে সবকিছু বিক্রি হয়েছে।", "ఈ కాలంలో అన్నీ అమ్ముడుపోయాయి.", "ಈ ಅವಧಿಯಲ್ಲಿ ಎಲ್ಲವೂ ಮಾರಾಟವಾಗಿದೆ.", "આ સમયગાળામાં બધું વેચાયું.", "ଏହି ଅବଧିରେ ସବୁ ବିକ୍ରି ହୋଇଛି।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਸਭ ਵਿਕ ਗਿਆ।", "এই সময়ছোৱাত সকলো বিক্ৰী হ'ল।")
        w("NO RETURNS IN THIS PERIOD.", "इस अवधि में कोई वापसी नहीं।", "या कालावधीत परतावा नाही.", "இந்தக் காலகட்டத்தில் திரும்பல் இல்லை.", "এই সময়ে কোনো ফেরত নেই।", "ఈ కాలంలో వాపసులు లేవు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಮರಳಿಕೆ ಇಲ್ಲ.", "આ સમયગાળામાં કોઈ પરત નથી.", "ଏହି ଅବଧିରେ କୌଣସି ଫେରସ୍ତ ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਵਾਪਸੀ ਨਹੀਂ।", "এই সময়ছোৱাত কোনো ঘূৰাই দিয়া নাই।")
        w("NO PAYMENTS IN THIS PERIOD.", "इस अवधि में कोई भुगतान नहीं।", "या कालावधीत पेमेंट नाही.", "இந்தக் காலகட்டத்தில் கட்டணம் இல்லை.", "এই সময়ে কোনো পেমেন্ট নেই।", "ఈ కాలంలో చెల్లింపులు లేవు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಪಾವತಿ ಇಲ್ಲ.", "આ સમયગાળામાં કોઈ ચુકવણી નથી.", "ଏହି ଅବଧିରେ କୌଣସି ଦେୟ ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਭੁਗਤਾਨ ਨਹੀਂ।", "এই সময়ছোৱাত কোনো পৰিশোধ নাই।")
        w("NO CUSTOMER PAYMENTS IN THIS PERIOD.", "इस अवधि में ग्राहक भुगतान नहीं।", "या कालावधीत ग्राहक पेमेंट नाही.", "இந்தக் காலகட்டத்தில் வாடிக்கையாளர் கட்டணம் இல்லை.", "এই সময়ে ক্রেতার কোনো পেমেন্ট নেই।", "ఈ కాలంలో కస్టమర్ చెల్లింపులు లేవు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಗ್ರಾಹಕ ಪಾವತಿ ಇಲ್ಲ.", "આ સમયગાળામાં ગ્રાહક ચુકવણી નથી.", "ଏହି ଅବଧିରେ ଗ୍ରାହକ ଦେୟ ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਗਾਹਕ ਭੁਗਤਾਨ ਨਹੀਂ।", "এই সময়ছোৱাত গ্ৰাহকৰ পৰিশোধ নাই।")
        w("NO ITEMS IN THIS PERIOD.", "इस अवधि में कोई वस्तु नहीं।", "या कालावधीत वस्तू नाही.", "இந்தக் காலகட்டத்தில் பொருட்கள் இல்லை.", "এই সময়ে কোনো পণ্য নেই।", "ఈ కాలంలో వస్తువులు లేవు.", "ಈ ಅವಧಿಯಲ್ಲಿ ವಸ್ತುಗಳಿಲ್ಲ.", "આ સમયગાળામાં કોઈ વસ્તુ નથી.", "ଏହି ଅବଧିରେ କୌଣସି ସାମଗ୍ରୀ ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਵਸਤੂ ਨਹੀਂ।", "এই সময়ছোৱাত কোনো সামগ্ৰী নাই।")
        w("NO TAX WAS CHARGED IN THIS PERIOD.", "इस अवधि में कोई कर नहीं लगा।", "या कालावधीत कर आकारला नाही.", "இந்தக் காலகட்டத்தில் வரி விதிக்கப்படவில்லை.", "এই সময়ে কোনো কর নেওয়া হয়নি।", "ఈ కాలంలో పన్ను వసూలు కాలేదు.", "ಈ ಅವಧಿಯಲ್ಲಿ ತೆರಿಗೆ ವಿಧಿಸಿಲ್ಲ.", "આ સમયગાળામાં કર લેવાયો નથી.", "ଏହି ଅବଧିରେ କର ଲାଗିନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਟੈਕਸ ਨਹੀਂ ਲੱਗਿਆ।", "এই সময়ছোৱাত কোনো কৰ লোৱা হোৱা নাই।")
        w("NO BILL WAS REPRINTED IN THIS PERIOD.", "इस अवधि में कोई बिल दोबारा नहीं छपा।", "या कालावधीत बिल पुन्हा छापले नाही.", "இந்தக் காலகட்டத்தில் பில் மறுபதிப்பு இல்லை.", "এই সময়ে কোনো বিল পুনর্মুদ্রণ হয়নি।", "ఈ కాలంలో బిల్లు మళ్లీ ముద్రించలేదు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಬಿಲ್ ಮರುಮುದ್ರಣವಾಗಿಲ್ಲ.", "આ સમયગાળામાં કોઈ બિલ ફરી છપાયું નથી.", "ଏହି ଅବଧିରେ କୌଣସି ବିଲ ପୁନଃମୁଦ୍ରଣ ହୋଇନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਬਿੱਲ ਮੁੜ ਨਹੀਂ ਛਪਿਆ।", "এই সময়ছোৱাত কোনো বিল পুনৰ ছপা হোৱা নাই।")
        w("NO BILL WAS VOIDED IN THIS PERIOD.", "इस अवधि में कोई बिल रद्द नहीं हुआ।", "या कालावधीत बिल रद्द झाले नाही.", "இந்தக் காலகட்டத்தில் பில் ரத்து இல்லை.", "এই সময়ে কোনো বিল বাতিল হয়নি।", "ఈ కాలంలో బిల్లు రద్దు కాలేదు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಬಿಲ್ ರದ್ದಾಗಿಲ್ಲ.", "આ સમયગાળામાં કોઈ બિલ રદ થયું નથી.", "ଏହି ଅବଧିରେ କୌଣସି ବିଲ ବାତିଲ ହୋଇନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਬਿੱਲ ਰੱਦ ਨਹੀਂ ਹੋਇਆ।", "এই সময়ছোৱাত কোনো বিল বাতিল হোৱা নাই।")
        w("NO CANCELLED KOT ITEMS IN THIS PERIOD.", "इस अवधि में कोई रद्द KOT वस्तु नहीं।", "या कालावधीत रद्द KOT वस्तू नाही.", "இந்தக் காலகட்டத்தில் ரத்து KOT பொருட்கள் இல்லை.", "এই সময়ে বাতিল KOT পণ্য নেই।", "ఈ కాలంలో రద్దైన KOT వస్తువులు లేవు.", "ಈ ಅವಧಿಯಲ್ಲಿ ರದ್ದಾದ KOT ವಸ್ತುಗಳಿಲ್ಲ.", "આ સમયગાળામાં રદ KOT વસ્તુ નથી.", "ଏହି ଅବଧିରେ ବାତିଲ KOT ସାମଗ୍ରୀ ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਰੱਦ KOT ਵਸਤੂ ਨਹੀਂ।", "এই সময়ছোৱাত বাতিল KOT সামগ্ৰী নাই।")
        w("THIS ITEM SOLD NOTHING IN THIS PERIOD.", "इस अवधि में यह वस्तु नहीं बिकी।", "या कालावधीत ही वस्तू विकली नाही.", "இந்தக் காலகட்டத்தில் இப்பொருள் விற்கவில்லை.", "এই সময়ে এই পণ্য বিক্রি হয়নি।", "ఈ కాలంలో ఈ వస్తువు అమ్ముడుపోలేదు.", "ಈ ಅವಧಿಯಲ್ಲಿ ಈ ವಸ್ತು ಮಾರಾಟವಾಗಿಲ್ಲ.", "આ સમયગાળામાં આ વસ્તુ વેચાઈ નથી.", "ଏହି ଅବଧିରେ ଏହି ସାମଗ୍ରୀ ବିକ୍ରି ହୋଇନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਇਹ ਵਸਤੂ ਨਹੀਂ ਵਿਕੀ।", "এই সময়ছোৱাত এই সামগ্ৰী বিক্ৰী হোৱা নাই।")
        w("NO PRODUCTS ON THIS TILL.", "इस टिल पर कोई उत्पाद नहीं।", "या टिलवर उत्पादन नाही.", "இந்த கல்லாவில் பொருட்கள் இல்லை.", "এই টিলে কোনো পণ্য নেই।", "ఈ టిల్‌లో ఉత్పత్తులు లేవు.", "ಈ ಟಿಲ್‌ನಲ್ಲಿ ಉತ್ಪನ್ನಗಳಿಲ್ಲ.", "આ ટિલ પર કોઈ ઉત્પાદન નથી.", "ଏହି ଟିଲରେ କୌଣସି ଉତ୍ପାଦ ନାହିଁ।", "ਇਸ ਟਿੱਲ 'ਤੇ ਕੋਈ ਉਤਪਾਦ ਨਹੀਂ।", "এই টিলত কোনো সামগ্ৰী নাই।")
        w("NO TRANSACTIONS IN THIS PERIOD.", "इस अवधि में कोई लेन-देन नहीं।", "या कालावधीत व्यवहार नाही.", "இந்தக் காலகட்டத்தில் பரிவர்த்தனை இல்லை.", "এই সময়ে কোনো লেনদেন নেই।", "ఈ కాలంలో లావాదేవీలు లేవు.", "ಈ ಅವಧಿಯಲ್ಲಿ ವಹಿವಾಟು ಇಲ್ಲ.", "આ સમયગાળામાં કોઈ વ્યવહાર નથી.", "ଏହି ଅବଧିରେ କୌଣସି କାରବାର ନାହିଁ।", "ਇਸ ਮਿਆਦ ਵਿੱਚ ਕੋਈ ਲੈਣ-ਦੇਣ ਨਹੀਂ।", "এই সময়ছোৱাত কোনো লেনদেন নাই।")
    }

    /** One dictionary row, in [Language] order after English. */
    private fun MutableMap<String, Array<String>>.w(
        key: String, hi: String, mr: String, ta: String, bn: String, te: String,
        kn: String, gu: String, or: String, pa: String, `as`: String
    ) {
        put(normalise(key), arrayOf(hi, mr, ta, bn, te, kn, gu, or, pa, `as`))
    }
}
