package eu.kanade.translation.recognizer

import eu.kanade.translation.model.FoundText
import eu.kanade.translation.model.TranslationBlock
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A word read by OCR, at page coordinates, on the line numbered [line]. */
@Serializable
data class Word(
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
 * Returns a block for each text in [found], laid over the OCR [words] inside its box: their union is more exact than
 * a box guessed from a small page image, and their letters give the lettering's size. Words outside every box, like
 * sound effects or signs that the model left out, are dropped. A text without words keeps its own box, with letters
 * as tall as the page's others.
 */
fun blocksOfFoundText(found: List<FoundText>, words: List<Word>): List<TranslationBlock> {
    val pageLetterHeight = words.map { it.letterHeight }.median()
    val wordsByText = words.groupBy { word ->
        val cx = word.x + word.width / 2
        val cy = word.y + word.height / 2
        found
            .filter { text ->
                val margin = word.letterHeight * FOUND_BOX_MARGIN
                cx in text.left - margin..text.right + margin && cy in text.top - margin..text.bottom + margin
            }
            // A word in two boxes belongs to the smaller, as a bubble drawn inside a larger one
            .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
    }
    return found.mapNotNull { text ->
        if (text.translation.isBlank()) return@mapNotNull null
        val inside = wordsByText[text].orEmpty()
        val letterHeight = inside.map { it.letterHeight }.median()
            ?: pageLetterHeight
            ?: ((text.bottom - text.top) / FALLBACK_LINES)
        val left = inside.minOfOrNull { it.x } ?: text.left
        val top = inside.minOfOrNull { it.y } ?: text.top
        TranslationBlock(
            text = text.text,
            translation = text.translation,
            width = (inside.maxOfOrNull { it.x + it.width } ?: text.right) - left,
            height = (inside.maxOfOrNull { it.y + it.height } ?: text.bottom) - top,
            x = left,
            y = top,
            symHeight = letterHeight,
            symWidth = letterHeight,
            angle = inside.map { it.angle }.median() ?: 0f,
        )
    }
}

private fun List<Float>.median(): Float? = sorted().getOrNull(size / 2)

/** A box around text that a detector found on a page, at page coordinates. */
class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Returns the text boxes among a detector's [boxes], given as left, top, right, bottom, with their [labels] and
 * [scores]: text in a bubble, or text outside one when the detector is surer, as it then also takes art for text.
 * Boxes around whole bubbles are left out, as the text inside them is found on its own.
 */
fun textBoxes(labels: LongArray, boxes: Array<FloatArray>, scores: FloatArray): List<TextBox> {
    return labels.indices.mapNotNull { i ->
        val minScore = when (labels[i]) {
            LABEL_TEXT_IN_BUBBLE -> MIN_TEXT_IN_BUBBLE_SCORE
            LABEL_TEXT_OUTSIDE_BUBBLE -> MIN_TEXT_OUTSIDE_BUBBLE_SCORE
            else -> return@mapNotNull null
        }
        val (left, top, right, bottom) = boxes[i].toList()
        TextBox(left, top, right, bottom).takeIf { scores[i] >= minScore && right > left && bottom > top }
    }
}

/**
 * Groups [words] into one block per box a detector found around a bubble's text or a caption. Words outside every box,
 * like sound effects, are dropped, and so are boxes without words.
 */
fun blocksInBoxes(boxes: List<TextBox>, words: List<Word>): List<TranslationBlock> {
    val wordsByBox = wordsByBox(boxes, words)
    return boxes
        .mapNotNull { box -> wordsByBox[box]?.let(::joinedBlock) }
        .filterNot { it.isArt() }
}

/** Returns the [boxes] that none of [words] is placed in, whose text OCR missed. */
fun boxesWithoutWords(boxes: List<TextBox>, words: List<Word>): List<TextBox> {
    val wordsByBox = wordsByBox(boxes, words)
    return boxes.filter { it !in wordsByBox }
}

private fun wordsByBox(boxes: List<TextBox>, words: List<Word>): Map<TextBox?, List<Word>> {
    val pageLetterHeight = words.map { it.letterHeight }.median() ?: 0f
    return words.groupBy { word ->
        val cx = word.x + word.width / 2
        val cy = word.y + word.height / 2
        // A bubble's outline read as a huge letter would otherwise reach boxes all over the page
        val margin = min(word.letterHeight, pageLetterHeight) * FOUND_BOX_MARGIN
        boxes
            .filter { cx in it.left - margin..it.right + margin && cy in it.top - margin..it.bottom + margin }
            .filter { word.areaInside(it, margin) >= word.width * word.height * MIN_WORD_SHARE_IN_BOX }
            // Detectors give the same text more than once, in boxes of different sizes
            .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
    }
}

private fun Word.areaInside(box: TextBox, margin: Float): Float {
    val width = min(x + this.width, box.right + margin) - max(x, box.left - margin)
    val height = min(y + this.height, box.bottom + margin) - max(y, box.top - margin)
    return max(width, 0f) * max(height, 0f)
}

// Lines are joined by line breaks, top to bottom, so words hyphenated across them are joined back
private fun joinedBlock(words: List<Word>): TranslationBlock {
    return words.groupBy { it.line }.values
        .map { line -> line.sortedBy { it.x }.toBlock() }
        .sortedBy { it.y }
        .reduce(::mergeTextBlock)
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

// Boxes from a model are a little off, by up to about a letter
private const val FOUND_BOX_MARGIN = 1f

private const val FALLBACK_LINES = 3f

private const val MIN_WORD_SHARE_IN_BOX = 0.5f

private const val LABEL_TEXT_IN_BUBBLE = 1L
private const val LABEL_TEXT_OUTSIDE_BUBBLE = 2L
private const val MIN_TEXT_IN_BUBBLE_SCORE = 0.3f
private const val MIN_TEXT_OUTSIDE_BUBBLE_SCORE = 0.5f

private const val MIN_LETTERS_TO_TRIM = 3
private const val MAX_LETTER_HEIGHT = 1.4f

// Words in a bubble are about half a letter apart, while the text of the next bubble is at least a letter away
private const val MAX_WORD_GAP = 1f
