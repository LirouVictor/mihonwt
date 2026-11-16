package eu.kanade.translation.recognizer

import android.content.Context

/**
 * Fixes words OCR misread into something that isn't English, like "PREARY" for "DREARY" or "UST" for "JUST", which a
 * translator that doesn't see the page translates as read. A word is changed only by the misreadings comic lettering
 * typically causes, and only when they lead to a single most common known word.
 *
 * [ranks] holds known words in capitals without apostrophes, with 0 for the most common.
 */
class OcrSpelling(private val ranks: Map<String, Int>) {

    /** Returns [text] with its misread words fixed. Words of [names], like a series' characters, count as known. */
    fun fix(text: String, names: Set<String> = emptySet()): String = WORD.replace(text) { match ->
        val word = match.value
        val key = word.key()
        if (key.length < MIN_LETTERS || key.none(Char::isLetter) || key.all(Char::isDigit)) return@replace word
        if (key in ranks || key in names) return@replace word
        val fixed = bestCandidate(word, key, names) ?: return@replace word
        val cased = if (word.any(Char::isLowerCase) && word.count(Char::isUpperCase) <= 1) {
            fixed.lowercase().replaceFirstChar { if (word.first().isUpperCase()) it.uppercaseChar() else it }
        } else {
            fixed
        }
        withApostrophes(word, key, cased)
    }

    /** Puts the apostrophes of [word] back into its [fixed] form, which differs from [key] by one letter at most. */
    private fun withApostrophes(word: String, key: String, fixed: String): String {
        val inserted = if (fixed.length >
            key.length
        ) {
            key.indices.firstOrNull { key[it] != fixed[it] } ?: key.length
        } else {
            -1
        }
        val result = StringBuilder()
        var next = 0
        for (c in word) {
            if (c == '\'' || c == '’') {
                result.append(c)
                continue
            }
            if (next == inserted) result.append(fixed[next++])
            result.append(fixed[next++])
        }
        if (next < fixed.length) result.append(fixed, next, fixed.length)
        return result.toString()
    }

    private fun bestCandidate(word: String, key: String, names: Set<String>): String? {
        val found = candidates(word, key).filter { it in ranks || it in names }.distinct()
            .map { it to (if (it in names) NAME_RANK else ranks.getValue(it)) }
        val best = found.minOfOrNull { it.second } ?: return null
        return found.filter { it.second == best }.singleOrNull()?.first
    }

    private fun candidates(word: String, key: String) = sequence {
        // A dropped "J" at the start of a word, as in "UST", is the only fix short words get: others too easily turn
        // one word into another, as "CLAK" read as "CAK" into "CAR"
        yield("J$key")
        if (key.length < MIN_LETTERS_TO_CHANGE) return@sequence
        for (i in key.indices) {
            MISREAD[key[i]]?.forEach { yield(key.substring(0, i) + it + key.substring(i + 1)) }
        }
        // A lowercase letter in a word in capitals stands out as misread, as the "n" of "AnAZING", while an uppercase
        // "N" is mostly right
        if (word.count(Char::isUpperCase) > word.count(Char::isLowerCase)) {
            word.filter { it != '\'' && it != '’' }.forEachIndexed { i, c ->
                if (c == 'n') yield(key.substring(0, i) + 'M' + key.substring(i + 1))
            }
        }
        // A thin "I" is often dropped from a word, which is then left with no vowel, as "NGHT"; a word with vowels,
        // like "CHAN", is more likely a name
        if (key.none { it in VOWELS }) {
            for (i in 0..key.length) yield(key.substring(0, i) + 'I' + key.substring(i))
        }
    }

    private fun String.key() = uppercase().filter { it != '\'' && it != '’' }

    companion object {
        private const val MIN_LETTERS = 3
        private const val MIN_LETTERS_TO_CHANGE = 4
        private const val NAME_RANK = -1
        private const val VOWELS = "AEIOU"
        private val WORD = Regex("""[\p{L}\d'’]+""")

        // What OCR read, and the letters lettering it misreads that way, from misreadings seen on real pages
        private val MISREAD = mapOf(
            'P' to "D",
            'K' to "R",
            'W' to "N",
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
