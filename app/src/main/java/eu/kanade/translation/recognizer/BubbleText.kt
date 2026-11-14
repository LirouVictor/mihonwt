package eu.kanade.translation.recognizer

import eu.kanade.translation.model.TranslationBlock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A word read by OCR, at page coordinates, on the line numbered [line]. */
class Word(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val letterHeight: Float,
    val line: Int,
    val angle: Float,
)

/**
 * Groups [words] into one block per speech bubble. ML Kit's own blocks and even its lines can run across bubbles
 * that touch, mixing their text, so each line is first cut where its words are far apart or where a bubble's
 * outline runs between them, read as "|" or seen in [pixels], and the pieces are then joined back by bubble.
 */
fun bubbleBlocks(words: List<Word>, pixels: LightPixels? = null): List<TranslationBlock> {
    val pieces = words.groupBy { it.line }.values.flatMap { line ->
        val sorted = line.sortedBy { it.x }
        val pieces = mutableListOf(mutableListOf<Word>())
        sorted.forEach { word ->
            val previous = pieces.last().lastOrNull()
            val isOutline = word.text.all { it in OUTLINE_CHARS }
            val gap = previous?.let { word.x - (it.x + it.width) } ?: 0f
            val farApart = gap > max(word.letterHeight, previous?.letterHeight ?: 0f) * MAX_WORD_GAP
            if (isOutline || farApart || (previous != null && pixels?.outlineBetween(previous, word) == true)) {
                if (pieces.last().isNotEmpty()) pieces.add(mutableListOf())
            }
            if (!isOutline) pieces.last().add(word)
        }
        pieces.filter { it.isNotEmpty() }.withMarksAttached()
    }
    return mergeBubbleBlocks(pieces.map { it.toBlock() }).filterNot { it.isArt() }
}

/**
 * Whether a bubble's outline runs between [left] and [right], as where two bubbles touch: a dark stroke across the
 * height of the words with light space on both sides of it, which lettering never leaves between two words.
 */
private fun LightPixels.outlineBetween(left: Word, right: Word): Boolean {
    val gapLeft = ceil(left.x + left.width).toInt()
    val gapRight = right.x.toInt()
    val top = ceil(max(left.y, right.y)).toInt()
    val bottom = min(left.y + left.height, right.y + right.height).toInt()
    val width = gapRight - gapLeft
    val height = bottom - top
    if (width < 3 || height < 3 || gapLeft < 0 || top < 0) return false
    val light = read(gapLeft, top, gapRight, bottom)
    val darkPixels = IntArray(width) { col -> (0..<height).count { row -> !light[row * width + col] } }
    val firstLight = darkPixels.indexOfFirst { it == 0 }
    val lastLight = darkPixels.indexOfLast { it == 0 }
    return (firstLight + 1..<lastLight).any { darkPixels[it] >= height * MIN_OUTLINE_SHARE }
}

/**
 * Joins pieces of a line that hold no letters or digits, like a hyphen or "?!" that OCR read as a word of its own,
 * to the piece before them, or after them at the start of the line, instead of losing them.
 */
private fun List<MutableList<Word>>.withMarksAttached(): List<List<Word>> {
    val pieces = toMutableList()
    var i = 0
    while (i < pieces.size && pieces.size > 1) {
        if (pieces[i].any { word -> word.text.any(Char::isLetterOrDigit) }) {
            i++
        } else if (i > 0) {
            pieces[i - 1].addAll(pieces.removeAt(i))
        } else {
            pieces[1].addAll(0, pieces.removeAt(0))
        }
    }
    return pieces
}

/**
 * Returns the word spelled by [letters], OCR's boxes of each of its letters at page coordinates, leaving out a
 * letter at either end much taller than the rest: a bubble's outline read as "J" or "|", which would otherwise be
 * taken for the size of the lettering. Returns null when no letters are left.
 */
fun wordOf(letters: List<Letter>, line: Int, angle: Float): Word? {
    val median = letters.map { it.height }.sorted().getOrNull(letters.size / 2) ?: return null
    fun isOutline(letter: Letter) = letters.size >= MIN_LETTERS_TO_TRIM && letter.height > median * MAX_LETTER_HEIGHT
    val kept = letters.dropWhile(::isOutline).dropLastWhile(::isOutline).ifEmpty { return null }
    val left = kept.minOf { it.x }
    val top = kept.minOf { it.y }
    return Word(
        text = kept.joinToString("") { it.text },
        x = left,
        y = top,
        width = kept.maxOf { it.x + it.width } - left,
        height = kept.maxOf { it.y + it.height } - top,
        letterHeight = kept.map { it.height }.sorted()[kept.size / 2],
        line = line,
        angle = angle,
    )
}

/** A letter read by OCR, at page coordinates. */
class Letter(val text: String, val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Whether this block is more likely the artwork read as text than lettering, as marks like "--", prices and numbers
 * on signs, or a lone stroke read as "K". Translating it would only blank part of the drawing. A lone "I" is a word.
 */
private fun TranslationBlock.isArt(): Boolean {
    val letters = text.filter(Char::isLetter)
    return letters.isEmpty() || (letters.length == 1 && letters != "I")
}

private fun List<Word>.toBlock(): TranslationBlock {
    val left = minOf { it.x }
    val top = minOf { it.y }
    return TranslationBlock(
        text = joinWords().fixLetteringMistakes(),
        width = maxOf { it.x + it.width } - left,
        height = maxOf { it.y + it.height } - top,
        x = left,
        y = top,
        symHeight = first().letterHeight,
        symWidth = first().letterHeight,
        angle = first().angle,
    )
}

// A hyphen or "?!" read as a word of its own is written against the word before it, as it was lettered
private fun List<Word>.joinWords() = buildString {
    this@joinWords.forEachIndexed { i, word ->
        if (i > 0 && word.text.any(Char::isLetterOrDigit)) append(' ')
        append(word.text)
    }
}

/**
 * Joins blocks that look like parts of the same speech bubble. They don't come one after the other, so every pair is
 * compared.
 */
private fun mergeBubbleBlocks(blocks: List<TranslationBlock>): MutableList<TranslationBlock> {
    val merged = blocks.sortedBy { it.y }.toMutableList()
    var i = 0
    while (i < merged.size) {
        val j = (i + 1..<merged.size).firstOrNull { isSameBubble(merged[i], merged[it]) }
        if (j == null) {
            i++
        } else {
            merged[i] = mergeTextBlock(merged[i], merged.removeAt(j))
        }
    }
    return merged
}

/**
 * Whether [below] continues [above]: stacked less than about a line apart and overlapping horizontally. Distances
 * are measured in letter heights, so they hold at any page resolution.
 */
private fun isSameBubble(above: TranslationBlock, below: TranslationBlock): Boolean {
    val lineHeight = max(above.symHeight, below.symHeight)
    val gap = below.y - (above.y + above.height)
    val overlap = min(above.x + above.width, below.x + below.width) - max(above.x, below.x)
    return gap > -lineHeight && gap < lineHeight * 1.2f && overlap > min(above.width, below.width) / 2
}

// Joined by a line break, so a word hyphenated across the lines is joined back before translating
private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
    val newX = min(a.x, b.x)
    val newY = min(a.y, b.y)
    return TranslationBlock(
        text = a.text + "\n" + b.text,
        width = max(a.x + a.width, b.x + b.width) - newX,
        height = max(a.y + a.height, b.y + b.height) - newY,
        x = newX,
        y = newY,
        symHeight = a.symHeight,
        symWidth = a.symWidth,
        angle = a.angle,
    )
}

private const val OUTLINE_CHARS = "|"

private const val MIN_OUTLINE_SHARE = 0.6f

private const val MIN_LETTERS_TO_TRIM = 3
private const val MAX_LETTER_HEIGHT = 1.4f

// Words in a bubble are about half a letter apart, while the text of the next bubble is at least a letter away
private const val MAX_WORD_GAP = 1f
