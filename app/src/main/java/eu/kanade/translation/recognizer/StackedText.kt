package eu.kanade.translation.recognizer

import eu.kanade.translation.model.Area
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Returns the letters of [box] when it holds lettering stacked a letter per line, like a "WHAT?!" running down a
 * narrow bubble, which OCR reads as a few stray letters at best: one area per row of the stack, from the top, a dot
 * joined to the letter it belongs to. Returns null for a box not much taller than wide or holding lines of text.
 */
fun stackedLetters(box: TextBox, pixels: LightPixels): List<Area>? {
    val left = box.left.toInt()
    val top = box.top.toInt()
    val right = ceil(box.right).toInt()
    val bottom = ceil(box.bottom).toInt()
    val width = right - left
    val height = bottom - top
    if (width < 1 || left < 0 || top < 0 || height < width * MIN_STACK_RATIO) return null
    val light = pixels.read(left, top, right, bottom)
    fun isDark(col: Int, row: Int) = !light[row * width + col]

    val rows = mutableListOf<Area>()
    var start = -1
    for (row in 0..height) {
        val dark = row < height && (0..<width).any { isDark(it, row) }
        if (dark && start < 0) start = row
        if (!dark && start >= 0) {
            val cols = (0..<width).filter { col -> (start..<row).any { isDark(col, it) } }
            rows += Area(
                x = (left + cols.first()).toFloat(),
                y = (top + start).toFloat(),
                width = (cols.last() + 1 - cols.first()).toFloat(),
                height = (row - start).toFloat(),
            )
            start = -1
        }
    }
    val letterHeight = rows.map { it.height }.median() ?: return null
    val letters = mutableListOf<Area>()
    rows.forEach { row ->
        val previous = letters.lastOrNull()
        // The dot of a "?" or "!" sits just under it, while a period after a letter keeps a letter's spacing
        val isDot =
            row.height < letterHeight * MAX_DOT_HEIGHT || (previous?.height ?: 0f) < letterHeight * MAX_DOT_HEIGHT
        if (previous != null && isDot && row.y - (previous.y + previous.height) < letterHeight * MAX_DOT_GAP) {
            letters[letters.lastIndex] = previous.joinedWith(row)
        } else {
            letters += row
        }
    }
    // Lines of text, rather than letters, are much wider than tall
    if (letters.size < MIN_STACKED_LETTERS || letters.any { it.width > letterHeight * MAX_LETTER_WIDTH }) return null
    return letters
}

/**
 * Whether the stack has a word break before letter [index] of [letters], where letterers leave a wider gap than
 * between the letters of a word.
 */
fun stackedWordBreakBefore(letters: List<Area>, index: Int): Boolean {
    if (index == 0) return false
    val letterHeight = letters.map { it.height }.median() ?: return false
    val previous = letters[index - 1]
    return letters[index].y - (previous.y + previous.height) > letterHeight * MIN_WORD_GAP
}

private fun Area.joinedWith(other: Area): Area {
    val left = min(x, other.x)
    val top = min(y, other.y)
    return Area(left, top, max(x + width, other.x + other.width) - left, max(y + height, other.y + other.height) - top)
}

private const val MIN_STACK_RATIO = 2f
private const val MIN_STACKED_LETTERS = 3
private const val MAX_LETTER_WIDTH = 2.5f
private const val MAX_DOT_HEIGHT = 0.5f
private const val MAX_DOT_GAP = 0.35f
private const val MIN_WORD_GAP = 1f
