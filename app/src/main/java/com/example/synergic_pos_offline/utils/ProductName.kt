package com.example.synergic_pos_offline.utils

import java.util.Locale

/**
 * A product's name as it prints in the till's language - translated where the words
 * are words, respelled where they are not.
 *
 * ## The two halves, and why a name needs both
 *
 * A line on a grocery bill is usually a brand and a thing: TATA SALT, PARLE-G
 * BISCUIT, SAFFOLA REFINED OIL. The two halves want opposite treatment, and applying
 * either one to the whole name gets it wrong.
 *
 *  * **The thing** has a word in every language, and printing it is a translation:
 *    SALT is नमक, உப்பு, ਲੂਣ. Respelling it as "साल्ट" would be writing an English
 *    word in Hindi letters when Hindi has its own.
 *  * **The brand** has no translation and must not be given one. TATA is टाटा -
 *    that is the company's name, and a customer looking for it on a shelf will not
 *    find "टाटा" translated into anything. Respelling is exactly right here.
 *
 * So a name is read word by word: anything in [WORDS] or [PHRASES] is translated, and
 * everything else falls through to [Transliterator] to be respelled. TATA SALT comes
 * out as टाटा नमक, which is what the shop's own signboard would say.
 *
 * ## Why a lexicon and not something cleverer
 *
 * There is no translation service on a till with no network, and there could not be a
 * complete dictionary of product names in any case - a shop can type anything. What
 * there *can* be is the vocabulary of the trade: the two hundred-odd words that name
 * what a kirana actually stocks. Those cover the noun on most lines, and the words
 * around them are brands, which wanted respelling anyway.
 *
 * ## The blank cell
 *
 * A word whose translation this file is not sure of in some language is left **empty**
 * for that language, and an empty cell falls through to respelling. That is the whole
 * safety story here: a respelled word is merely plain, while a wrongly translated one
 * is a different product on a customer's bill. Filling a cell is a claim, and the
 * blanks are where the claim was not worth making.
 */
object ProductName {

    /**
     * [raw] as it should print in [language].
     *
     * Translated first, respelled second: the lexicon replaces the words it knows, and
     * whatever is still in Latin afterwards goes to [Transliterator]. Which is why the
     * transliterator asks its "already in another script" question per word.
     */
    fun inPrintLanguage(language: PrintLanguage.Language, raw: String?): String {
        val name = raw ?: return ""
        if (language == PrintLanguage.Language.ENGLISH || name.isBlank()) return name
        val translated = runCatching { translateWords(language, name) }.getOrDefault(name)
        return Transliterator.to(language, translated)
    }

    /** Whether names are touched at all in [language] - false for English. */
    fun applies(language: PrintLanguage.Language): Boolean =
        language != PrintLanguage.Language.ENGLISH

    // ---- Reading a name a word at a time ---------------------------------------

    /** A run of letters, or the punctuation and spacing between two of them. */
    private data class Segment(val text: String, val isWord: Boolean)

    /**
     * Replaces the words and phrases this file knows, and leaves everything else
     * exactly where it was - spacing, hyphens and brackets included.
     *
     * Phrases are tried before single words, longest first, because several of them
     * mean something their words do not: BLACK PEPPER is one spice and not a pepper
     * that is black, and LADY FINGER is a vegetable. Taken a word at a time those
     * would come out as nonsense in every language here.
     */
    private fun translateWords(language: PrintLanguage.Language, name: String): String {
        val segments = segment(name)
        val out = StringBuilder()
        var i = 0
        // All of it or none of it - see [translatesWhole] for why.
        if (!translatesWhole(language, segments)) return name
        while (i < segments.size) {
            val segment = segments[i]
            if (!segment.isWord) {
                out.append(segment.text)
                i++
                continue
            }
            var consumed = 0
            for (span in MAX_PHRASE_WORDS downTo 2) {
                val phrase = phraseAt(segments, i, span) ?: continue
                val hit = lookup(language, PHRASES, phrase.first) ?: continue
                out.append(hit)
                consumed = phrase.second - i
                break
            }
            if (consumed > 0) {
                i += consumed
                continue
            }
            out.append(lookup(language, WORDS, segment.text) ?: singular(language, segment.text) ?: segment.text)
            i++
        }
        return out.toString()
    }

    /**
     * Whether every word of the name is one this file knows - the condition for
     * translating any of it.
     *
     * All or nothing, and this is the rule the whole file turns on.
     *
     * A name made entirely of trade words is a commodity, and a shop wants the word:
     * BUTTER is মাখন, SALT is লবণ, MUSTARD OIL is সরিষার তেল. A name with anything
     * else in it is somebody's name for something - a brand, a dish, a variant - and
     * the whole of it should be spelled rather than half-answered. VEG FRIED RICE is
     * ভেজ ফ্রাইড রাইস, because it is the name of a dish and not an instruction to buy
     * rice; JOHNSON'S BABY CREAM is জনসন'স বেবি ক্রিম all the way through.
     *
     * Translating word by word produced the worst of both: টাটা নুন and বেগ ফ্রীড চাল,
     * where half the name is the shop's and half is this file's, and a reader can
     * tell. Whichever way this rule falls for a given name, at least the name is one
     * thing.
     *
     * A unit after a figure counts as known, since it passes through either way.
     */
    private fun translatesWhole(
        language: PrintLanguage.Language,
        segments: List<Segment>
    ): Boolean {
        var i = 0
        var sawWord = false
        while (i < segments.size) {
            if (!segments[i].isWord) {
                i++
                continue
            }
            sawWord = true
            var matched = 0
            for (span in MAX_PHRASE_WORDS downTo 2) {
                val phrase = phraseAt(segments, i, span) ?: continue
                if (lookup(language, PHRASES, phrase.first) == null) continue
                matched = phrase.second - i
                break
            }
            if (matched > 0) {
                i += matched
                continue
            }
            val word = segments[i].text
            val known = lookup(language, WORDS, word) != null ||
                singular(language, word) != null ||
                isUnitAfterFigure(segments, i)
            if (!known) return false
            i++
        }
        return sawWord
    }

    /** "500 ML" - a unit is carried through untranslated, so it never blocks a name. */
    private fun isUnitAfterFigure(segments: List<Segment>, i: Int): Boolean {
        if (segments[i].text.lowercase(Locale.ROOT) !in UNITS) return false
        // Something before it has to be a figure, or "G" in "PARLE-G" would qualify.
        val before = segments.take(i).lastOrNull { it.text.any(Char::isDigit) }
        return before != null
    }

    /** The unit words, as [Transliterator] knows them - kept in step by hand. */
    private val UNITS = setOf(
        "kg", "kgs", "g", "gm", "gms", "mg", "ml", "l", "ltr", "ltrs",
        "pc", "pcs", "pkt", "pkts", "box", "nos", "dz", "mm", "cm"
    )

    /** Splits [name] into its words and the gaps between them, losing nothing. */
    private fun segment(name: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < name.length) {
            val word = name[i].isLetter()
            var j = i
            while (j < name.length && name[j].isLetter() == word) j++
            out.add(Segment(name.substring(i, j), word))
            i = j
        }
        return out
    }

    /**
     * The next [span] words from [from] joined by single spaces, and where they end -
     * or null when they are not separated by something a phrase can span.
     *
     * A space or a hyphen may sit between the words of a phrase; a bracket or a comma
     * may not, because those separate one idea from the next rather than joining two
     * halves of one.
     */
    private fun phraseAt(segments: List<Segment>, from: Int, span: Int): Pair<String, Int>? {
        val words = mutableListOf<String>()
        var i = from
        while (words.size < span) {
            if (i >= segments.size || !segments[i].isWord) return null
            words.add(segments[i].text)
            i++
            if (words.size == span) break
            val gap = segments.getOrNull(i) ?: return null
            if (gap.isWord || gap.text.any { it != ' ' && it != '-' }) return null
            i++
        }
        return words.joinToString(" ") to i
    }

    /** The longest phrase this file holds, so [translateWords] knows where to start. */
    private const val MAX_PHRASE_WORDS = 3

    /**
     * BISCUITS is BISCUIT, and CANDIES is CANDY.
     *
     * Only tried once the word itself has missed, so a plural that is its own word -
     * PEAS, NOODLES, GRAPES - is answered from the table rather than being cut down to
     * something that is not a word at all.
     */
    private fun singular(language: PrintLanguage.Language, word: String): String? {
        val upper = word.uppercase(Locale.ROOT)
        if (upper.length > 4 && upper.endsWith("IES")) {
            lookup(language, WORDS, upper.dropLast(3) + "Y")?.let { return it }
        }
        if (upper.length > 3 && upper.endsWith("ES")) {
            lookup(language, WORDS, upper.dropLast(2))?.let { return it }
        }
        if (upper.length > 3 && upper.endsWith("S")) {
            lookup(language, WORDS, upper.dropLast(1))?.let { return it }
        }
        return null
    }

    /** [key] in [language], or null - which includes a cell left deliberately empty. */
    private fun lookup(
        language: PrintLanguage.Language,
        table: Map<String, Array<String>>,
        key: String
    ): String? {
        val slot = language.ordinal - 1
        if (slot < 0) return null
        val normalised = key.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)
        return table[normalised]?.getOrNull(slot)?.takeIf { it.isNotEmpty() }
    }

    // ---- The lexicon ---------------------------------------------------------------

    /**
     * Phrases that mean something their words do not, tried before the words are.
     *
     * This is also where a modifier gets to be translated at all. BLACK and RED are
     * not in [WORDS] on purpose - a colour is safe inside BLACK PEPPER and a menace
     * outside it, where BLACK LABEL and RED BULL are names of things and would come
     * back with their first word turned into a colour.
     */
    private val PHRASES: Map<String, Array<String>> = buildMap {
        w("RED CHILLI", "लाल मिर्च", "लाल मिरची", "சிவப்பு மிளகாய்", "লাল লঙ্কা", "ఎర్ర మిర్చి", "ಕೆಂಪು ಮೆಣಸಿನಕಾಯಿ", "લાલ મરચું", "ଲାଲ ଲଙ୍କା", "ਲਾਲ ਮਿਰਚ", "ৰঙা জলকীয়া")
        w("GREEN CHILLI", "हरी मिर्च", "हिरवी मिरची", "பச்சை மிளகாய்", "কাঁচা লঙ্কা", "పచ్చి మిర్చి", "ಹಸಿ ಮೆಣಸಿನಕಾಯಿ", "લીલું મરચું", "କଞ୍ଚା ଲଙ୍କା", "ਹਰੀ ਮਿਰਚ", "কেঁচা জলকীয়া")
        w("BLACK PEPPER", "काली मिर्च", "काळी मिरी", "மிளகு", "গোলমরিচ", "మిరియాలు", "ಕಾಳುಮೆಣಸು", "કાળા મરી", "ଗୋଲମରିଚ", "ਕਾਲੀ ਮਿਰਚ", "গোলমৰিচ")
        w("MUSTARD OIL", "सरसों तेल", "मोहरीचे तेल", "கடுகு எண்ணெய்", "সরিষার তেল", "ఆవ నూనె", "ಸಾಸಿವೆ ಎಣ್ಣೆ", "રાઈનું તેલ", "ସୋରିଷ ତେଲ", "ਸਰ੍ਹੋਂ ਦਾ ਤੇਲ", "সৰিয়হৰ তেল")
        w("COCONUT OIL", "नारियल तेल", "खोबरेल तेल", "தேங்காய் எண்ணெய்", "নারকেল তেল", "కొబ్బరి నూనె", "ತೆಂಗಿನ ಎಣ್ಣೆ", "નાળિયેર તેલ", "ନଡ଼ିଆ ତେଲ", "ਨਾਰੀਅਲ ਤੇਲ", "নাৰিকল তেল")
        w("HAIR OIL", "बाल तेल", "केस तेल", "தலை எண்ணெய்", "চুলের তেল", "జుట్టు నూనె", "ಕೂದಲ ಎಣ್ಣೆ", "વાળનું તેલ", "କେଶ ତେଲ", "ਵਾਲਾਂ ਦਾ ਤੇਲ", "চুলিৰ তেল")
        w("GARAM MASALA", "गरम मसाला", "गरम मसाला", "கரம் மசாலா", "গরম মসলা", "గరం మసాలా", "ಗರಂ ಮಸಾಲೆ", "ગરમ મસાલો", "ଗରମ ମସଲା", "ਗਰਮ ਮਸਾਲਾ", "গৰম মচলা")
        w("CHILLI POWDER", "मिर्च पाउडर", "मिरची पावडर", "மிளகாய்த் தூள்", "লঙ্কা গুঁড়ো", "కారం పొడి", "ಮೆಣಸಿನ ಪುಡಿ", "મરચું પાવડર", "ଲଙ୍କା ଗୁଣ୍ଡ", "ਮਿਰਚ ਪਾਊਡਰ", "জলকীয়া গুৰি")
        w("TURMERIC POWDER", "हल्दी पाउडर", "हळद पावडर", "மஞ்சள் தூள்", "হলুদ গুঁড়ো", "పసుపు పొడి", "ಅರಿಶಿನ ಪುಡಿ", "હળદર પાવડર", "ହଳଦୀ ଗୁଣ୍ଡ", "ਹਲਦੀ ਪਾਊਡਰ", "হালধি গুৰি")
        w("MILK POWDER", "दूध पाउडर", "दूध पावडर", "பால் பொடி", "দুধের গুঁড়ো", "పాల పొడి", "ಹಾಲಿನ ಪುಡಿ", "દૂધ પાવડર", "ଦୁଧ ଗୁଣ୍ଡ", "ਦੁੱਧ ਪਾਊਡਰ", "গাখীৰ গুৰি")
        w("WASHING POWDER", "धुलाई पाउडर", "धुण्याची पावडर", "சலவைத் தூள்", "কাচার গুঁড়ো", "వాషింగ్ పౌడర్", "ಒಗೆಯುವ ಪುಡಿ", "ધોવાનો પાવડર", "ଧୋଇବା ଗୁଣ୍ଡ", "ਧੋਣ ਵਾਲਾ ਪਾਊਡਰ", "ধোৱা গুৰি")
        w("TEA POWDER", "चाय पत्ती", "चहा पूड", "தேயிலைத் தூள்", "চা পাতা", "టీ పొడి", "ಚಹಾ ಪುಡಿ", "ચા પાવડર", "ଚା ଗୁଣ୍ଡ", "ਚਾਹ ਪੱਤੀ", "চাহ পাত")
        w("GRAM FLOUR", "बेसन", "बेसन", "கடலை மாவு", "বেসন", "శనగపిండి", "ಕಡಲೆ ಹಿಟ್ಟು", "ચણાનો લોટ", "ବେସନ", "ਵੇਸਣ", "বেচন")
        w("ICE CREAM", "आइसक्रीम", "आइस्क्रीम", "ஐஸ்கிரீம்", "আইসক্রিম", "ఐస్ క్రీమ్", "ಐಸ್ ಕ್ರೀಮ್", "આઈસ્ક્રીમ", "ଆଇସକ୍ରିମ", "ਆਈਸਕਰੀਮ", "আইচক্ৰীম")
        w("COLD DRINK", "ठंडा पेय", "थंड पेय", "குளிர்பானம்", "ঠান্ডা পানীয়", "చల్లని పానీయం", "ತಂಪು ಪಾನೀಯ", "ઠંડું પીણું", "ଥଣ୍ଡା ପାନୀୟ", "ਠੰਢਾ ਪੀਣ", "ঠাণ্ডা পানীয়")
        w("SOFT DRINK", "शीतल पेय", "शीतपेय", "குளிர்பானம்", "ঠান্ডা পানীয়", "శీతల పానీయం", "ತಂಪು ಪಾನೀಯ", "ઠંડું પીણું", "ଥଣ୍ଡା ପାନୀୟ", "ਠੰਢਾ ਪੀਣ", "ঠাণ্ডা পানীয়")
        w("LADY FINGER", "भिंडी", "भेंडी", "வெண்டைக்காய்", "ঢেঁড়স", "బెండకాయ", "ಬೆಂಡೆಕಾಯಿ", "ભીંડા", "ଭେଣ୍ଡି", "ਭਿੰਡੀ", "ভেণ্ডী")
        w("GREEN PEAS", "हरी मटर", "हिरवे वाटाणे", "பச்சைப் பட்டாணி", "কড়াইশুঁটি", "పచ్చి బఠానీ", "ಹಸಿ ಬಟಾಣಿ", "લીલા વટાણા", "କଞ୍ଚା ମଟର", "ਹਰੇ ਮਟਰ", "কেঁচা মটৰ")
        w("TOOTH PASTE", "टूथपेस्ट", "टूथपेस्ट", "பற்பசை", "টুথপেস্ট", "టూత్ పేస్ట్", "ಟೂತ್ ಪೇಸ್ಟ್", "ટૂથપેસ્ટ", "ଟୁଥପେଷ୍ଟ", "ਟੂਥਪੇਸਟ", "টুথপেষ্ট")
        w("TOOTH BRUSH", "टूथब्रश", "टूथब्रश", "பல் துலக்கி", "টুথব্রাশ", "టూత్ బ్రష్", "ಟೂತ್ ಬ್ರಷ್", "ટૂથબ્રશ", "ଟୁଥବ୍ରସ୍", "ਟੂਥਬੁਰਸ਼", "টুথব্ৰাছ")
        w("MINERAL WATER", "मिनरल पानी", "मिनरल पाणी", "குடிநீர்", "মিনারেল জল", "మినరల్ నీరు", "ಮಿನರಲ್ ನೀರು", "મિનરલ પાણી", "ମିନେରାଲ ପାଣି", "ਮਿਨਰਲ ਪਾਣੀ", "মিনাৰেল পানী")
        w("DRINKING WATER", "पीने का पानी", "पिण्याचे पाणी", "குடிநீர்", "পানীয় জল", "తాగు నీరు", "ಕುಡಿಯುವ ನೀರು", "પીવાનું પાણી", "ପିଇବା ପାଣି", "ਪੀਣ ਵਾਲਾ ਪਾਣੀ", "খোৱা পানী")
        w("MUSTARD SEED", "सरसों दाना", "मोहरी", "கடுகு", "সরিষা", "ఆవాలు", "ಸಾಸಿವೆ", "રાઈ", "ସୋରିଷ", "ਸਰ੍ਹੋਂ", "সৰিয়হ")
        w("BAY LEAF", "तेजपत्ता", "तमालपत्र", "பிரியாணி இலை", "তেজপাতা", "బిర్యానీ ఆకు", "ಬಿರಿಯಾನಿ ಎಲೆ", "તમાલપત્ર", "ତେଜପତ୍ର", "ਤੇਜ ਪੱਤਾ", "তেজপাত")
        w("CURRY LEAF", "करी पत्ता", "कढीपत्ता", "கறிவேப்பிலை", "কারি পাতা", "కరివేపాకు", "ಕರಿಬೇವು", "મીઠો લીમડો", "ଭୃଷଙ୍ଗ ପତ୍ର", "ਕੜੀ ਪੱਤਾ", "নৰসিংহ পাত")
        w("GROUND NUT", "मूंगफली", "भुईमूग", "நிலக்கடலை", "চিনাবাদাম", "వేరుశనగ", "ಕಡಲೆಕಾಯಿ", "મગફળી", "ଚିନାବାଦାମ", "ਮੂੰਗਫਲੀ", "বাদাম")
        w("DRY FRUIT", "सूखा मेवा", "सुका मेवा", "உலர் பழம்", "শুকনো ফল", "ఎండు పండ్లు", "ಒಣ ಹಣ್ಣು", "સૂકો મેવો", "ଶୁଖିଲା ଫଳ", "ਸੁੱਕਾ ਮੇਵਾ", "শুকান ফল")
        w("REFINED OIL", "रिफाइंड तेल", "रिफाइंड तेल", "ரிஃபைன்ட் எண்ணெய்", "রিফাইন্ড তেল", "రిఫైన్డ్ నూనె", "ರಿಫೈನ್ಡ್ ಎಣ್ಣೆ", "રિફાઈન્ડ તેલ", "ରିଫାଇନ୍ଡ ତେଲ", "ਰਿਫਾਇੰਡ ਤੇਲ", "ৰিফাইণ্ড তেল")
        w("SUNFLOWER OIL", "सूरजमुखी तेल", "सूर्यफूल तेल", "சூரியகாந்தி எண்ணெய்", "সূর্যমুখী তেল", "పొద్దుతిరుగుడు నూనె", "ಸೂರ್ಯಕಾಂತಿ ಎಣ್ಣೆ", "સૂરજમુખી તેલ", "ସୂର୍ଯ୍ୟମୁଖୀ ତେଲ", "ਸੂਰਜਮੁਖੀ ਤੇਲ", "সূৰ্যমুখী তেল")
        w("WHEAT FLOUR", "गेहूं का आटा", "गव्हाचे पीठ", "கோதுமை மாவு", "গমের আটা", "గోధుమ పిండి", "ಗೋಧಿ ಹಿಟ್ಟು", "ઘઉંનો લોટ", "ଗହମ ଅଟା", "ਕਣਕ ਦਾ ਆਟਾ", "ঘেঁহুৰ আটা")
        // Not a pen. A phrase can also be here to stop its first word being taken for
        // the word it looks like - PEN DRIVE would otherwise print as "कलम ड्राइव".
        w("PEN DRIVE", "पेन ड्राइव", "पेन ड्राइव्ह", "பென் டிரைவ்", "পেন ড্রাইভ", "పెన్ డ్రైవ్", "ಪೆನ್ ಡ್ರೈವ್", "પેન ડ્રાઈવ", "ପେନ ଡ୍ରାଇଭ", "ਪੈੱਨ ਡਰਾਈਵ", "পেন ড্ৰাইভ")
        w("BASMATI RICE", "बासमती चावल", "बासमती तांदूळ", "பாஸ்மதி அரிசி", "বাসমতী চাল", "బాస్మతి బియ్యం", "ಬಾಸ್ಮತಿ ಅಕ್ಕಿ", "બાસમતી ચોખા", "ବାସମତୀ ଚାଉଳ", "ਬਾਸਮਤੀ ਚੌਲ", "বাচমতী চাউল")
    }

    /**
     * The trade's own vocabulary - what a shop actually stocks, one word at a time.
     *
     * In the order [PrintLanguage.Language] names them after English: Hindi, Marathi,
     * Tamil, Bengali, Telugu, Kannada, Gujarati, Odia, Punjabi, Assamese. An empty
     * cell is a word this file will not claim in that language, and it falls through
     * to being respelled instead - see the note at the top.
     */
    private val WORDS: Map<String, Array<String>> = buildMap {
        // ---- Staples ------------------------------------------------------------
        w("RICE", "चावल", "तांदूळ", "அரிசி", "চাল", "బియ్యం", "ಅಕ್ಕಿ", "ચોખા", "ଚାଉଳ", "ਚੌਲ", "চাউল")
        w("WHEAT", "गेहूं", "गहू", "கோதுமை", "গম", "గోధుమ", "ಗೋಧಿ", "ઘઉં", "ଗହମ", "ਕਣਕ", "ঘেঁহু")
        w("FLOUR", "आटा", "पीठ", "மாவு", "আটা", "పిండి", "ಹಿಟ್ಟು", "લોટ", "ଅଟା", "ਆਟਾ", "আটা")
        w("ATTA", "आटा", "पीठ", "மாவு", "আটা", "పిండి", "ಹಿಟ್ಟು", "લોટ", "ଅଟା", "ਆਟਾ", "আটা")
        w("MAIDA", "मैदा", "मैदा", "மைதா", "ময়দা", "మైదా", "ಮೈದಾ", "મેંદો", "ମଇଦା", "ਮੈਦਾ", "ময়দা")
        w("SUJI", "सूजी", "रवा", "ரவை", "সুজি", "రవ్వ", "ರವೆ", "સોજી", "ସୁଜି", "ਸੂਜੀ", "সুজি")
        w("RAVA", "सूजी", "रवा", "ரவை", "সুজি", "రవ్వ", "ರವೆ", "સોજી", "ସୁଜି", "ਸੂਜੀ", "সুজি")
        w("SUGAR", "चीनी", "साखर", "சர்க்கரை", "চিনি", "చక్కెర", "ಸಕ್ಕರೆ", "ખાંડ", "ଚିନି", "ਖੰਡ", "চেনি")
        w("JAGGERY", "गुड़", "गूळ", "வெல்லம்", "গুড়", "బెల్లం", "ಬೆಲ್ಲ", "ગોળ", "ଗୁଡ଼", "ਗੁੜ", "গুৰ")
        w("SALT", "नमक", "मीठ", "உப்பு", "লবণ", "ఉప్పు", "ಉಪ್ಪು", "મીઠું", "ଲୁଣ", "ਲੂਣ", "নিমখ")
        w("OIL", "तेल", "तेल", "எண்ணெய்", "তেল", "నూనె", "ಎಣ್ಣೆ", "તેલ", "ତେଲ", "ਤੇਲ", "তেল")
        w("GHEE", "घी", "तूप", "நெய்", "ঘি", "నెయ్యి", "ತುಪ್ಪ", "ઘી", "ଘିଅ", "ਘਿਓ", "ঘিউ")
        w("BUTTER", "मक्खन", "लोणी", "வெண்ணெய்", "মাখন", "వెన్న", "ಬೆಣ್ಣೆ", "માખણ", "ଲହୁଣୀ", "ਮੱਖਣ", "মাখন")
        w("MILK", "दूध", "दूध", "பால்", "দুধ", "పాలు", "ಹಾಲು", "દૂધ", "ଦୁଧ", "ਦੁੱਧ", "গাখীৰ")
        w("CURD", "दही", "दही", "தயிர்", "দই", "పెరుగు", "ಮೊಸರು", "દહીં", "ଦହି", "ਦਹੀਂ", "দৈ")
        w("PANEER", "पनीर", "पनीर", "பன்னீர்", "পনির", "పనీర్", "ಪನೀರ್", "પનીર", "ପନିର", "ਪਨੀਰ", "পনিৰ")
        w("BREAD", "ब्रेड", "ब्रेड", "ரொட்டி", "পাউরুটি", "బ్రెడ్", "ಬ್ರೆಡ್", "બ્રેડ", "ପାଉଁରୁଟି", "ਬਰੈੱਡ", "পাওৰুটি")
        w("EGG", "अंडा", "अंडे", "முட்டை", "ডিম", "గుడ్డు", "ಮೊಟ್ಟೆ", "ઈંડું", "ଅଣ୍ଡା", "ਆਂਡਾ", "কণী")
        w("TEA", "चाय", "चहा", "தேநீர்", "চা", "టీ", "ಚಹಾ", "ચા", "ଚା", "ਚਾਹ", "চাহ")
        w("COFFEE", "कॉफी", "कॉफी", "காபி", "কফি", "కాఫీ", "ಕಾಫಿ", "કોફી", "କଫି", "ਕੌਫੀ", "কফি")
        w("WATER", "पानी", "पाणी", "தண்ணீர்", "জল", "నీరు", "ನೀರು", "પાણી", "ପାଣି", "ਪਾਣੀ", "পানী")
        w("HONEY", "शहद", "मध", "தேன்", "মধু", "తేనె", "ಜೇನುತುಪ್ಪ", "મધ", "ମହୁ", "ਸ਼ਹਿਦ", "মৌ")

        // ---- Pulses ---------------------------------------------------------------
        w("DAL", "दाल", "डाळ", "பருப்பு", "ডাল", "పప్పు", "ಬೇಳೆ", "દાળ", "ଡାଲି", "ਦਾਲ", "দাইল")
        w("LENTIL", "दाल", "डाळ", "பருப்பு", "ডাল", "పప్పు", "ಬೇಳೆ", "દાળ", "ଡାଲି", "ਦਾਲ", "দাইল")
        w("GRAM", "चना", "हरभरा", "கடலை", "ছোলা", "శనగ", "ಕಡಲೆ", "ચણા", "ଛୋଲା", "ਛੋਲੇ", "বুট")
        w("CHANA", "चना", "हरभरा", "கடலை", "ছোলা", "శనగ", "ಕಡಲೆ", "ચણા", "ଛୋଲା", "ਛੋਲੇ", "বুট")
        w("PEAS", "मटर", "वाटाणा", "பட்டாணி", "মটর", "బఠానీ", "ಬಟಾಣಿ", "વટાણા", "ମଟର", "ਮਟਰ", "মটৰ")
        w("RAJMA", "राजमा", "राजमा", "ராஜ்மா", "রাজমা", "రాజ్మా", "ರಾಜ್ಮಾ", "રાજમા", "ରାଜମା", "ਰਾਜਮਾ", "ৰাজমা")

        // ---- Spices ---------------------------------------------------------------
        w("MASALA", "मसाला", "मसाला", "மசாலா", "মসলা", "మసాలా", "ಮಸಾಲೆ", "મસાલો", "ମସଲା", "ਮਸਾਲਾ", "মচলা")
        w("SPICE", "मसाला", "मसाला", "மசாலா", "মসলা", "మసాలా", "ಮಸಾಲೆ", "મસાલો", "ମସଲା", "ਮਸਾਲਾ", "মচলা")
        w("TURMERIC", "हल्दी", "हळद", "மஞ்சள்", "হলুদ", "పసుపు", "ಅರಿಶಿನ", "હળદર", "ହଳଦୀ", "ਹਲਦੀ", "হালধি")
        w("HALDI", "हल्दी", "हळद", "மஞ்சள்", "হলুদ", "పసుపు", "ಅರಿಶಿನ", "હળદર", "ହଳଦୀ", "ਹਲਦੀ", "হালধি")
        w("CHILLI", "मिर्च", "मिरची", "மிளகாய்", "লঙ্কা", "మిర్చి", "ಮೆಣಸಿನಕಾಯಿ", "મરચું", "ଲଙ୍କା", "ਮਿਰਚ", "জলকীয়া")
        w("CHILLY", "मिर्च", "मिरची", "மிளகாய்", "লঙ্কা", "మిర్చి", "ಮೆಣಸಿನಕಾಯಿ", "મરચું", "ଲଙ୍କା", "ਮਿਰਚ", "জলকীয়া")
        w("CUMIN", "जीरा", "जिरे", "சீரகம்", "জিরা", "జీలకర్ర", "ಜೀರಿಗೆ", "જીરું", "ଜିରା", "ਜੀਰਾ", "জিৰা")
        w("JEERA", "जीरा", "जिरे", "சீரகம்", "জিরা", "జీలకర్ర", "ಜೀರಿಗೆ", "જીરું", "ଜିରା", "ਜੀਰਾ", "জিৰা")
        w("CORIANDER", "धनिया", "धणे", "கொத்தமல்லி", "ধনে", "ధనియాలు", "ಕೊತ್ತಂಬರಿ", "ધાણા", "ଧନିଆ", "ਧਨੀਆ", "ধনিয়া")
        w("DHANIA", "धनिया", "धणे", "கொத்தமல்லி", "ধনে", "ధనియాలు", "ಕೊತ್ತಂಬರಿ", "ધાણા", "ଧନିଆ", "ਧਨੀਆ", "ধনিয়া")
        w("MUSTARD", "सरसों", "मोहरी", "கடுகு", "সরিষা", "ఆవాలు", "ಸಾಸಿವೆ", "રાઈ", "ସୋରିଷ", "ਸਰ੍ਹੋਂ", "সৰিয়হ")
        w("CARDAMOM", "इलायची", "वेलची", "ஏலக்காய்", "এলাচ", "యాలకులు", "ಏಲಕ್ಕಿ", "એલચી", "ଅଳେଇଚ", "ਇਲਾਇਚੀ", "এলাচি")
        w("ELAICHI", "इलायची", "वेलची", "ஏலக்காய்", "এলাচ", "యాలకులు", "ಏಲಕ್ಕಿ", "એલચી", "ଅଳେଇଚ", "ਇਲਾਇਚੀ", "এলাচি")
        w("CLOVE", "लौंग", "लवंग", "கிராம்பு", "লবঙ্গ", "లవంగం", "ಲವಂಗ", "લવિંગ", "ଲବଙ୍ଗ", "ਲੌਂਗ", "লং")
        w("CINNAMON", "दालचीनी", "दालचिनी", "பட்டை", "দারচিনি", "దాల్చిన చెక్క", "ದಾಲ್ಚಿನ್ನಿ", "તજ", "ଦାରୁଚିନି", "ਦਾਲਚੀਨੀ", "দালচেনি")
        w("GINGER", "अदरक", "आले", "இஞ்சி", "আদা", "అల్లం", "ಶುಂಠಿ", "આદુ", "ଅଦା", "ਅਦਰਕ", "আদা")
        w("GARLIC", "लहसुन", "लसूण", "பூண்டு", "রসুন", "వెల్లుల్లి", "ಬೆಳ್ಳುಳ್ಳಿ", "લસણ", "ରସୁଣ", "ਲਸਣ", "নহৰু")
        w("FENUGREEK", "मेथी", "मेथी", "வெந்தயம்", "মেথি", "మెంతులు", "ಮೆಂತ್ಯ", "મેથી", "ମେଥି", "ਮੇਥੀ", "মেথি")
        w("METHI", "मेथी", "मेथी", "வெந்தயம்", "মেথি", "మెంతులు", "ಮೆಂತ್ಯ", "મેથી", "ମେଥି", "ਮੇਥੀ", "মেথি")

        // ---- Vegetables -------------------------------------------------------------
        w("VEGETABLE", "सब्जी", "भाजी", "காய்கறி", "সবজি", "కూరగాయ", "ತರಕಾರಿ", "શાકભાજી", "ପନିପରିବା", "ਸਬਜ਼ੀ", "শাক-পাচলি")
        w("POTATO", "आलू", "बटाटा", "உருளைக்கிழங்கு", "আলু", "బంగాళదుంప", "ಆಲೂಗಡ್ಡೆ", "બટાકા", "ଆଳୁ", "ਆਲੂ", "আলু")
        w("ONION", "प्याज", "कांदा", "வெங்காயம்", "পেঁয়াজ", "ఉల్లిపాయ", "ಈರುಳ್ಳಿ", "ડુંગળી", "ପିଆଜ", "ਪਿਆਜ਼", "পিয়াঁজ")
        w("TOMATO", "टमाटर", "टोमॅटो", "தக்காளி", "টমেটো", "టమాటా", "ಟೊಮೆಟೊ", "ટામેટા", "ଟମାଟୋ", "ਟਮਾਟਰ", "বিলাহী")
        w("BRINJAL", "बैंगन", "वांगे", "கத்தரிக்காய்", "বেগুন", "వంకాయ", "ಬದನೆಕಾಯಿ", "રીંગણ", "ବାଇଗଣ", "ਬੈਂਗਣ", "বেঙেনা")
        w("CABBAGE", "पत्तागोभी", "कोबी", "முட்டைகோஸ்", "বাঁধাকপি", "క్యాబేజీ", "ಎಲೆಕೋಸು", "કોબીજ", "ବନ୍ଧାକୋବି", "ਬੰਦ ਗੋਭੀ", "বন্ধাকবি")
        w("CAULIFLOWER", "फूलगोभी", "फुलकोबी", "காலிஃபிளவர்", "ফুলকপি", "కాలీఫ్లవర్", "ಹೂಕೋಸು", "ફૂલકોબી", "ଫୁଲକୋବି", "ਫੁੱਲ ਗੋਭੀ", "ফুলকবি")
        w("CARROT", "गाजर", "गाजर", "கேரட்", "গাজর", "క్యారెట్", "ಕ್ಯಾರೆಟ್", "ગાજર", "ଗାଜର", "ਗਾਜਰ", "গাজৰ")
        w("SPINACH", "पालक", "पालक", "கீரை", "পালং", "పాలకూర", "ಪಾಲಕ್", "પાલક", "ପାଳଙ୍ଗ", "ਪਾਲਕ", "পালেং")
        w("LEMON", "नींबू", "लिंबू", "எலுமிச்சை", "লেবু", "నిమ్మ", "ನಿಂಬೆ", "લીંબુ", "ଲେମ୍ବୁ", "ਨਿੰਬੂ", "নেমু")
        w("CUCUMBER", "खीरा", "काकडी", "வெள்ளரி", "শসা", "దోసకాయ", "ಸೌತೆಕಾಯಿ", "કાકડી", "କାକୁଡ଼ି", "ਖੀਰਾ", "তিয়ঁহ")
        w("PUMPKIN", "कद्दू", "भोपळा", "பூசணி", "কুমড়ো", "గుమ్మడికాయ", "ಕುಂಬಳಕಾಯಿ", "કોળું", "କଖାରୁ", "ਕੱਦੂ", "ৰঙালাও")
        w("RADISH", "मूली", "मुळा", "முள்ளங்கி", "মূলা", "ముల్లంగి", "ಮೂಲಂಗಿ", "મૂળા", "ମୂଳା", "ਮੂਲੀ", "মূলা")

        // ---- Fruit ------------------------------------------------------------------
        w("FRUIT", "फल", "फळ", "பழம்", "ফল", "పండు", "ಹಣ್ಣು", "ફળ", "ଫଳ", "ਫਲ", "ফল")
        w("APPLE", "सेब", "सफरचंद", "ஆப்பிள்", "আপেল", "యాపిల్", "ಸೇಬು", "સફરજન", "ସେଓ", "ਸੇਬ", "আপেল")
        w("BANANA", "केला", "केळी", "வாழைப்பழம்", "কলা", "అరటి", "ಬಾಳೆಹಣ್ಣು", "કેળું", "କଦଳୀ", "ਕੇਲਾ", "কল")
        w("MANGO", "आम", "आंबा", "மாம்பழம்", "আম", "మామిడి", "ಮಾವು", "કેરી", "ଆମ୍ବ", "ਅੰਬ", "আম")
        w("ORANGE", "संतरा", "संत्रे", "ஆரஞ்சு", "কমলা", "నారింజ", "ಕಿತ್ತಳೆ", "નારંગી", "କମଳା", "ਸੰਤਰਾ", "কমলা")
        w("GRAPES", "अंगूर", "द्राक्षे", "திராட்சை", "আঙুর", "ద్రాక్ష", "ದ್ರಾಕ್ಷಿ", "દ્રાક્ષ", "ଅଙ୍ଗୁର", "ਅੰਗੂਰ", "আঙুৰ")
        w("COCONUT", "नारियल", "नारळ", "தேங்காய்", "নারকেল", "కొబ్బరి", "ತೆಂಗಿನಕಾಯಿ", "નાળિયેર", "ନଡ଼ିଆ", "ਨਾਰੀਅਲ", "নাৰিকল")

        // ---- From the cold counter ---------------------------------------------------
        w("CHICKEN", "चिकन", "चिकन", "கோழி", "মুরগি", "చికెన్", "ಚಿಕನ್", "ચિકન", "ଚିକେନ", "ਚਿਕਨ", "কুকুৰা")
        w("MUTTON", "मटन", "मटण", "ஆட்டிறைச்சி", "মটন", "మటన్", "ಮಟನ್", "મટન", "ମଟନ", "ਮਟਨ", "ছাগলীৰ মাংস")
        w("FISH", "मछली", "मासे", "மீன்", "মাছ", "చేప", "ಮೀನು", "માછલી", "ମାଛ", "ਮੱਛੀ", "মাছ")
        w("MEAT", "मांस", "मांस", "இறைச்சி", "মাংস", "మాంసం", "ಮಾಂಸ", "માંસ", "ମାଂସ", "ਮਾਸ", "মাংস")
        w("PRAWN", "झींगा", "कोळंबी", "இறால்", "চিংড়ি", "రొయ్య", "ಸೀಗಡಿ", "ઝીંગા", "ଚିଙ୍ଗୁଡ଼ି", "ਝੀਂਗਾ", "ইচা")

        // ---- Household ---------------------------------------------------------------
        w("SOAP", "साबुन", "साबण", "சோப்பு", "সাবান", "సబ్బు", "ಸಾಬೂನು", "સાબુ", "ସାବୁନ", "ਸਾਬਣ", "চাবোন")
        w("SHAMPOO", "शैम्पू", "शॅम्पू", "ஷாம்பு", "শ্যাম্পু", "షాంపూ", "ಶಾಂಪೂ", "શેમ્પૂ", "ଶାମ୍ପୁ", "ਸ਼ੈਂਪੂ", "শ্বেম্পু")
        w("TOOTHPASTE", "टूथपेस्ट", "टूथपेस्ट", "பற்பசை", "টুথপেস্ট", "టూత్ పేస్ట్", "ಟೂತ್ ಪೇಸ್ಟ್", "ટૂથપેસ્ટ", "ଟୁଥପେଷ୍ଟ", "ਟੂਥਪੇਸਟ", "টুথপেষ্ট")
        w("TOOTHBRUSH", "टूथब्रश", "टूथब्रश", "பல் துலக்கி", "টুথব্রাশ", "టూత్ బ్రష్", "ಟೂತ್ ಬ್ರಷ್", "ટૂથબ્રશ", "ଟୁଥବ୍ରସ୍", "ਟੂਥਬੁਰਸ਼", "টুথব্ৰাছ")
        w("BRUSH", "ब्रश", "ब्रश", "தூரிகை", "ব্রাশ", "బ్రష్", "ಬ್ರಷ್", "બ્રશ", "ବ୍ରସ୍", "ਬੁਰਸ਼", "ব্ৰাছ")
        w("POWDER", "पाउडर", "पावडर", "பொடி", "গুঁড়ো", "పొడి", "ಪುಡಿ", "પાવડર", "ଗୁଣ୍ଡ", "ਪਾਊਡਰ", "গুৰি")
        w("CREAM", "क्रीम", "क्रीम", "கிரீம்", "ক্রিম", "క్రీమ్", "ಕ್ರೀಮ್", "ક્રીમ", "କ୍ରିମ", "ਕਰੀਮ", "ক্ৰীম")
        w("TOWEL", "तौलिया", "टॉवेल", "துண்டு", "তোয়ালে", "తువ్వాలు", "ಟವೆಲ್", "ટુવાલ", "ତଉଲିଆ", "ਤੌਲੀਆ", "তোৱালে")
        w("BUCKET", "बाल्टी", "बादली", "வாளி", "বালতি", "బకెట్", "ಬಕೆಟ್", "ડોલ", "ବାଲ୍ଟି", "ਬਾਲਟੀ", "বাল্টি")
        w("BROOM", "झाड़ू", "झाडू", "துடைப்பம்", "ঝাড়ু", "చీపురు", "ಪೊರಕೆ", "સાવરણી", "ଝାଡ଼ୁ", "ਝਾੜੂ", "ঝাৰু")
        w("CANDLE", "मोमबत्ती", "मेणबत्ती", "மெழுகுவர்த்தி", "মোমবাতি", "కొవ్వొత్తి", "ಮೇಣದಬತ್ತಿ", "મીણબત્તી", "ମହମବତୀ", "ਮੋਮਬੱਤੀ", "মমবাতি")
        w("MATCHBOX", "माचिस", "काडेपेटी", "தீப்பெட்டி", "দেশলাই", "అగ్గిపెట్టె", "ಬೆಂಕಿಪೊಟ್ಟಣ", "દીવાસળી", "ଦିଆସିଲି", "ਮਾਚਿਸ", "ম'চ")
        w("BATTERY", "बैटरी", "बॅटरी", "மின்கலம்", "ব্যাটারি", "బ్యాటరీ", "ಬ್ಯಾಟರಿ", "બેટરી", "ବ୍ୟାଟେରୀ", "ਬੈਟਰੀ", "বেটাৰি")
        w("BULB", "बल्ब", "बल्ब", "விளக்கு", "বাল্ব", "బల్బ్", "ಬಲ್ಬ್", "બલ્બ", "ବଲ୍ବ", "ਬਲਬ", "বাল্ব")

        // ---- Snacks and the shelf beside the till -------------------------------------
        w("BISCUIT", "बिस्कुट", "बिस्किट", "பிஸ்கட்", "বিস্কুট", "బిస్కెట్", "ಬಿಸ್ಕತ್ತು", "બિસ્કિટ", "ବିସ୍କୁଟ", "ਬਿਸਕੁਟ", "বিস্কুট")
        w("CHOCOLATE", "चॉकलेट", "चॉकलेट", "சாக்லேட்", "চকোলেট", "చాక్లెట్", "ಚಾಕೊಲೇಟ್", "ચોકલેટ", "ଚକୋଲେଟ", "ਚਾਕਲੇਟ", "চকলেট")
        w("NOODLES", "नूडल्स", "नूडल्स", "நூடுல்ஸ்", "নুডলস", "నూడుల్స్", "ನೂಡಲ್ಸ್", "નૂડલ્સ", "ନୁଡୁଲ୍ସ", "ਨੂਡਲਜ਼", "নুডলছ")
        w("JUICE", "जूस", "ज्यूस", "சாறு", "জুস", "జ్యూస్", "ಜ್ಯೂಸ್", "જ્યુસ", "ଜୁସ୍", "ਜੂਸ", "জুচ")
        w("CURRY", "करी", "कढी", "கறி", "কারি", "కూర", "ಸಾರು", "કરી", "ତରକାରୀ", "ਕੜੀ", "তৰকাৰী")
        w("SUNFLOWER", "सूरजमुखी", "सूर्यफूल", "சூரியகாந்தி", "সূর্যমুখী", "పొద్దుతిరుగుడు", "ಸೂರ್ಯಕಾಂತಿ", "સૂરજમુખી", "ସୂର୍ଯ୍ୟମୁଖୀ", "ਸੂਰਜਮੁਖੀ", "সূৰ্যমুখী")
        // Odia and Assamese left blank: both borrow the English word as often as not,
        // and a guess here would be a guess printed on a bill. They fall through to
        // being spelled in their own letters instead.
        w("CANDY", "टॉफी", "गोळी", "மிட்டாய்", "ক্যান্ডি", "మిఠాయి", "ಮಿಠಾಯಿ", "ટોફી", "", "ਟਾਫੀ", "")
        w("TOFFEE", "टॉफी", "गोळी", "மிட்டாய்", "টফি", "మిఠాయి", "ಮಿಠಾಯಿ", "ટોફી", "", "ਟਾਫੀ", "")
        w("PICKLE", "अचार", "लोणचे", "ஊறுகாய்", "আচার", "ఊరగాయ", "ಉಪ್ಪಿನಕಾಯಿ", "અથાણું", "ଆଚାର", "ਆਚਾਰ", "আচাৰ")
        w("ACHAR", "अचार", "लोणचे", "ஊறுகாய்", "আচার", "ఊరగాయ", "ಉಪ್ಪಿನಕಾಯಿ", "અથાણું", "ଆଚାର", "ਆਚਾਰ", "আচাৰ")
        w("PAPAD", "पापड़", "पापड", "அப்பளம்", "পাঁপড়", "అప్పడం", "ಹಪ್ಪಳ", "પાપડ", "ପାପଡ଼", "ਪਾਪੜ", "পাপৰ")
        w("SAUCE", "सॉस", "सॉस", "சாஸ்", "সস", "సాస్", "ಸಾಸ್", "સોસ", "ସସ୍", "ਸਾਸ", "চছ")

        // ---- Stationery ----------------------------------------------------------------
        w("PEN", "कलम", "पेन", "பேனா", "কলম", "పెన్ను", "ಪೆನ್ನು", "પેન", "କଲମ", "ਪੈੱਨ", "কলম")
        w("PENCIL", "पेंसिल", "पेन्सिल", "பென்சில்", "পেন্সিল", "పెన్సిల్", "ಪೆನ್ಸಿಲ್", "પેન્સિલ", "ପେନ୍ସିଲ", "ਪੈਨਸਿਲ", "পেন্সিল")
        w("NOTEBOOK", "कॉपी", "वही", "நோட்டுப் புத்தகம்", "খাতা", "నోట్ బుక్", "ನೋಟ್ ಬುಕ್", "નોટબુક", "ଖାତା", "ਕਾਪੀ", "খাতা")
        w("BOOK", "किताब", "पुस्तक", "புத்தகம்", "বই", "పుస్తకం", "ಪುಸ್ತಕ", "પુસ્તક", "ବହି", "ਕਿਤਾਬ", "কিতাপ")
        w("PAPER", "कागज", "कागद", "காகிதம்", "কাগজ", "కాగితం", "ಕಾಗದ", "કાગળ", "କାଗଜ", "ਕਾਗਜ਼", "কাগজ")
        w("BAG", "थैला", "पिशवी", "பை", "ব্যাগ", "సంచి", "ಚೀಲ", "થેલી", "ବ୍ୟାଗ", "ਥੈਲਾ", "বেগ")
    }

    /** One row of a table, in [PrintLanguage.Language] order after English. */
    private fun MutableMap<String, Array<String>>.w(
        key: String, hi: String, mr: String, ta: String, bn: String, te: String,
        kn: String, gu: String, or: String, pa: String, `as`: String
    ) {
        put(key.uppercase(Locale.ROOT), arrayOf(hi, mr, ta, bn, te, kn, gu, or, pa, `as`))
    }
}
