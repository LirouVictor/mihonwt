package eu.kanade.translation.recognizer

import android.content.Context
import eu.kanade.translation.model.joinHyphenatedWords

/**
 * Fixes words OCR misread into something that isn't English, like "PREARY" for "DREARY" or "UST" for "JUST", which a
 * translator that doesn't see the page translates as read. A word is changed only by the misreadings comic lettering
 * typically causes, or split where OCR missed a space, and only when that leads to a single most common known word.
 *
 * [ranks] holds known words in capitals without apostrophes, with 0 for the most common.
 */
class OcrSpelling(private val ranks: Map<String, Int>) {

    /**
     * Returns [text] with its misread words fixed. Words of [names], like a series' characters, count as known. With
     * [anyLetter], words of [MIN_LETTERS_ANY_CHANGE] letters or more may also have any one letter changed, added or
     * dropped, and words of [MIN_LETTERS_ADDED] a letter added, which turns names into words too easily unless the
     * series' names are known.
     */
    fun fix(text: String, names: Set<String> = emptySet(), anyLetter: Boolean = false): String =
        WORD.replace(text.joinBrokenWords(names)) { fixWord(it.value, names, anyLetter) }

    private fun fixWord(word: String, names: Set<String>, anyLetter: Boolean): String {
        // Possessives aren't in the word list, so the owner is checked: "EVERYONE'S" would otherwise be split into
        // "EVERY ONE'S" and "BONNEY'S" changed into "BONNET'S"
        if (word.length > MIN_LETTERS && word.last().uppercaseChar() == 'S' && word[word.length - 2] in "'’") {
            return fixWord(word.dropLast(2), names, anyLetter) + word.takeLast(2)
        }
        val key = word.key()
        if (!isCheckable(key) || key in ranks || key in names) return word
        val fixed = best(misreadings(word, key), names)
            ?: split(word, key)
            ?: anyLetterChange(key, anyLetter)
            ?: return word
        val cased = if (word.any(Char::isLowerCase) && word.count(Char::isUpperCase) <= 1) {
            fixed.lowercase().replaceFirstChar { if (word.first().isUpperCase()) it.uppercaseChar() else it }
        } else {
            fixed
        }
        return if (' ' in fixed) cased else withApostrophes(word, key, cased)
    }

    /**
     * Joins words broken across lines, whose halves aren't words and were changed into others, as "TECTING" into
     * "TESTING": those hyphenated, and those whose hyphen OCR missed, as "PRO" and "TECTING", when joined they make a
     * known word and the second half isn't one.
     */
    private fun String.joinBrokenWords(names: Set<String>): String = joinHyphenatedWords().replace(LINE_BROKEN_WORD) {
        val (first, second) = it.destructured
        val isBroken = (first + second).key() in ranks && second.key() !in ranks && second.key() !in names
        if (isBroken) first + second else it.value
    }

    /** Whether [word] is English. */
    fun isWord(word: String): Boolean = word.key() in ranks

    /** Returns the words of [text] that aren't English, in capitals without apostrophes. */
    fun unknownWords(text: String): Set<String> = WORD.findAll(text)
        .map { it.value.key() }
        .filter { isCheckable(it) && it !in ranks }
        .toSet()

    private fun isCheckable(key: String) =
        key.length >= MIN_LETTERS && key.any(Char::isLetter) && !key.all(Char::isDigit)

    /** Puts the apostrophes of [word] back into its [fixed] form, which differs from [key] by one letter at most. */
    private fun withApostrophes(word: String, key: String, fixed: String): String {
        if (word.length == key.length) return fixed
        val inserted = if (fixed.length > key.length) {
            key.indices.firstOrNull { key[it] != fixed[it] } ?: key.length
        } else {
            -1
        }
        val dropped = if (fixed.length < key.length) {
            fixed.indices.firstOrNull { key[it] != fixed[it] } ?: fixed.length
        } else {
            -1
        }
        val result = StringBuilder()
        var next = 0
        var read = 0
        for (c in word) {
            if (c == '\'' || c == '’') {
                result.append(c)
                continue
            }
            if (read++ == dropped) continue
            if (next == inserted) result.append(fixed[next++])
            if (next < fixed.length) result.append(fixed[next++])
        }
        if (next < fixed.length) result.append(fixed, next, fixed.length)
        return result.toString()
    }

    // Names are left out, as a name one letter from another is more likely a different character
    private fun anyLetterChange(key: String, anyLetter: Boolean): String? =
        when {
            !anyLetter || key.length < MIN_LETTERS_ADDED -> null
            // Shorter names are too often one letter from a word, as "BONNEY", "PANZER" or "SATONE", while a thin
            // letter OCR dropped, as in "CHCKEN", is the likelier mistake
            key.length < MIN_LETTERS_ANY_CHANGE -> best(oneEdit(key).filter { it.length > key.length }, emptySet())
            else -> best(oneEdit(key), emptySet())
        }

    private fun best(candidates: Sequence<String>, names: Set<String>): String? {
        val found = candidates.filter { it in ranks || it in names }.distinct()
            .map { it to (if (it in names) NAME_RANK else ranks.getValue(it)) }
            .toList()
        val best = found.minOfOrNull { it.second } ?: return null
        return found.filter { it.second == best }.singleOrNull()?.first
    }

    private fun misreadings(word: String, key: String) = sequence {
        // A dropped "J" at the start of a word, as in "UST", is the only fix short words get: others too easily turn
        // one word into another, as "CLAK" read as "CAK" into "CAR"
        yield("J$key")
        if (key.length < MIN_LETTERS_TO_CHANGE) return@sequence
        for (i in key.indices) {
            MISREAD[key[i]]?.forEach { yield(key.substring(0, i) + it + key.substring(i + 1)) }
        }
        // "RN" and "M" run together or apart
        var i = key.indexOf("RN")
        while (i >= 0) {
            yield(key.substring(0, i) + 'M' + key.substring(i + 2))
            i = key.indexOf("RN", i + 1)
        }
        key.forEachIndexed { at, c -> if (c == 'M') yield(key.substring(0, at) + "RN" + key.substring(at + 1)) }
        // A lowercase letter in a word in capitals stands out as misread, as the "n" of "AnAZING", while an uppercase
        // "N" is mostly right
        if (word.count(Char::isUpperCase) > word.count(Char::isLowerCase)) {
            word.filter { it != '\'' && it != '’' }.forEachIndexed { at, c ->
                if (c == 'n') yield(key.substring(0, at) + 'M' + key.substring(at + 1))
            }
        }
        // A thin "I" is often dropped from a word, which is then left with no vowel, as "NGHT"; a word with vowels,
        // like "CHAN", is more likely a name
        if (key.none { it in VOWELS }) {
            for (at in 0..key.length) yield(key.substring(0, at) + 'I' + key.substring(at))
        }
    }

    private fun oneEdit(key: String) = sequence {
        for (i in key.indices) yield(key.removeRange(i, i + 1))
        for (i in 0..key.length) {
            for (c in 'A'..'Z') {
                if (i < key.length && c != key[i]) yield(key.substring(0, i) + c + key.substring(i + 1))
                yield(key.substring(0, i) + c + key.substring(i))
            }
        }
    }

    /**
     * Returns [word] as the two common words it is, as "I SAID" for "ISAID", where OCR missed a space. Only an "I", two
     * long words, or a short word before a long one or after a fairly long one are split off: other pairs turn names
     * and dropped endings into words, as "NOR MAN", "NO THIN" or "TALK IN".
     */
    private fun split(word: String, key: String): String? {
        if (key.any { !it.isLetter() }) return null
        val splits = (1 until key.length).filter { at ->
            val first = key.substring(0, at)
            val second = key.substring(at)
            // A word after a glued "I" may be rarer, as "IFORGED", which a dropped letter would turn into "FORGED"
            val withI = (first == "I" && (second.isCommon(MIN_SPLIT_I_LETTERS) || second.isKnown(MIN_SPLIT_LETTERS))) ||
                (second == "I" && first.isCommon(MIN_SPLIT_I_LETTERS))
            val shortFirst = first.isShortCommon() && second.isCommon(MIN_SPLIT_AFTER_SHORT_LETTERS)
            val shortSecond = second.isShortCommon() && second != "IN" && first.isCommon(MIN_SPLIT_LETTERS)
            withI || shortFirst || shortSecond ||
                (first.isCommon(MIN_SPLIT_LETTERS) && second.isCommon(MIN_SPLIT_LETTERS))
        }
        val at = splits.singleOrNull() ?: return null
        // Apostrophes stay where they were, as in "IDON'T"
        var letters = 0
        val wordAt = word.indexOfFirst { c -> c != '\'' && c != '’' && letters++ == at }
        return word.substring(0, wordAt) + " " + word.substring(wordAt)
    }

    private fun String.isShortCommon() = length in MIN_SHORT_LETTERS..<MIN_SPLIT_LETTERS && ranks[this] == 0

    private fun String.isCommon(minLetters: Int) = length >= minLetters && ranks[this] == 0

    private fun String.isKnown(minLetters: Int) = length >= minLetters && this in ranks

    private fun String.key() = uppercase().filter { it != '\'' && it != '’' }

    companion object {
        private const val MIN_LETTERS = 3
        private const val MIN_LETTERS_TO_CHANGE = 4
        private const val MIN_LETTERS_ADDED = 6
        private const val MIN_LETTERS_ANY_CHANGE = 7
        private const val MIN_SPLIT_LETTERS = 4
        private const val MIN_SPLIT_I_LETTERS = 3
        private const val MIN_SHORT_LETTERS = 2
        private const val MIN_SPLIT_AFTER_SHORT_LETTERS = 5
        private const val NAME_RANK = -1
        private const val VOWELS = "AEIOU"
        private val WORD = Regex("""[\p{L}\d'’]+""")
        private val LINE_BROKEN_WORD = Regex("""(\p{L}+)\n(\p{L}+)""")

        // What OCR read, and the letters lettering it misreads that way, from misreadings seen on real pages
        private val MISREAD = mapOf(
            'P' to "D",
            'K' to "R",
            'W' to "NI",
            '0' to "O",
            '1' to "I",
            '5' to "S",
            '8' to "B",
            '2' to "Z",
            '6' to "G",
        )

        private const val WORDS_ASSET = "english_words.txt"

        /** Loads the English words that come with the app. */
        fun load(context: Context): OcrSpelling {
            val ranks = HashMap<String, Int>(WORDS_CAPACITY)
            context.assets.open(WORDS_ASSET).bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith('#') || it.length < 2 }
                    .forEach { ranks.putIfAbsent(it.substring(1), it[0].digitToInt()) }
            }
            return OcrSpelling(ranks)
        }

        private const val WORDS_CAPACITY = 80_000

        /** The words of a series' glossary terms, so its names are neither changed nor missed. */
        fun namesOf(terms: Collection<String>): Set<String> = terms
            .flatMap { term -> WORD.findAll(term).map { it.value.uppercase().filter { c -> c != '\'' && c != '’' } } }
            .filter { it.length >= MIN_LETTERS }
            .toSet()
    }
}
