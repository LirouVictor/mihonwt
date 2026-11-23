package eu.kanade.translation.recognizer

import eu.kanade.translation.model.Area
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Whether a page [width] by [height] is a piece of a long strip, as manhwa are, which scanlators cut at a fixed height
 * whatever runs across the cut.
 */
fun isStripPiece(width: Int, height: Int): Boolean = height >= width * MIN_STRIP_RATIO

/**
 * Returns the blocks of [above], a piece of a strip, that may run on below its bottom edge, as those within a few
 * letters of it.
 */
fun blocksNearBottom(above: PageTranslation): List<TranslationBlock> = above.blocks.filter { block ->
    above.imgHeight - (block.y + block.height) <= block.symHeight * MAX_SEAM_GAP_TOUCHING
}

/**
 * Returns the blocks of [above] and [below], two strip pieces read one after the other, that are parts of one text cut
 * by the edge between them: each block near the bottom of [above] with the block near the top of [below] sharing most
 * of its width. They are closer than a line apart across the edge, or a little more when a bubble around one of them
 * runs into the edge.
 */
fun blocksAcrossSeam(above: PageTranslation, below: PageTranslation): List<Pair<TranslationBlock, TranslationBlock>> {
    val taken = mutableSetOf<TranslationBlock>()
    return blocksNearBottom(above).mapNotNull { upper ->
        below.blocks
            .filter { it !in taken }
            .filter { lower ->
                val gap = above.imgHeight - (upper.y + upper.height) + lower.y
                val letter = min(upper.symHeight, lower.symHeight)
                val bubbleAtEdge = upper.bubble.any { above.imgHeight - (it.y + it.height) <= EDGE } ||
                    lower.bubble.any { it.y <= EDGE }
                overlapX(upper, lower) > 0 &&
                    (gap <= letter * MAX_SEAM_GAP || (bubbleAtEdge && gap <= letter * MAX_SEAM_GAP_TOUCHING))
            }
            .maxByOrNull { overlapX(upper, it) }
            ?.let { lower ->
                taken += lower
                upper to lower
            }
    }
}

private fun overlapX(a: TranslationBlock, b: TranslationBlock): Float =
    min(a.x + a.width, b.x + b.width) - max(a.x, b.x)

/**
 * Returns [translation] split in two at a space, the first part with about [share] of its letters, so a text cut by a
 * page's edge is translated whole and drawn on both sides as its original was. A single word stays whole, first.
 */
fun splitTranslation(translation: String, share: Float): Pair<String, String> {
    val words = translation.trim().split(SPACES)
    if (words.size < 2) return translation.trim() to ""
    val letters = words.map { word -> word.count(Char::isLetterOrDigit) }
    val total = letters.sum().coerceAtLeast(1)
    val cut = (1..<words.size).minBy { i -> abs(letters.take(i).sum().toFloat() / total - share) }
    return words.take(cut).joinToString(" ") to words.drop(cut).joinToString(" ")
}

/** Share of the letters of [words] whose middle is above [edge]. */
fun letterShareAbove(words: List<Word>, edge: Float): Float? {
    val letters = words.map { it.text.count(Char::isLetterOrDigit) }
    val total = letters.sum().takeIf { it > 0 } ?: return null
    return words.indices.filter { words[it].y + words[it].height / 2 < edge }.sumOf { letters[it] }.toFloat() / total
}

/**
 * Returns [block] painted over from its text to [edge], the page's top or bottom edge, between [left] and [right]: the
 * letters of the line the edge cuts through are too broken for OCR, so no block covers them.
 */
fun TranslationBlock.paintedToEdge(edge: Float, left: Float, right: Float): TranslationBlock {
    val own = bubble.ifEmpty {
        val padding = symHeight / 4
        listOf(Area(x - padding, y - padding, width + 2 * padding, height + 2 * padding))
    }
    val gapTop = if (edge > y) y + height else edge
    val gapBottom = if (edge > y) edge else y
    val gap = Area(left, gapTop, right - left, gapBottom - gapTop).takeIf { it.height > 0 }
    return copy(bubble = own + listOfNotNull(gap))
}

/** Share of the letters of [upper] in [upper] and [lower] together. */
fun letterShare(upper: String, lower: String): Float {
    val a = upper.count(Char::isLetterOrDigit)
    val b = lower.count(Char::isLetterOrDigit)
    return if (a + b == 0) 0.5f else a.toFloat() / (a + b)
}

/** Returns the text of [words], its lines top to bottom. */
fun joinedText(words: List<Word>): String = words.groupBy { it.line }.values
    .sortedBy { line -> line.minOf { it.y } }
    .joinToString("\n") { line -> line.sortedBy { it.x }.joinToString(" ") { it.text } }

private val SPACES = Regex("""\s+""")

// Manhwa pieces are about four or five times as tall as wide, manga pages less than twice
private const val MIN_STRIP_RATIO = 2f

// Lines of one text are less than a line apart
private const val MAX_SEAM_GAP = 1.5f
private const val MAX_SEAM_GAP_TOUCHING = 3f

private const val EDGE = 2f
