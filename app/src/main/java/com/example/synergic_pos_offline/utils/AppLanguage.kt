package com.example.synergic_pos_offline.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import com.example.synergic_pos_offline.R
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * The language the app's own screens are labelled in.
 *
 * The counterpart to [PrintLanguage], and deliberately not built the same way. A
 * bill is *translated*, because the customer holding it wants the real word. A
 * screen is *transliterated* - the English word respelled in the local script -
 * because the person reading it was trained on this till in English and says
 * "product" and "tax" out loud. So the bill says পণ্য and the screen says প্রডাক্ট.
 *
 * ## What this translates
 *
 * The app's *labels* - headings, buttons, menu rows, field hints. Never the shop's
 * own data: product names, customer names, amounts and anything typed into a field
 * are left exactly as they are. That falls out of the dictionary rather than needing
 * a rule, because "Basmati Rice 5kg" is not a phrase anybody wrote a translation for
 * and so nothing happens to it.
 *
 * ## What it deliberately does not translate
 *
 * The login screen, which is the one place the app is used by somebody who has not
 * chosen a language yet - the setting is per till and is read after sign-in. It is
 * excluded by where [apply] is called from rather than by a check in here.
 *
 * The receipt preview, too: that is a picture of a printed bill, and the language it
 * is labelled in is Print Language's answer, not this one's. A till may well print
 * Hindi bills from an English screen, or the reverse.
 *
 * ## Coverage
 *
 * Every label is covered, because [Transliterator] works from the spelling rather
 * than from a list - so a screen added tomorrow is in the language too, with nobody
 * having to write its words down. [CORRECTIONS] holds only the words the letters
 * mislead it on.
 */
object AppLanguage {

    /** What the screens read in until somebody chooses otherwise. */
    val DEFAULT = PrintLanguage.Language.ENGLISH

    /** Settings key for app screen language (independent of print language). */
    const val SETTING_KEY = "app_language"

    /**
     * The language this till's screens are in.
     *
     * Separate from [PrintLanguage.SETTING_KEY] so the operator can choose different
     * languages for screens vs. bills. Both default to English, but changing one does
     * not affect the other.
     *
     * What differs is not *which* language but how it is written - the bill is
     * translated and the screen is transliterated. See [tr].
     */
    fun of(context: Context): PrintLanguage.Language = runCatching {
        val code = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString(SETTING_KEY, DEFAULT.code)
        PrintLanguage.Language.values().firstOrNull { it.code == code } ?: DEFAULT
    }.getOrDefault(DEFAULT)

    /**
     * [raw] as this language spells it.
     *
     * The screens are *transliterated*, not translated: an English word written in
     * the local script, which is how the trade actually talks. A Bengali shopkeeper
     * says "product" and "tax" out loud, so the screen says প্রডাক্ট and ট্যাক্স -
     * not পণ্য and কর, which are the dictionary words and read like a textbook.
     *
     * That is the opposite of what [PrintLanguage] does, deliberately. A bill is read
     * by the customer holding it and wants the real word; a screen is read by whoever
     * was trained on this till, and they learned it in English.
     *
     * [Transliterator] does the respelling, so every label is covered rather than
     * only the ones somebody wrote down. [CORRECTIONS] is where its answer is
     * overridden - it works letter by letter from the spelling, which is right for
     * "Tax" and wrong for "User", where English says "yoo-zer" and the letters do not.
     */
    fun tr(lang: PrintLanguage.Language, raw: String?): String {
        val text = raw ?: return ""
        if (lang == DEFAULT || text.isBlank()) return text
        correction(lang, text)?.let { return it }
        // Word by word after that, so a correction written once reaches every label
        // the word appears in. "Order Items" is two known words, and without this it
        // would fall through whole to the machine and come back with neither of them.
        return text.split(SPACE).joinToString(" ") { token -> corrected(lang, token) }
    }

    /** [raw] as a whole phrase, if the whole phrase has been written down. */
    private fun correction(lang: PrintLanguage.Language, raw: String): String? =
        CORRECTIONS[normalise(raw)]?.getOrNull(lang.slotOf())?.takeIf { it.isNotEmpty() }

    /**
     * One word, with whatever punctuation it arrived wearing put back on.
     *
     * "Orders" and "Orders:" and "(Orders)" are the same word as far as the list is
     * concerned, and a label that ends in a colon should not miss its correction over
     * it. Anything with no correction goes to the machine as before.
     */
    private fun corrected(lang: PrintLanguage.Language, token: String): String {
        val core = token.trim { !it.isLetter() }
        if (core.isEmpty()) return Transliterator.to(lang, token)
        val fixed = correction(lang, core) ?: return Transliterator.to(lang, token)
        val at = token.indexOf(core)
        return token.substring(0, at) + fixed + token.substring(at + core.length)
    }

    private val SPACE = Regex(" ")

    /** Subtrees the pass does not enter - see [walk]. */
    private val UNTOUCHED = setOf(R.id.cardReceipt, R.id.cardProductNames)

    /** The row written down for [key], for tests to check none of them is short. */
    internal fun correctionsFor(key: String): Array<String>? = CORRECTIONS[normalise(key)]

    /** Where this language's word sits in a [CORRECTIONS] row; -1 for English. */
    private fun PrintLanguage.Language.slotOf(): Int = ordinal - 1

    /** Trim, collapse runs of space, upper-case - the form [CORRECTIONS] is keyed in. */
    private fun normalise(raw: String): String =
        raw.trim().replace(WHITESPACE, " ").uppercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")

    // ---- Applying it to a live screen ------------------------------------------

    /**
     * Translates every label under [root] into [lang], in place.
     *
     * Walks the live view tree rather than going through string resources, because
     * this app has none to go through: its text is written into 137 layout files and
     * a thousand places in code. Extracting all of it would be a far larger and more
     * dangerous change than reading it back off the views it has already been set on,
     * and this way a screen that builds its rows in code is translated by the same
     * pass as one that declares them in XML.
     *
     * Safe to call repeatedly, and safe to call with a different language than last
     * time: each view keeps its English original in a tag ([R.id.tag_original_text]),
     * so every pass translates the English rather than the previous translation.
     */
    fun apply(root: View?, lang: PrintLanguage.Language) {
        if (root == null) return
        walk(root, lang)
    }

    /** The same, reading the till's chosen language for itself. */
    fun apply(root: View?) {
        val context = root?.context ?: return
        apply(root, of(context))
    }

    private fun walk(view: View, lang: PrintLanguage.Language) {
        // Two cards show raw text on purpose and must be left exactly as they are.
        //
        // The receipt card is a picture of printed paper, laid out in whatever Print
        // Language says. The product-names card is a before-and-after - what was
        // typed beside what will print - and relabelling the "before" side would
        // leave it comparing a thing with itself.
        if (view.id in UNTOUCHED) return

        when (view) {
            // A dropdown's text is the *value* that was chosen, and several screens
            // read it back to work out what was selected. Translating it would leave
            // them unable to recognise their own setting - so only the hint moves.
            is AutoCompleteTextView -> translateHint(view, lang)
            // The same, harder: an EditText holds whatever was typed into it.
            is EditText -> translateHint(view, lang)
            is TextView -> {
                translateText(view, lang)
                translateHint(view, lang)
            }
            is TextInputLayout -> translateLayoutHint(view, lang)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), lang)
        }
    }

    /**
     * Translates one label, keeping track of what the English behind it was.
     *
     * Two tags rather than one, because a screen sets its own text constantly - a
     * running total, the signed-in user, a row count - and a single "the original was
     * X" cache would keep writing a stale X back over it. So the pass also remembers
     * what *it* last wrote: if the view still says that, the app has not touched it
     * and the remembered English still stands; if it says anything else, the app has
     * moved on and whatever is there now is the new English to translate from.
     */
    private fun translateInto(
        current: String?,
        originalTag: Int,
        appliedTag: Int,
        view: View,
        lang: PrintLanguage.Language,
        set: (String) -> Unit
    ) {
        val now = current.orEmpty()
        val applied = view.getTag(appliedTag) as? String
        val original = if (applied != null && applied == now) {
            view.getTag(originalTag) as? String ?: now
        } else {
            now
        }
        if (original.isBlank()) return

        val translated = tr(lang, original)
        view.setTag(originalTag, original)
        view.setTag(appliedTag, translated)
        if (now != translated) set(translated)
    }

    private fun translateText(view: TextView, lang: PrintLanguage.Language) =
        translateInto(
            view.text?.toString(), R.id.tag_original_text, R.id.tag_applied_text, view, lang
        ) { view.text = it }

    private fun translateHint(view: TextView, lang: PrintLanguage.Language) =
        translateInto(
            view.hint?.toString(), R.id.tag_original_hint, R.id.tag_applied_hint, view, lang
        ) { view.hint = it }

    private fun translateLayoutHint(view: TextInputLayout, lang: PrintLanguage.Language) =
        translateInto(
            view.hint?.toString(), R.id.tag_original_hint, R.id.tag_applied_hint, view, lang
        ) { view.hint = it }

    // ---- The dictionary ---------------------------------------------------------

    private fun MutableMap<String, Array<String>>.w(key: String, vararg values: String) {
        put(normalise(key), arrayOf(*values))
    }


    /**
     * Where [Transliterator]'s answer is overridden, in the order of
     * [PrintLanguage.Language] after English: Hindi, Marathi, Tamil, Bengali,
     * Telugu, Kannada, Gujarati, Odia, Punjabi, Assamese.
     *
     * Not a dictionary of the app's words - the transliterator already covers every
     * one of those, which is what makes this feature reach the whole app rather than
     * a list somebody maintains. This is only the exceptions: words where working
     * from the spelling gives the wrong sound.
     *
     * They are nearly all the same two faults. English writes a sound it does not
     * say - "User" is "yoo-zer", not "oo-ser" - or it writes one letter for two
     * sounds, as the hard c in "Duplicate" that the rules read as an s. Anything the
     * machine already gets right is deliberately absent: "Tax" is not here because
     * ট্যাক্স is what it produces on its own.
     */
    private val CORRECTIONS: Map<String, Array<String>> = buildMap {
        // ---- The sounds the spelling hides --------------------------------------
        w("USER", "यूज़र", "यूजर", "யூசர்", "ইউজার", "యూజర్", "ಯೂಸರ್", "યુઝર", "ୟୁଜର", "ਯੂਜ਼ਰ", "ইউজাৰ")
        w("USERS", "यूज़र्स", "यूजर्स", "யூசர்ஸ்", "ইউজারস", "యూజర్స్", "ಯೂಸರ್ಸ್", "યુઝર્સ", "ୟୁଜରସ", "ਯੂਜ਼ਰਸ", "ইউজাৰছ")
        w("DUPLICATE", "डुप्लिकेट", "डुप्लिकेट", "டூப்ளிகேட்", "ডুপ্লিকেট", "డుప్లికేట్", "ಡುಪ್ಲಿಕೇಟ್", "ડુપ્લિકેટ", "ଡୁପ୍ଲିକେଟ", "ਡੁਪਲੀਕੇਟ", "ডুপ্লিকেট")
        w("PRODUCT", "प्रोडक्ट", "प्रोडक्ट", "ப்ரொடக்ட்", "প্রডাক্ট", "ప్రొడక్ట్", "ಪ್ರೊಡಕ್ಟ್", "પ્રોડક્ટ", "ପ୍ରଡକ୍ଟ", "ਪ੍ਰੋਡਕਟ", "প্ৰডাক্ট")
        w("PRODUCTS", "प्रोडक्ट्स", "प्रोडक्ट्स", "ப்ரொடக்ட்ஸ்", "প্রডাক্টস", "ప్రొడక్ట్స్", "ಪ್ರೊಡಕ್ಟ್ಸ್", "પ્રોડક્ટ્સ", "ପ୍ରଡକ୍ଟସ", "ਪ੍ਰੋਡਕਟਸ", "প্ৰডাক্টছ")
        w("CATEGORY", "कैटेगरी", "कॅटेगरी", "கேட்டகிரி", "ক্যাটাগরি", "కేటగిరీ", "ಕ್ಯಾಟಗರಿ", "કેટેગરી", "କ୍ୟାଟାଗୋରୀ", "ਕੈਟੇਗਰੀ", "কেটেগৰী")
        w("CUSTOMER", "कस्टमर", "कस्टमर", "கஸ்டமர்", "কাস্টমার", "కస్టమర్", "ಕಸ್ಟಮರ್", "કસ્ટમર", "କଷ୍ଟମର", "ਕਸਟਮਰ", "কাষ্টমাৰ")
        w("CUSTOMERS", "कस्टमर्स", "कस्टमर्स", "கஸ்டமர்ஸ்", "কাস্টমারস", "కస్టమర్స్", "ಕಸ್ಟಮರ್ಸ್", "કસ્ટમર્સ", "କଷ୍ଟମରସ", "ਕਸਟਮਰਸ", "কাষ্টমাৰছ")
        w("SEARCH", "सर्च", "सर्च", "சர்ச்", "সার্চ", "సెర్చ్", "ಸರ್ಚ್", "સર્ચ", "ସର୍ଚ", "ਸਰਚ", "চাৰ্চ")
        w("SALE", "सेल", "सेल", "சேல்", "সেল", "సేల్", "ಸೇಲ್", "સેલ", "ସେଲ", "ਸੇਲ", "চেল")
        w("SALES", "सेल्स", "सेल्स", "சேல்ஸ்", "সেলস", "సేల్స్", "ಸೇಲ್ಸ್", "સેલ્સ", "ସେଲସ", "ਸੇਲਸ", "চেলছ")
        w("LOGOUT", "लॉगआउट", "लॉगआउट", "லாக்அவுட்", "লগআউট", "లాగౌట్", "ಲಾಗ್‌ಔಟ್", "લોગઆઉટ", "ଲଗଆଉଟ", "ਲਾਗਆਊਟ", "লগআউট")
        w("LOGIN", "लॉगिन", "लॉगिन", "லாகின்", "লগইন", "లాగిన్", "ಲಾಗಿನ್", "લોગિન", "ଲଗଇନ", "ਲਾਗਇਨ", "লগইন")
        w("CHECKOUT", "चेकआउट", "चेकआउट", "செக்அவுட்", "চেকআউট", "చెకౌట్", "ಚೆಕ್‌ಔಟ್", "ચેકઆઉટ", "ଚେକଆଉଟ", "ਚੈੱਕਆਊਟ", "চেকআউট")
        w("ORDER", "ऑर्डर", "ऑर्डर", "ஆர்டர்", "অর্ডার", "ఆర్డర్", "ಆರ್ಡರ್", "ઓર્ડર", "ଅର୍ଡର", "ਆਰਡਰ", "অৰ্ডাৰ")
        w("ORDERS", "ऑर्डर्स", "ऑर्डर्स", "ஆர்டர்ஸ்", "অর্ডারস", "ఆర్డర్స్", "ಆರ್ಡರ್ಸ್", "ઓર્ડર્સ", "ଅର୍ଡରସ", "ਆਰਡਰਸ", "অৰ্ডাৰছ")
        w("TOTAL", "टोटल", "टोटल", "டோட்டல்", "টোটাল", "టోటల్", "ಟೋಟಲ್", "ટોટલ", "ଟୋଟାଲ", "ਟੋਟਲ", "টোটেল")
        w("SETTINGS", "सेटिंग्स", "सेटिंग्ज", "செட்டிங்ஸ்", "সেটিংস", "సెట్టింగ్స్", "ಸೆಟ್ಟಿಂಗ್ಸ್", "સેટિંગ્સ", "ସେଟିଂସ", "ਸੈਟਿੰਗਸ", "ছেটিংছ")
        w("REPORT", "रिपोर्ट", "रिपोर्ट", "ரிப்போர்ட்", "রিপোর্ট", "రిపోర్ట్", "ರಿಪೋರ್ಟ್", "રિપોર્ટ", "ରିପୋର୍ଟ", "ਰਿਪੋਰਟ", "ৰিপৰ্ট")
        w("REPORTS", "रिपोर्ट्स", "रिपोर्ट्स", "ரிப்போர்ட்ஸ்", "রিপোর্টস", "రిపోర్ట్స్", "ರಿಪೋರ್ಟ್ಸ್", "રિપોર્ટ્સ", "ରିପୋର୍ଟସ", "ਰਿਪੋਰਟਸ", "ৰিপৰ্টছ")
        w("QUANTITY", "क्वांटिटी", "क्वांटिटी", "குவாண்டிட்டி", "কোয়ান্টিটি", "క్వాంటిటీ", "ಕ್ವಾಂಟಿಟಿ", "ક્વોન્ટિટી", "କ୍ୱାଣ୍ଟିଟି", "ਕੁਆਂਟਿਟੀ", "কোৱাণ্টিটি")
        w("PRICE", "प्राइस", "प्राइस", "ப்ரைஸ்", "প্রাইস", "ప్రైస్", "ಪ್ರೈಸ್", "પ્રાઇસ", "ପ୍ରାଇସ", "ਪ੍ਰਾਈਸ", "প্ৰাইচ")
        w("DATE", "डेट", "डेट", "டேட்", "ডেট", "డేట్", "ಡೇಟ್", "ડેટ", "ଡେଟ", "ਡੇਟ", "ডেট")
        w("NAME", "नेम", "नेम", "நேம்", "নেম", "నేమ్", "ನೇಮ್", "નેમ", "ନେମ", "ਨੇਮ", "নেম")
        w("DELETE", "डिलीट", "डिलीट", "டெலீட்", "ডিলিট", "డిలీట్", "ಡಿಲೀಟ್", "ડિલીટ", "ଡିଲିଟ", "ਡਿਲੀਟ", "ডিলিট")
        w("MASTER", "मास्टर", "मास्टर", "மாஸ்டர்", "মাস্টার", "మాస్టర్", "ಮಾಸ್ಟರ್", "માસ્ટર", "ମାଷ୍ଟର", "ਮਾਸਟਰ", "মাষ্টাৰ")
        w("LANGUAGE", "लैंग्वेज", "लँग्वेज", "லாங்குவேஜ்", "ল্যাঙ্গুয়েজ", "లాంగ్వేజ్", "ಲ್ಯಾಂಗ್ವೇಜ್", "લેંગ્વેજ", "ଲାଙ୍ଗୁଏଜ", "ਲੈਂਗਵੇਜ", "লেংগুৱেজ")
        w("DASHBOARD", "डैशबोर्ड", "डॅशबोर्ड", "டாஷ்போர்டு", "ড্যাশবোর্ড", "డాష్‌బోర్డ్", "ಡ್ಯಾಶ್‌ಬೋರ್ಡ್", "ડેશબોર્ડ", "ଡ୍ୟାସବୋର୍ଡ", "ਡੈਸ਼ਬੋਰਡ", "ডেশ্ববৰ্ড")
        w("PAYMENT", "पेमेंट", "पेमेंट", "பேமெண்ட்", "পেমেন্ট", "పేమెంట్", "ಪೇಮೆಂಟ್", "પેમેન્ટ", "ପେମେଣ୍ଟ", "ਪੇਮੈਂਟ", "পেমেণ্ট")
        w("DISCOUNT", "डिस्काउंट", "डिस्काउंट", "டிஸ்கவுண்ட்", "ডিসকাউন্ট", "డిస్కౌంట్", "ಡಿಸ್ಕೌಂಟ್", "ડિસ્કાઉન્ટ", "ଡିସ୍କାଉଣ୍ଟ", "ਡਿਸਕਾਊਂਟ", "ডিচকাউণ্ট")
        w("CANCEL", "कैंसिल", "कॅन्सल", "கேன்சல்", "ক্যানসেল", "క్యాన్సిల్", "ಕ್ಯಾನ್ಸಲ್", "કેન્સલ", "କ୍ୟାନ୍ସେଲ", "ਕੈਂਸਲ", "কেনচেল")
        w("HISTORY", "हिस्ट्री", "हिस्ट्री", "ஹிஸ்டரி", "হিস্ট্রি", "హిస్టరీ", "ಹಿಸ್ಟರಿ", "હિસ્ટ્રી", "ହିଷ୍ଟ୍ରି", "ਹਿਸਟਰੀ", "হিষ্ট্ৰী")
        w("INVENTORY", "इन्वेंटरी", "इन्व्हेंटरी", "இன்வெண்டரி", "ইনভেন্টরি", "ఇన్వెంటరీ", "ಇನ್ವೆಂಟರಿ", "ઇન્વેન્ટરી", "ଇନଭେଣ୍ଟୋରୀ", "ਇਨਵੈਂਟਰੀ", "ইনভেণ্টৰী")
    }
}
