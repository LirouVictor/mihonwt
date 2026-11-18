package eu.kanade.translation.recognizer

import kotlin.math.max
import kotlin.math.min

/**
 * Returns which of [corrections], a model's readings of the blocks of a page, is [read] by OCR with its mistakes
 * fixed: the closest one that [isCorrectionOf] it. Models now and then give a page's blocks in another order, so
 * all of them are compared.
 */
fun correctionOf(read: String, corrections: Collection<String>): String? {
    val letters = read.lettersAndDigits()
    return corrections
        .filter { isCorrectionOf(read, it) }
        .minByOrNull {
            val other = it.lettersAndDigits()
            editDistance(letters, other).toFloat() / max(letters.length, other.length)
        }
}

/**
 * Whether [corrected], a model's reading of a block's lettering, is likely the same text as [read] by OCR with its
 * mistakes fixed rather than text from elsewhere on the page or a rewrite: at most [MAX_CHANGED] of their letters and
 * digits differ. A missed line or word is within that, a different bubble isn't. Letters of a script [read] has none
 * of are never a fix, as Gemini once gave Arabic "لل" for the "LL" of "THEY'LL".
 */
fun isCorrectionOf(read: String, corrected: String): Boolean {
    val a = read.lettersAndDigits()
    val b = corrected.lettersAndDigits()
    if (b.isEmpty()) return false
    if (!a.scripts().containsAll(b.scripts())) return false
    return editDistance(a, b) <= max(a.length, b.length) * MAX_CHANGED
}

private fun String.lettersAndDigits() = uppercase().filter(Char::isLetterOrDigit)

private fun String.scripts(): Set<Character.UnicodeScript> = codePoints().toArray()
    .map(Character.UnicodeScript::of)
    .filterNot { it == Character.UnicodeScript.COMMON || it == Character.UnicodeScript.INHERITED }
    .toSet()

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val replace = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = min(replace, min(previous[j], current[j - 1]) + 1)
        }
        previous = current
    }
    return previous[b.length]
}

private const val MAX_CHANGED = 0.5f
