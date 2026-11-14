package eu.kanade.translation.recognizer

import eu.kanade.translation.model.TranslationBlock
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
 * that touch, mixing their text, so each line is first cut where its words are far apart or where it reads a
 * bubble's outline as "|", and the pieces are then joined back by bubble.
 */
fun bubbleBlocks(words: List<Word>): List<TranslationBlock> {
    val pieces = words.groupBy { it.line }.values.flatMap { line ->
        val sorted = line.sortedBy { it.x }
        val pieces = mutableListOf(mutableListOf<Word>())
        sorted.forEach { word ->
            val previous = pieces.last().lastOrNull()
            val isOutline = word.text.all { it in OUTLINE_CHARS }
            val gap = previous?.let { word.x - (it.x + it.width) } ?: 0f
            if (isOutline || gap > max(word.letterHeight, previous?.letterHeight ?: 0f) * MAX_WORD_GAP) {
                if (pieces.last().isNotEmpty()) pieces.add(mutableListOf())
            }
            if (!isOutline) pieces.last().add(word)
        }
        pieces.filter { it.isNotEmpty() }
    }
    return mergeBubbleBlocks(pieces.map { it.toBlock() }).filterNot { it.isArt() }
}

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
        text = joinToString(" ") { it.text }.fixLetteringMistakes(),
        width = maxOf { it.x + it.width } - left,
        height = maxOf { it.y + it.height } - top,
        x = left,
        y = top,
        symHeight = first().letterHeight,
        symWidth = first().letterHeight,
        angle = first().angle,
    )
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

// Words in a bubble are about half a letter apart, while the text of the next bubble is at least a letter away
private const val MAX_WORD_GAP = 1f
