package eu.kanade.translation.recognizer

import kotlin.math.max
import kotlin.math.min

/**
 * Whether [corrected], a model's reading of a block's lettering, is likely the same text as [read] by OCR with its
 * mistakes fixed rather than text from elsewhere on the page or a rewrite: at most [MAX_CHANGED] of their letters and
 * digits differ. A missed line or word is within that, a different bubble isn't.
 */
fun isCorrectionOf(read: String, corrected: String): Boolean {
    val a = read.lettersAndDigits()
    val b = corrected.lettersAndDigits()
    if (b.isEmpty()) return false
    return editDistance(a, b) <= max(a.length, b.length) * MAX_CHANGED
}

private fun String.lettersAndDigits() = uppercase().filter(Char::isLetterOrDigit)

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
