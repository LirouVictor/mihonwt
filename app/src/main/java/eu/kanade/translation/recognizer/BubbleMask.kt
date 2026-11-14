package eu.kanade.translation.recognizer

import eu.kanade.translation.model.Area
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which pixels of a page are light, read a region at a time so a long strip is never held whole. */
fun interface LightPixels {
    /** Whether each pixel from [left], [top] to [right], [bottom] (exclusive) is light, row by row. */
    fun read(left: Int, top: Int, right: Int, bottom: Int): BooleanArray
}

/**
 * Returns this block placed in the speech bubble around it: [TranslationBlock.bubble] covers the bubble's whole inside,
 * English letters and any stray marks included, so it can be painted over, and the block's box becomes the largest
 * rectangle inside the bubble for the translation. Returns null when no closed bubble is found, as for text on art or
 * a bubble cut by the page's edge.
 *
 * The page is read in square cells about an eighth of a letter high, a cell being light only if all its pixels are, so
 * a thin outline still closes the bubble.
 */
fun TranslationBlock.inBubble(
    imageWidth: Int,
    imageHeight: Int,
    others: List<TranslationBlock>,
    pixels: LightPixels,
): TranslationBlock? {
    if (abs(angle) > MAX_ANGLE) return null
    // Most bubbles are found in a small search, and a short text in a big bubble in a larger one
    return REACH_LINES.firstNotNullOfOrNull { inBubble(imageWidth, imageHeight, others, pixels, it) }
}

private fun TranslationBlock.inBubble(
    imageWidth: Int,
    imageHeight: Int,
    others: List<TranslationBlock>,
    pixels: LightPixels,
    reachLines: Float,
): TranslationBlock? {
    val lineHeight = max(symHeight, 1f)
    val cell = max(1, (lineHeight / CELLS_PER_LINE).roundToInt())
    val reach = lineHeight * reachLines
    val left = max(0, (x - reach).toInt())
    val top = max(0, (y - reach).toInt())
    val right = min(imageWidth, ceil(x + width + reach).toInt())
    val bottom = min(imageHeight, ceil(y + height + reach).toInt())
    val cols = (right - left + cell - 1) / cell
    val rows = (bottom - top + cell - 1) / cell
    if (cols < 3 || rows < 3) return null

    val light = pixels.read(left, top, right, bottom)
    val regionWidth = right - left
    fun cellIsLight(col: Int, row: Int): Boolean {
        for (py in row * cell..<min((row + 1) * cell, bottom - top)) {
            for (px in col * cell..<min((col + 1) * cell, regionWidth)) {
                if (!light[py * regionWidth + px]) return false
            }
        }
        return true
    }

    val lightCells = BooleanArray(cols * rows) { cellIsLight(it % cols, it / cols) }
    val boxLeft = ((x - left) / cell).toInt().coerceIn(0, cols - 1)
    val boxTop = ((y - top) / cell).toInt().coerceIn(0, rows - 1)
    val boxRight = ((x + width - left) / cell).toInt().coerceIn(0, cols - 1)
    val boxBottom = ((y + height - top) / cell).toInt().coerceIn(0, rows - 1)

    // The light space between the letters is filled outwards. A box that overlaps the outline also holds light
    // cells beyond it, whose fill runs off to the edge of the search and is dropped
    val inside = BooleanArray(cols * rows)
    val seen = BooleanArray(cols * rows)
    val queue = ArrayDeque<Int>()
    val component = mutableListOf<Int>()
    for (row in boxTop..boxBottom) {
        for (col in boxLeft..boxRight) {
            val seed = row * cols + col
            if (seen[seed] || !lightCells[seed]) continue
            seen[seed] = true
            queue.add(seed)
            component.clear()
            var closed = true
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                component.add(index)
                val c = index % cols
                val r = index / cols
                if (c == 0 || r == 0 || c == cols - 1 || r == rows - 1) {
                    closed = false
                    continue
                }
                for (next in intArrayOf(index - 1, index + 1, index - cols, index + cols)) {
                    if (!seen[next] && lightCells[next]) {
                        seen[next] = true
                        queue.add(next)
                    }
                }
            }
            if (closed) component.forEach { inside[it] = true }
        }
    }

    // Marks enclosed by the bubble's light space, like letters OCR missed, belong to the bubble too
    val outside = BooleanArray(cols * rows)
    for (col in 0..<cols) {
        queue.add(col)
        queue.add((rows - 1) * cols + col)
    }
    for (row in 0..<rows) {
        queue.add(row * cols)
        queue.add(row * cols + cols - 1)
    }
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        if (outside[index] || inside[index]) continue
        outside[index] = true
        val col = index % cols
        val row = index / cols
        if (col > 0) queue.add(index - 1)
        if (col < cols - 1) queue.add(index + 1)
        if (row > 0) queue.add(index - cols)
        if (row < rows - 1) queue.add(index + cols)
    }
    val bubble = BooleanArray(cols * rows) { !outside[it] }

    // Bubbles that touch share their light space, so each block only lays its text out on the cells nearer to it
    val neighbours = others.filter { it !== this && it.overlaps(left, top, right, bottom) }
    val ownCells = BooleanArray(cols * rows) { index ->
        if (!bubble[index]) return@BooleanArray false
        val cx = left + (index % cols + 0.5f) * cell
        val cy = top + (index / cols + 0.5f) * cell
        val own = distanceSquaredTo(cx, cy)
        neighbours.none { it.distanceSquaredTo(cx, cy) < own }
    }
    val text = largestRectangle(ownCells, cols, rows) ?: return null
    val margin = lineHeight * TEXT_MARGIN_LINES
    val textLeft = left + text.left * cell + margin
    val textTop = top + text.top * cell + margin
    val textRight = left + (text.right + 1) * cell - margin
    val textBottom = top + (text.bottom + 1) * cell - margin
    if ((textRight - textLeft) * (textBottom - textTop) < width * height * MIN_TEXT_AREA) return null

    return copy(
        x = textLeft,
        y = textTop,
        width = textRight - textLeft,
        height = textBottom - textTop,
        bubble = bubbleAreas(bubble, cols, rows, left, top, cell),
    )
}

/** Covers the [bubble] cells with rectangles, one per run of cells, joining runs repeated on the rows below. */
private fun bubbleAreas(bubble: BooleanArray, cols: Int, rows: Int, left: Int, top: Int, cell: Int): List<Area> {
    val areas = mutableListOf<Area>()
    var open = mapOf<Pair<Int, Int>, Int>()
    for (row in 0..<rows) {
        val runs = mutableListOf<Pair<Int, Int>>()
        var col = 0
        while (col < cols) {
            if (bubble[row * cols + col]) {
                val start = col
                while (col < cols && bubble[row * cols + col]) col++
                runs.add(start to col)
            } else {
                col++
            }
        }
        open = runs.associateWith { run ->
            val index = open[run]
            if (index != null) {
                areas[index] = areas[index].copy(height = areas[index].height + cell)
                index
            } else {
                areas.add(
                    Area(
                        x = (left + run.first * cell).toFloat(),
                        y = (top + row * cell).toFloat(),
                        width = ((run.second - run.first) * cell).toFloat(),
                        height = cell.toFloat(),
                    ),
                )
                areas.lastIndex
            }
        }
    }
    return areas
}

private class CellRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The largest rectangle of set cells, found row by row from the heights of the set columns above each. */
private fun largestRectangle(cells: BooleanArray, cols: Int, rows: Int): CellRect? {
    val heights = IntArray(cols)
    var best: CellRect? = null
    var bestArea = 0
    for (row in 0..<rows) {
        for (col in 0..<cols) heights[col] = if (cells[row * cols + col]) heights[col] + 1 else 0
        val stack = ArrayDeque<Int>()
        for (col in 0..cols) {
            val height = if (col < cols) heights[col] else 0
            while (stack.isNotEmpty() && heights[stack.last()] >= height) {
                val tall = heights[stack.removeLast()]
                val start = if (stack.isEmpty()) 0 else stack.last() + 1
                val area = tall * (col - start)
                if (area > bestArea) {
                    bestArea = area
                    best = CellRect(start, row - tall + 1, col - 1, row)
                }
            }
            stack.addLast(col)
        }
    }
    return best
}

private fun TranslationBlock.overlaps(left: Int, top: Int, right: Int, bottom: Int) =
    x < right && x + width > left && y < bottom && y + height > top

private fun TranslationBlock.distanceSquaredTo(px: Float, py: Float): Float {
    val dx = max(max(x - px, 0f), px - (x + width))
    val dy = max(max(y - py, 0f), py - (y + height))
    return dx * dx + dy * dy
}

private const val MAX_ANGLE = 10f
private const val CELLS_PER_LINE = 8f
private val REACH_LINES = listOf(6f, 12f, 24f)
private const val TEXT_MARGIN_LINES = 0.25f

// Bubbles whose inside leaves the text much less room than the letters had are probably not bubbles at all
private const val MIN_TEXT_AREA = 0.6f
