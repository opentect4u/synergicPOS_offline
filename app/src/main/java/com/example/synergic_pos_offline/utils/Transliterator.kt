package com.example.synergic_pos_offline.utils

import java.util.Locale

/**
 * Spells a product name in the script of the till's print language.
 *
 * ## What this is, and what it is not
 *
 * This is **transliteration, not translation**. It writes the name the shop typed in
 * using the letters of another script, so a customer who cannot read the Latin
 * alphabet can still read the name aloud and recognise what they bought. TOOTHPASTE
 * becomes टूथ्पेस्ट - which reads as "toothpaste" - and not दंतमंजन, which is the Hindi
 * word for it. There is no way to do the latter on a till with no network: it would
 * need a dictionary of every product name any shop might ever type.
 *
 * For Indian retail that is usually the right answer anyway, because most of what is
 * on a shelf is a brand name. PARLE-G is PARLE-G in every language; what changes is
 * whether the customer can read the letters.
 *
 * ## How good it is
 *
 * Approximate, and deliberately so. English spelling does not state its own
 * pronunciation - "tough", "though" and "through" share four letters and three
 * sounds - so any rule-based reading of it is a good guess rather than an answer. The
 * rules here are tuned for what actually appears in a product master: brand names,
 * romanised Hindi (ATTA, DAL, GHEE) and plain English nouns. The Print Language
 * screen shows this shop's own product names as they will print, so an operator can
 * judge the result on their own catalogue before a customer does.
 *
 * ## What is left alone
 *
 * A token carrying a digit (500ML, 1KG, PARLE-20), a known unit, and anything already
 * written in a non-Latin script. A shop that has typed its product names in Hindi
 * already gets exactly what it typed - this only ever fires on Latin letters.
 *
 * ## The mechanism
 *
 * Every script here is Brahmic and shares one structure: a consonant carries an
 * inherent "a", a vowel sign replaces it, and a virama cancels it. So there is one
 * algorithm and one table per script, rather than ten transliterators. Latin is read
 * into a list of [Sound]s once, and each script prints that same list its own way.
 */
object Transliterator {

    /**
     * [text] written in [language]'s script, or [text] itself where there is nothing
     * to do - English, an empty name, or a name already in another script.
     */
    fun to(language: PrintLanguage.Language, text: String?): String {
        val raw = text ?: return ""
        val script = scriptFor(language) ?: return raw
        if (raw.isBlank() || !hasLatinLetters(raw)) return raw
        return runCatching { transliterate(script, raw) }.getOrDefault(raw)
    }

    /** Whether slips in [language] have their product names respelled at all. */
    fun applies(language: PrintLanguage.Language): Boolean = scriptFor(language) != null

    // ---- Splitting a name into the parts that get respelled --------------------

    /**
     * Walks [text] a token at a time, respelling the words and carrying everything
     * between them through untouched.
     *
     * The separators matter as much as the words: "PARLE-G 100G" has to come back
     * with its hyphen and its space where they were, or the name stops being the name.
     */
    private fun transliterate(script: Script, text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                out.append(text[i])
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j].isLetter()) j++
            out.append(token(script, text.substring(i, j), countedBefore(text, i)))
            i = j
        }
        return out.toString()
    }

    /**
     * Whether a number runs up to the token starting at [start].
     *
     * This is what tells a unit from a letter. The G of "500 G" is grams and the G of
     * "PARLE-G" is a letter of the name, and the only thing separating them is what
     * comes before: a figure, or anything else.
     */
    private fun countedBefore(text: String, start: Int): Boolean {
        var k = start - 1
        while (k >= 0 && text[k] == ' ') k--
        return k >= 0 && text[k].isDigit()
    }

    /** One run of letters, respelled - or kept, where respelling it would lose it. */
    private fun token(script: Script, word: String, countedBefore: Boolean): String {
        // A word already in a script of its own is one somebody has settled - either
        // the shop typed it that way, or [ProductName] has just translated it. Either
        // way there is nothing here that could improve on it. Asked per word rather
        // than of the whole name, so "बासमती RICE" still gets its second word done.
        if (hasNonLatinLetters(word)) return word
        val lower = word.lowercase(Locale.ROOT)
        // A unit is read as a quantity, not as a word. "1 KG" respelled phonetically
        // is still legible, but it is no longer the symbol printed in the quantity
        // column beside it, and the two have to match.
        if (countedBefore && lower in UNITS) return word
        // An acronym or a single letter is read out letter by letter, which is what a
        // reader does with it too: the G of PARLE-G is "jee", not a consonant with no
        // vowel. Spelled through the same rules by way of each letter's English name.
        //
        // "y" counts as a vowel for this one question. It is the only vowel FRY, DRY
        // and TRY have, and read as a consonant they would be spelled out letter by
        // letter - FISH FRY came out as "मछली एफआरवै".
        if (word.length <= 4 && lower.none { it in "aeiouy" }) {
            return word.map { letter ->
                LETTER_NAMES[letter.lowercaseChar()]?.let { render(script, sounds(it)) } ?: letter.toString()
            }.joinToString("")
        }
        val sounds = sounds(lower)
        return if (sounds.isEmpty()) word else render(script, sounds)
    }

    private fun hasLatinLetters(text: String) = text.any { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * Whether the name is already in a script of its own.
     *
     * A shop that has typed its catalogue in Hindi has said what it wants printed, and
     * guessing at it a second time could only make it worse.
     */
    private fun hasNonLatinLetters(text: String) =
        text.any { it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z' }

    /** Read as a quantity wherever they appear, in any language. */
    private val UNITS = setOf(
        "kg", "kgs", "g", "gm", "gms", "gram", "grams", "mg", "ml", "l", "ltr", "ltrs",
        "litre", "liter", "pc", "pcs", "pkt", "pkts", "box", "no", "nos", "dz", "dzn",
        "mm", "cm", "m", "ft", "inch", "set", "pair", "bdl", "bag", "tin", "jar"
    )

    /**
     * How each letter of the alphabet is said, written the way this file reads Latin.
     *
     * Saves a table of letter names per script: the names go through the same rules
     * as any other word, so "gee" lands as जी in Devanagari and ஜீ in Tamil without
     * either being written down.
     */
    private val LETTER_NAMES = mapOf(
        'a' to "ay", 'b' to "bee", 'c' to "see", 'd' to "dee", 'e' to "ee", 'f' to "ef",
        'g' to "jee", 'h' to "aitch", 'i' to "ai", 'j' to "jay", 'k' to "kay",
        'l' to "el", 'm' to "em", 'n' to "en", 'o' to "o", 'p' to "pee", 'q' to "kyu",
        'r' to "aar", 's' to "es", 't' to "tee", 'u' to "yu", 'v' to "vee",
        'w' to "dablyu", 'x' to "eks", 'y' to "wai", 'z' to "zed"
    )

    // ---- Latin in ---------------------------------------------------------------

    /** One sound of the word being read - see [sounds]. */
    private sealed interface Sound

    /** A consonant, keyed as in [CONSONANTS]. */
    private data class Cons(val key: String) : Sound

    /** A vowel, keyed as in [VOWELS]. `a` is the one a consonant carries by default. */
    private data class Vowel(val key: String) : Sound

    /** The nasal that rides on the vowel before a stop - the ं of संडविच. */
    private data object Nasal : Sound

    /**
     * Reads a lower-case Latin word into the sounds it is made of.
     *
     * Longest match first, so "sh" is read before "s" and "ee" before "e". Three
     * things happen before the scan proper, each of them the difference between a
     * name a reader recognises and one they do not:
     *
     *  * a doubled consonant is collapsed - BUTTER is बटर, not बट्टर;
     *  * a silent final "e" is dropped and the vowel before it lengthened, so COLGATE
     *    is कोलगेट rather than कोलगेटे, and PASTE is पेस्ट rather than पास्टे;
     *  * "kn", "wr", "ps" and "pn" lose their silent first letter at the start of a
     *    word.
     */
    private fun sounds(raw: String): List<Sound> {
        var word = collapseDoubles(raw)
        word = dropSilentStart(word)
        word = applyFinalE(word)
        word = lengthenOpenVowels(word)
        word = applyWordEndings(word)

        val out = mutableListOf<Sound>()
        var i = 0
        while (i < word.length) {
            // A nasal directly before another consonant is written on the vowel
            // before it rather than as a letter of its own - CANDY is कंडि. Not at the
            // end of a word, where the nasal is the last thing said.
            val c = word[i]
            if ((c == 'n' || c == 'm') && i + 1 < word.length && !isVowelLetter(word[i + 1]) &&
                word[i + 1] != 'h' && out.isNotEmpty()
            ) {
                out.add(Nasal)
                i++
                continue
            }
            val matched = MULTI.firstOrNull { (spelling, _) -> word.startsWith(spelling, i) }
            val read = matched?.second?.invoke(word, i)
            // A rule that read nothing has declined this position - see [closed] -
            // so the letters go back to being read one at a time.
            if (matched != null && !read.isNullOrEmpty()) {
                out.addAll(read)
                i += matched.first.length
                continue
            }
            out.addAll(single(word, i))
            i++
        }
        return out
    }

    /** BUTTER, not BUTTTER: an English double is one sound, and so is a Hindi one. */
    private fun collapseDoubles(word: String): String {
        val out = StringBuilder()
        for (c in word) {
            // Only consonants. "ee", "oo" and "aa" are vowels in their own right and
            // collapsing them would turn GHEE into घे and COOKIE into कोकि.
            if (out.isNotEmpty() && out.last() == c && !isVowelLetter(c)) continue
            out.append(c)
        }
        return out.toString()
    }

    private fun dropSilentStart(word: String): String = when {
        word.length > 2 && (word.startsWith("kn") || word.startsWith("gn")) -> word.substring(1)
        word.length > 2 && word.startsWith("wr") -> word.substring(1)
        word.length > 2 && (word.startsWith("ps") || word.startsWith("pn")) -> word.substring(1)
        else -> word
    }

    /**
     * The silent "e" that ends an English word, and what it does to the vowel before it.
     *
     * Left in place it becomes a syllable that is not said - COLGATE would print as
     * कोलगेटे. Taken away on its own it loses the sound it was marking, and GATE would
     * print as गट. So it is dropped *and* the vowel it governs is lengthened, which is
     * the job that "e" was doing there in the first place.
     *
     * Up to two consonants may stand between, which is what makes PASTE, TASTE and
     * WASTE come out right along with GATE and NOTE.
     */
    private fun applyFinalE(word: String): String {
        if (word.length < 3 || !word.endsWith("e")) return word
        val body = word.dropLast(1)
        // "GHEE", "COFFEE", "FREE": the e before this one is half of a vowel of its
        // own, and taking this one away would leave घे where घी belongs.
        if (isVowelLetter(body.last())) return word
        // Something else in the word has to be a vowel, or dropping this one leaves a
        // word with no vowel at all.
        if (body.none { isVowelLetter(it) }) return word
        var cluster = body.takeLastWhile { !isVowelLetter(it) }
        if (cluster.isEmpty() || cluster.length > 2) return body
        // The final e was also softening the consonant in front of it: RICE is राइस
        // and not राइक, PAGE is पेज and not पेग. Softened before it is dropped, since
        // once it has gone there is nothing left to say so.
        if (cluster.length == 1) {
            when (cluster) {
                "c" -> cluster = "s"
                "g" -> cluster = "j"
            }
        }
        // PARLE, NESTLE, TITLE: an e after l or r is said, not silent. Dropping it
        // would turn पार्ले into पेर्ल - a different name, on a customer's bill.
        if (cluster.last() == 'l' || cluster.last() == 'r') return word
        val stem = body.dropLast(body.takeLastWhile { !isVowelLetter(it) }.length)
        val governed = stem.lastOrNull() ?: return body
        if (!isVowelLetter(governed) || (stem.length >= 2 && isVowelLetter(stem[stem.length - 2]))) {
            return stem + cluster
        }
        val lengthened = when (governed) {
            'a' -> "ei"     // GATE  -> गेट
            'i' -> "aai"    // WIPE  -> वाइप
            'o' -> "oa"     // NOTE  -> नोट
            'u' -> "yu"     // CUTE  -> क्यूट
            'e' -> "ee"     // THESE -> थीस
            else -> return stem + cluster
        }
        return stem.dropLast(1) + lengthened + cluster
    }

    /**
     * The vowel of an open syllable, which these languages hear long.
     *
     * A romanised Indian name spells with one letter what Devanagari writes with a
     * sign: TATA, ATTA and CHAWAL are टाटा, आटा and चावल, and read short they come out
     * as टटा, अटा and चवल - close enough to be recognised, far enough to look wrong on
     * a bill. The pattern is narrow on purpose: a single "a", one consonant, then
     * another vowel. TOOTHPASTE and SALT have a consonant pair after their "a" and are
     * left alone, which is right - सल्ट is what SALT should be.
     *
     * It over-fires on the odd English word (MAGGI becomes मागी rather than मगी), which
     * is the cost of not knowing which language a name was borrowed from. The Print
     * Language screen shows the shop what its own catalogue does.
     */
    private fun lengthenOpenVowels(word: String): String {
        val out = StringBuilder()
        for (i in word.indices) {
            val letter = word[i]
            val open = (letter == 'a' || letter == 'o') &&
                i + 2 < word.length &&
                !isVowelLetter(word[i + 1]) && word[i + 1] != 'h' &&
                isVowelLetter(word[i + 2]) &&
                (i == 0 || !isVowelLetter(word[i - 1]))
            // "oa" is this file's spelling of the long o, so SODA and HOTEL keep the
            // sound SOAP has rather than the clipped one of SHOP.
            out.append(if (!open) letter else if (letter == 'a') "aa" else "oa")
        }
        return out.toString()
    }

    /**
     * The vowels an Indian reader expects to be long at the end of a word.
     *
     * A single letter carries no length in Latin, so ATTA, MASALA, MAGGI and BASMATI
     * all end in something the scripts here would write long - आटा, मसाला, मैगी,
     * बासमती. Left short they come out as आट and मगि, which are different words.
     *
     * "-er" and "-or" go the other way: BUTTER and DOCTOR are बटर and डॉक्टर, where
     * the vowel is the faintest in the word rather than an "e" or an "o" at all.
     */
    private fun applyWordEndings(word: String): String {
        if (word.length < 3) return word
        if (word.endsWith("er") || word.endsWith("or")) return word.dropLast(2) + "ar"
        val last = word.last()
        val before = word[word.length - 2]
        if (isVowelLetter(before)) return word          // already a vowel pair
        return when (last) {
            'a' -> word + "a"                // ATTA  -> आटा
            'i' -> word.dropLast(1) + "ee"   // MAGGI -> मगी
            // A "y" that is the word's only vowel is the long one of FRY and DRY;
            // one that follows a vowel elsewhere in the word is the short ending of
            // CANDY and CITY. Told apart by whether there is another vowel at all.
            'y' -> word.dropLast(1) +
                if (word.dropLast(1).any { isVowelLetter(it) }) "ee" else "aai"
            else -> word
        }
    }

    /**
     * Which "a" this is - the three English gets out of one letter in a closed
     * syllable, which is most of them once [lengthenOpenVowels] has taken the open
     * ones.
     *
     *  * before "l" plus a consonant it is the aw of SALT and MALT - सॉल्ट, সল্ট;
     *  * before "r" it is the long one of CAR and CARD;
     *  * otherwise it is the flat a of AND, HAND and CANDY, which every one of these
     *    scripts spells with a sign of its own.
     */
    private fun shortA(word: String, i: Int): String {
        val next = word.getOrNull(i + 1) ?: return "a"
        if (isVowelLetter(next)) return "a"
        val after = word.getOrNull(i + 2)
        return when {
            // A word *ending* in -al is a faint one - TOTAL, METAL, CHAWAL - while an
            // "al" with a consonant behind it is the aw of SALT and MALT. Treating
            // the two alike turned चावल into चावॉल.
            next == 'l' && after == null -> "a"
            next == 'l' && after != null && !isVowelLetter(after) -> "oshort"
            // Long in CAR and BAR, where it is the only vowel; the faint one of
            // BUTTER, SUGAR and DOCTOR where it closes a word that already has one.
            // [applyWordEndings] has turned those endings into "ar" by this point.
            next == 'r' ->
                if (i + 2 >= word.length && word.take(i).any { isVowelLetter(it) }) "a" else "aa"
            else -> "ae"
        }
    }

    private fun isVowelLetter(c: Char) = c in "aeiou"

    /** The spellings worth more than one letter, longest first. */
    private val MULTI: List<Pair<String, (String, Int) -> List<Sound>>> = listOf(
        // Consonants
        "tch" to c("ch"), "sch" to c("s", "k"), "chh" to c("chh"),
        "ch" to c("ch"), "sh" to c("sh"), "th" to c("th"), "ph" to c("ph"),
        "gh" to c("gh"), "kh" to c("kh"), "bh" to c("bh"), "dh" to c("dh"),
        "jh" to c("jh"), "zh" to c("j"), "ck" to c("k"), "qu" to c("k", "v"),
        "wh" to c("v"), "ng" to { _, _ -> listOf(Nasal, Cons("g")) },
        // Vowels
        // English "ai" is the vowel of PLAIN, TRAIN and MAIN, which these scripts
        // write with the "e" sign - प्लेन, not प्लैन.
        // Before "ou" in the list, so it wins: the ou of SOUP and GROUP is a long u,
        // while the bare one of SOUR and ROUND is the diphthong below.
        "oup" to { _, _ -> listOf(Vowel("uu"), Cons("p")) },
        "aa" to v("aa"), "ee" to v("ii"), "oo" to v("uu"), "ai" to v("e"),
        "ea" to v("ii"), "ie" to v("ii"), "ei" to v("e"),
        "oa" to v("o"), "oe" to v("o"), "ou" to v("au"), "au" to v("au"),
        "ue" to v("uu"),
        "eu" to { _, _ -> listOf(Cons("y"), Vowel("uu")) },
        "ew" to { _, _ -> listOf(Cons("y"), Vowel("uu")) },
        "oi" to { _, _ -> listOf(Vowel("o"), Cons("y")) },
        "yu" to { _, _ -> listOf(Cons("y"), Vowel("uu")) },
        // These four are a vowel only when nothing follows them. Before another vowel
        // the w and the y are consonants doing their own job: CHAWAL is चावल, not
        // चौअल, and PAYAL is पायल rather than पेअल.
        "ay" to closed(v("e")), "aw" to closed(v("au")), "ow" to closed(v("au")),
        "oy" to closed { _, _ -> listOf(Vowel("o"), Cons("y")) }
    )

    /** A vowel pair that only reads as one where no vowel follows it. */
    private fun closed(read: (String, Int) -> List<Sound>): (String, Int) -> List<Sound> =
        { word, i ->
            if (i + 2 < word.length && isVowelLetter(word[i + 2])) NOT_MATCHED else read(word, i)
        }

    /** Returned by [closed] to mean "read this the long way instead". */
    private val NOT_MATCHED = emptyList<Sound>()

    private fun c(vararg keys: String): (String, Int) -> List<Sound> =
        { _, _ -> keys.map { Cons(it) } }

    private fun v(key: String): (String, Int) -> List<Sound> = { _, _ -> listOf(Vowel(key)) }

    /** One letter, where nothing longer matched. */
    private fun single(word: String, i: Int): List<Sound> = when (val c = word[i]) {
        'a' -> listOf(Vowel(shortA(word, i)))
        'e' -> listOf(Vowel("e"))
        'i' -> listOf(Vowel("i"))
        // The o of HOT and SHOP, not the one of SOAP - an open o was already
        // lengthened by [lengthenOpenVowels], so anything still bare here is closed.
        'o' -> listOf(Vowel(if (i + 1 < word.length && isVowelLetter(word[i + 1])) "o" else "oshort"))
        'u' -> listOf(Vowel("u"))
        // A consonant only when it has a vowel to carry - YES and PAYAL open a
        // syllable with it. Everywhere else it is the vowel itself, which is what
        // CANDY and CITY end on.
        'y' -> if (i + 1 < word.length && isVowelLetter(word[i + 1])) listOf(Cons("y"))
        else listOf(Vowel("i"))
        // Soft before e, i and y; hard everywhere else - CENTRE against COLGATE.
        'c' -> listOf(Cons(if (i + 1 < word.length && word[i + 1] in "eiy") "s" else "k"))
        'x' -> listOf(Cons("k"), Cons("s"))
        'q' -> listOf(Cons("k"))
        'w' -> listOf(Cons("v"))
        // English t and d are heard as the retroflex pair by every one of these
        // languages, which is why TOMATO is टमाटर and not तमातर.
        't' -> listOf(Cons("T"))
        'd' -> listOf(Cons("D"))
        'f' -> listOf(Cons("ph"))
        'z' -> listOf(Cons("j"))
        else -> if (CONSONANTS.contains(c.toString())) listOf(Cons(c.toString())) else emptyList()
    }

    // ---- A script out ------------------------------------------------------------

    /**
     * Writes [sounds] in [script].
     *
     * A consonant is printed with the vowel that follows it folded in as a sign, or
     * with a virama where the next sound is another consonant. Which consonants get
     * that virama is the one judgement call here - see [viramas].
     */
    private fun render(script: Script, sounds: List<Sound>): String {
        val virama = viramas(script, sounds)
        val out = StringBuilder()
        var i = 0
        while (i < sounds.size) {
            when (val sound = sounds[i]) {
                is Nasal -> out.append(nasalFor(script, sounds.getOrNull(i + 1)))
                // Reached on its own, so it opens the word or follows another vowel:
                // either way it is written in full rather than as a sign.
                is Vowel -> out.append(script.independent[sound.key].orEmpty())
                is Cons -> {
                    out.append(script.consonant[sound.key].orEmpty())
                    val next = sounds.getOrNull(i + 1)
                    if (next is Vowel) {
                        out.append(script.matra[next.key].orEmpty())
                        i++
                    } else if (virama[i]) {
                        out.append(script.virama)
                    }
                }
            }
            i++
        }
        return out.toString()
    }

    /**
     * The nasal before another consonant, written the way this script writes it.
     *
     * See [NasalStyle]. The homorganic case picks the nasal that matches what
     * follows - Tamil writes ண் before a retroflex and ந் before a dental, and using
     * one for the other is the sort of thing a reader notices immediately.
     */
    private fun nasalFor(script: Script, next: Sound?): String {
        val following = (next as? Cons)?.key
        return when (script.nasal) {
            NasalStyle.ANUSVARA -> script.anusvara
            NasalStyle.DENTAL -> script.consonant["n"].orEmpty() + script.virama
            NasalStyle.HOMORGANIC -> {
                // Only keys [CONSONANTS] actually holds. It carries no velar or
                // palatal nasal, and naming one produced an empty string followed by
                // a virama - SPRING came out of Tamil as "ஸ்ப்ரி்க்", a mark sitting
                // on nothing. Those fall back to the dental, which is legible.
                val key = when (following) {
                    "T", "Th", "D", "Dh" -> "N"
                    "p", "ph", "b", "bh", "m" -> "m"
                    else -> "n"
                }
                script.consonant[key].orEmpty() + script.virama
            }
        }
    }

    /**
     * Which consonants are written with a virama, and which keep their inherent vowel.
     *
     * The phonetically honest answer is "every consonant not followed by a vowel", and
     * it is the wrong one: it produces कोल्गेट where every Hindi label in the country
     * reads कोलगेट. These languages delete the inherent vowel as they read, so their
     * spelling leaves it in and lets the reader drop it.
     *
     * What that comes to, per run of consonants with no vowel among them:
     *
     *  * a run that ends the word is closed, every consonant in it - PAST is पेस्ट,
     *    because the s really has nothing after it;
     *  * a run that starts the word is a genuine cluster - STRONG is स्ट्रॉंग;
     *  * a run of three or more in the middle is one too - EXTRA is एक्स्ट्रा;
     *  * a pair in the middle keeps the inherent vowel where the first is a
     *    sonorant - COLGATE is कोलगेट - and takes the virama otherwise, since PEPSI
     *    is पेप्सि and not पेपसि.
     *
     * The last consonant of a run before a vowel never takes one: the vowel is its own.
     */
    private fun viramas(script: Script, sounds: List<Sound>): BooleanArray {
        val marks = BooleanArray(sounds.size)
        var i = 0
        while (i < sounds.size) {
            if (sounds[i] !is Cons) {
                i++
                continue
            }
            var j = i
            while (j < sounds.size && sounds[j] is Cons) j++
            val last = j - 1
            val startsWord = i == 0
            val endsWord = last == sounds.lastIndex
            for (k in i..last) {
                marks[k] = when {
                    // A word can end without its final vowel written only where the
                    // language reads the inherent one - Tamil, Telugu and Kannada do,
                    // and the northern scripts do not.
                    k == last && endsWord -> script.viramaAtWordEnd
                    k == last -> false
                    endsWord || startsWord || (j - i) >= 3 -> true
                    else -> (sounds[k] as Cons).key !in SONORANTS
                }
            }
            i = j
        }
        return marks
    }

    /** The consonants a reader glides through without needing the vowel spelled out. */
    private val SONORANTS = setOf("l", "r", "y", "v", "n", "m")

    // ---- The scripts ---------------------------------------------------------------

    /**
     * One script's letters, in the order [VOWELS] and [CONSONANTS] name them.
     *
     * [viramaAtWordEnd] is the one behavioural difference between them: Tamil, Telugu
     * and Kannada say a consonant's inherent vowel and so must cancel it explicitly at
     * the end of a word, while Devanagari, Bengali, Gujarati, Gurmukhi and Odia drop
     * it as they read and would look wrong with it marked.
     */
    private class Script(
        val independent: Map<String, String>,
        val matra: Map<String, String>,
        val consonant: Map<String, String>,
        val virama: String,
        val anusvara: String,
        val viramaAtWordEnd: Boolean,
        val nasal: NasalStyle
    )

    /**
     * How a script writes the n or m that sits before another consonant.
     *
     * The three are not preferences; each is what its own readers expect, and picking
     * one for all of them gets two of the three wrong. AND is ऐंड in Hindi and ন্ড in
     * Bengali - the first writes the nasal as a mark over the vowel, the second as a
     * letter joined to what follows - and Tamil goes further and picks the nasal that
     * matches the consonant after it, ண் before a retroflex and ந் before a dental.
     */
    private enum class NasalStyle { ANUSVARA, DENTAL, HOMORGANIC }

    /**
     * The vowels a script has to spell, including two English needs and Sanskrit
     * does not:
     *
     *  * **ae** - the a of AND, HAND and CANDY. Devanagari writes it ऐ, Bengali
     *    অ্যা. Read as a plain "a" it turns অ্যান্ড into অংড, which is not the word.
     *  * **oshort** - the o of HOT, SHOP and BOX, as against the long one of SOAP.
     *    Devanagari marks it ॉ; Bengali and Odia leave it to the inherent vowel their
     *    consonants already carry, which is why those two have no sign for it and
     *    HOT comes out হট rather than হোট.
     */
    private val VOWELS = listOf(
        "a", "aa", "i", "ii", "u", "uu", "e", "ai", "o", "au", "ae", "oshort"
    )

    private val CONSONANTS = listOf(
        "k", "kh", "g", "gh", "ch", "chh", "j", "jh", "T", "Th", "D", "Dh", "N",
        "t", "th", "d", "dh", "n", "p", "ph", "b", "bh", "m", "y", "r", "l", "v",
        "sh", "s", "h"
    )

    private fun script(
        independent: String, matra: String, consonants: String,
        virama: String, anusvara: String, viramaAtWordEnd: Boolean,
        nasal: NasalStyle = NasalStyle.ANUSVARA
    ): Script {
        // Space-separated so an empty cell (the inherent "a" has no sign) can be
        // written down as one rather than being a gap nobody notices.
        val vowelForms = independent.split(" ")
        val matraForms = matra.split("|")
        val consonantForms = consonants.split(" ")
        require(vowelForms.size == VOWELS.size) { "vowels: ${vowelForms.size}" }
        require(matraForms.size == VOWELS.size) { "matras: ${matraForms.size}" }
        require(consonantForms.size == CONSONANTS.size) { "consonants: ${consonantForms.size}" }
        return Script(
            independent = VOWELS.zip(vowelForms).toMap(),
            matra = VOWELS.zip(matraForms).toMap(),
            consonant = CONSONANTS.zip(consonantForms).toMap(),
            virama = virama,
            anusvara = anusvara,
            viramaAtWordEnd = viramaAtWordEnd,
            nasal = nasal
        )
    }

    private val DEVANAGARI by lazy {
        script(
            independent = "अ आ इ ई उ ऊ ए ऐ ओ औ ऐ ऑ",
            matra = "|ा|ि|ी|ु|ू|े|ै|ो|ौ|ै|ॉ",
            consonants = "क ख ग घ च छ ज झ ट ठ ड ढ ण त थ द ध न प फ ब भ म य र ल व श स ह",
            virama = "्", anusvara = "ं", viramaAtWordEnd = false
        )
    }

    private val BENGALI by lazy {
        script(
            independent = "অ আ ই ঈ উ ঊ এ ঐ ও ঔ অ্যা অ",
            matra = "|া|ি|ী|ু|ূ|ে|ৈ|ো|ৌ|্যা|",
            consonants = "ক খ গ ঘ চ ছ জ ঝ ট ঠ ড ঢ ণ ত থ দ ধ ন প ফ ব ভ ম য র ল ব শ স হ",
            virama = "্", anusvara = "ং", nasal = NasalStyle.DENTAL, viramaAtWordEnd = false
        )
    }

    /** Bengali's script, with the two letters Assamese writes differently - ৰ and ৱ. */
    private val ASSAMESE by lazy {
        script(
            independent = "অ আ ই ঈ উ ঊ এ ঐ ও ঔ অ্যা অ",
            matra = "|া|ি|ী|ু|ূ|ে|ৈ|ো|ৌ|্যা|",
            consonants = "ক খ গ ঘ চ ছ জ ঝ ট ঠ ড ঢ ণ ত থ দ ধ ন প ফ ব ভ ম য ৰ ল ৱ শ স হ",
            virama = "্", anusvara = "ং", nasal = NasalStyle.DENTAL, viramaAtWordEnd = false
        )
    }

    private val GUJARATI by lazy {
        script(
            independent = "અ આ ઇ ઈ ઉ ઊ એ ઐ ઓ ઔ ઍ ઑ",
            matra = "|ા|િ|ી|ુ|ૂ|ે|ૈ|ો|ૌ|ૅ|ૉ",
            consonants = "ક ખ ગ ઘ ચ છ જ ઝ ટ ઠ ડ ઢ ણ ત થ દ ધ ન પ ફ બ ભ મ ય ર લ વ શ સ હ",
            virama = "્", anusvara = "ં", viramaAtWordEnd = false
        )
    }

    private val ODIA by lazy {
        script(
            independent = "ଅ ଆ ଇ ଈ ଉ ଊ ଏ ଐ ଓ ଔ ଆ ଅ",
            matra = "|ା|ି|ୀ|ୁ|ୂ|େ|ୈ|ୋ|ୌ|ା|",
            consonants = "କ ଖ ଗ ଘ ଚ ଛ ଜ ଝ ଟ ଠ ଡ ଢ ଣ ତ ଥ ଦ ଧ ନ ପ ଫ ବ ଭ ମ ଯ ର ଲ ୱ ଶ ସ ହ",
            virama = "୍", anusvara = "ଂ", viramaAtWordEnd = false
        )
    }

    private val GURMUKHI by lazy {
        script(
            independent = "ਅ ਆ ਇ ਈ ਉ ਊ ਏ ਐ ਓ ਔ ਐ ਔ",
            matra = "|ਾ|ਿ|ੀ|ੁ|ੂ|ੇ|ੈ|ੋ|ੌ|ੈ|ੌ",
            consonants = "ਕ ਖ ਗ ਘ ਚ ਛ ਜ ਝ ਟ ਠ ਡ ਢ ਣ ਤ ਥ ਦ ਧ ਨ ਪ ਫ ਬ ਭ ਮ ਯ ਰ ਲ ਵ ਸ਼ ਸ ਹ",
            virama = "੍", anusvara = "ਂ", viramaAtWordEnd = false
        )
    }

    private val TELUGU by lazy {
        script(
            independent = "అ ఆ ఇ ఈ ఉ ఊ ఏ ఐ ఓ ఔ ఆ ఒ",
            matra = "|ా|ి|ీ|ు|ూ|ే|ై|ో|ౌ|ా|ొ",
            consonants = "క ఖ గ ఘ చ ఛ జ ఝ ట ఠ డ ఢ ణ త థ ద ధ న ప ఫ బ భ మ య ర ల వ శ స హ",
            virama = "్", anusvara = "ం", viramaAtWordEnd = true
        )
    }

    private val KANNADA by lazy {
        script(
            independent = "ಅ ಆ ಇ ಈ ಉ ಊ ಏ ಐ ಓ ಔ ಆ ಒ",
            matra = "|ಾ|ಿ|ೀ|ು|ೂ|ೇ|ೈ|ೋ|ೌ|ಾ|ೊ",
            consonants = "ಕ ಖ ಗ ಘ ಚ ಛ ಜ ಝ ಟ ಠ ಡ ಢ ಣ ತ ಥ ದ ಧ ನ ಪ ಫ ಬ ಭ ಮ ಯ ರ ಲ ವ ಶ ಸ ಹ",
            virama = "್", anusvara = "ಂ", viramaAtWordEnd = true
        )
    }

    /**
     * Tamil, which does not distinguish what the others do.
     *
     * It has one letter where the northern scripts have four - க stands for k, kh, g
     * and gh alike - because the distinction is not one Tamil makes. So GOAT and COAT
     * come out the same, and there is nothing to be done about that short of writing
     * the name in a script Tamil does not use. The Grantha letters ஜ, ஷ, ஸ and ஹ are
     * used for the sounds Tamil borrows, which is what they are there for.
     */
    private val TAMIL by lazy {
        script(
            independent = "அ ஆ இ ஈ உ ஊ ஏ ஐ ஓ ஔ ஆ ஒ",
            matra = "|ா|ி|ீ|ు|ூ|ே|ை|ோ|ௌ|ா|ொ",
            consonants = "க க க க ச ச ஜ ஜ ட ட ட ட ண த த த த ந ப ப ப ப ம ய ர ல வ ஷ ஸ ஹ",
            virama = "்", anusvara = "ம்", nasal = NasalStyle.HOMORGANIC, viramaAtWordEnd = true
        )
    }

    private fun scriptFor(language: PrintLanguage.Language): Script? = when (language) {
        PrintLanguage.Language.ENGLISH -> null
        PrintLanguage.Language.HINDI, PrintLanguage.Language.MARATHI -> DEVANAGARI
        PrintLanguage.Language.BENGALI -> BENGALI
        PrintLanguage.Language.ASSAMESE -> ASSAMESE
        PrintLanguage.Language.TAMIL -> TAMIL
        PrintLanguage.Language.TELUGU -> TELUGU
        PrintLanguage.Language.KANNADA -> KANNADA
        PrintLanguage.Language.GUJARATI -> GUJARATI
        PrintLanguage.Language.ODIA -> ODIA
        PrintLanguage.Language.PUNJABI -> GURMUKHI
    }
}
