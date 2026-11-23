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
 * rectangle inside the bubble for the translation. Returns null when no closed bubble is found, as for text on art. The
 * page's edge closes a bubble it cuts.
 *
 * The page is read in square cells about an eighth of a letter high, a cell being light only if all its pixels are, so
 * a thin outline still closes the bubble. [words] are those OCR read on the page, whose lines tell this block's letters
 * from art its box overlaps.
 */
fun TranslationBlock.inBubble(
    imageWidth: Int,
    imageHeight: Int,
    others: List<TranslationBlock>,
    pixels: LightPixels,
    words: List<Word> = emptyList(),
): TranslationBlock? {
    if (abs(angle) > MAX_ANGLE) return null
    val letters = ownLines(words)
    // Most bubbles are found in a small search, and a short text in a big bubble in a larger one
    return REACH_LINES.firstNotNullOfOrNull { inBubble(imageWidth, imageHeight, others, pixels, it, letters = letters) }
}

/** The box of each line of the [words] whose middle is in this block's box, gaps between its words included. */
private fun TranslationBlock.ownLines(words: List<Word>): List<Area> = words
    .filter { it.x + it.width / 2 in x..x + width && it.y + it.height / 2 in y..y + height }
    .groupBy { it.line }
    .values
    .map { line ->
        // OCR's boxes can cut off a letter's edge, as of a large serif "B"
        val margin = line.maxOf { it.letterHeight } * LINE_MARGIN_LETTERS
        val left = line.minOf { it.x } - margin
        val top = line.minOf { it.y } - margin
        Area(left, top, line.maxOf { it.x + it.width } + margin - left, line.maxOf { it.y + it.height } + margin - top)
    }

/**
 * Returns this block placed in a bubble of screentone or flat tone around it, as [inBubble] places one in a white
 * bubble, with [pixels] telling which pixels are of the bubble's tone, as [tonePixels] does, or light ones for a white
 * bubble [inBubble] missed. The tone must reach past every side of the text, as grey art beside a caption would
 * otherwise be taken for a bubble. A tilted block keeps its box, its translation being laid out along its tilt.
 */
fun TranslationBlock.inToneBubble(
    imageWidth: Int,
    imageHeight: Int,
    others: List<TranslationBlock>,
    pixels: LightPixels,
): TranslationBlock? {
    // Screentone averaged is grey only some pixels away from the outline and the letters' outlines, which narrows the
    // way around large letters to less than a cell sized by them. A tilted block needs no upright room in the bubble
    val isTilted = abs(angle) > MAX_ANGLE
    return REACH_LINES.firstNotNullOfOrNull {
        inBubble(imageWidth, imageHeight, others, pixels, it, MAX_TONE_CELL, !isTilted, isToned = true)
    }
}

/**
 * Returns which pixels of a page of [width] by [height] are of the luminance [tone] once averaged with those around
 * them, as screentone's dots and gaps are, for [inToneBubble]. [luminances] reads a region's luminance row by row.
 */
fun tonePixels(
    width: Int,
    height: Int,
    tone: Int,
    luminances: (left: Int, top: Int, right: Int, bottom: Int) -> IntArray,
) = LightPixels { left, top, right, bottom ->
    val areaLeft = max(0, left - TONE_RADIUS)
    val areaTop = max(0, top - TONE_RADIUS)
    val areaRight = min(width, right + TONE_RADIUS)
    val areaBottom = min(height, bottom + TONE_RADIUS)
    val areaWidth = areaRight - areaLeft
    val values = luminances(areaLeft, areaTop, areaRight, areaBottom)
    // Sums of the area above and left of each point, so each pixel's average is read in four steps
    val sums = LongArray((areaWidth + 1) * (areaBottom - areaTop + 1))
    for (y in 0..<areaBottom - areaTop) {
        var row = 0L
        for (x in 0..<areaWidth) {
            row += values[y * areaWidth + x]
            sums[(y + 1) * (areaWidth + 1) + x + 1] = sums[y * (areaWidth + 1) + x + 1] + row
        }
    }
    val regionWidth = right - left
    BooleanArray(regionWidth * (bottom - top)) { index ->
        val x = left + index % regionWidth - areaLeft
        val y = top + index / regionWidth - areaTop
        val x0 = max(0, x - TONE_RADIUS)
        val y0 = max(0, y - TONE_RADIUS)
        val x1 = min(areaWidth, x + TONE_RADIUS + 1)
        val y1 = min(areaBottom - areaTop, y + TONE_RADIUS + 1)
        val sum = sums[y1 * (areaWidth + 1) + x1] - sums[y0 * (areaWidth + 1) + x1] -
            sums[y1 * (areaWidth + 1) + x0] + sums[y0 * (areaWidth + 1) + x0]
        abs(sum / ((x1 - x0) * (y1 - y0)) - tone) <= TONE_TOLERANCE
    }
}

private fun TranslationBlock.inBubble(
    imageWidth: Int,
    imageHeight: Int,
    others: List<TranslationBlock>,
    pixels: LightPixels,
    reachLines: Float,
    maxCell: Int = Int.MAX_VALUE,
    needsTextRoom: Boolean = true,
    isToned: Boolean = false,
    letters: List<Area> = emptyList(),
): TranslationBlock? {
    val lineHeight = max(symHeight, 1f)
    val cell = (lineHeight / CELLS_PER_LINE).roundToInt().coerceIn(1, maxCell)
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
    var largestTone = emptyList<Int>()
    for (row in boxTop..boxBottom) {
        for (col in boxLeft..boxRight) {
            val seed = row * cols + col
            if (seen[seed] || !lightCells[seed]) continue
            seen[seed] = true
            queue.add(seed)
            component.clear()
            var closed = true
            var pageEdges = 0
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                component.add(index)
                val c = index % cols
                val r = index / cols
                // The page's edge closes a bubble it cuts, while the edge of the search doesn't. Light space along
                // more than one edge of the page, far larger than its text or filling little of its bounds, is a
                // margin or blank art
                val atLeft = c == 0
                val atTop = r == 0
                val atRight = c == cols - 1
                val atBottom = r == rows - 1
                if ((atLeft && left > 0) || (atTop && top > 0) || (atRight && right < imageWidth) ||
                    (atBottom && bottom < imageHeight)
                ) {
                    closed = false
                    continue
                }
                if (atLeft) pageEdges = pageEdges or LEFT_EDGE
                if (atTop) pageEdges = pageEdges or TOP_EDGE
                if (atRight) pageEdges = pageEdges or RIGHT_EDGE
                if (atBottom) pageEdges = pageEdges or BOTTOM_EDGE
                fun visit(next: Int) {
                    if (!seen[next] && lightCells[next]) {
                        seen[next] = true
                        queue.add(next)
                    }
                }
                if (!atLeft) visit(index - 1)
                if (!atRight) visit(index + 1)
                if (!atTop) visit(index - cols)
                if (!atBottom) visit(index + cols)
            }
            val isCutBubble = pageEdges.countOneBits() <= MAX_PAGE_EDGES &&
                component.size * cell * cell <= width * height * MAX_CUT_BUBBLE_AREA &&
                component.size >= component.boundingCells(cols) * MIN_CUT_BUBBLE_FILL
            if (!closed || (pageEdges != 0 && !isCutBubble)) continue
            // Only a toned bubble's own tone: gaps between speed lines that the box reaches past it are tone too
            if (!isToned) {
                component.forEach { inside[it] = true }
            } else if (component.size > largestTone.size) {
                largestTone = component.toList()
            }
        }
    }
    largestTone.forEach { inside[it] = true }

    if (isToned && !inside.surrounds(cols, boxLeft, boxTop, boxRight, boxBottom)) return null

    // Marks enclosed by the bubble's light space, like letters OCR missed, belong to the bubble too. So do the
    // block's own letters where they touch the outline, which would otherwise join the outside and be left unpainted
    // Only along its lines when known, as art the box overlaps, like a fist over a jagged bubble's edge,
    // would be painted as a rectangle too
    val letterCells = BooleanArray(cols * rows) { letters.isEmpty() }
    letters.forEach { word ->
        val fromCol = ((word.x - left) / cell).toInt().coerceIn(0, cols - 1)
        val toCol = ((word.x + word.width - left) / cell).toInt().coerceIn(0, cols - 1)
        val fromRow = ((word.y - top) / cell).toInt().coerceIn(0, rows - 1)
        val toRow = ((word.y + word.height - top) / cell).toInt().coerceIn(0, rows - 1)
        for (row in fromRow..toRow) {
            for (col in fromCol..toCol) letterCells[row * cols + col] = true
        }
    }
    fun isOwnLetter(index: Int): Boolean {
        val col = index % cols
        val row = index / cols
        return !lightCells[index] && letterCells[index] && col in boxLeft..boxRight && row in boxTop..boxBottom
    }

    // A toned bubble's letters are enclosed by its tone, while the box of tilted text reaches past its outline
    fun isKept(index: Int) = inside[index] || (!isToned && isOwnLetter(index))
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
        if (outside[index] || isKept(index)) continue
        outside[index] = true
        val col = index % cols
        val row = index / cols
        if (col > 0) queue.add(index - 1)
        if (col < cols - 1) queue.add(index + 1)
        if (row > 0) queue.add(index - cols)
        if (row < rows - 1) queue.add(index + cols)
    }
    val bubble = BooleanArray(cols * rows) { !outside[it] }
    keepArt(bubble, inside, cols, left, top, cell, lineHeight * ART_MIN_LINES, others + this)
    // Without closed light space around the text there is no bubble, only its letters
    if (inside.none { it }) return null
    if (!needsTextRoom) return copy(bubble = bubbleAreas(bubble, cols, rows, left, top, cell))

    // Bubbles that touch share their light space, so each block only lays its text out on the cells nearer to it
    val neighbours = others.filter { it !== this && it.overlaps(left, top, right, bottom) }
    // The text had room where it was lettered, also over art left unpainted
    val ownCells = BooleanArray(cols * rows) { index ->
        val isInBox = index % cols in boxLeft..boxRight && index / cols in boxTop..boxBottom
        if (!bubble[index] && !isInBox) return@BooleanArray false
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

/**
 * Joins blocks whose boxes overlap once placed, as a note beside a bubble's text that OCR read a line across, so their
 * translations aren't drawn over each other. The joined text, in the order of the blocks, is written in the larger box,
 * or across both when neither is in a bubble. The letters of a block outside a bubble joined to one in a bubble are
 * painted over with the bubble, as they would otherwise stay in English beside the translation.
 */
fun List<TranslationBlock>.withOverlapsJoined(): MutableList<TranslationBlock> {
    val joined = mutableListOf<TranslationBlock>()
    forEach { block ->
        val i = joined.indexOfFirst { it.overlapsMostOf(block) }
        if (i < 0) {
            joined += block
            return@forEach
        }
        val earlier = joined[i]
        val text = earlier.text + "\n" + block.text
        joined[i] = if (earlier.bubble.isEmpty() && block.bubble.isEmpty()) {
            val left = min(earlier.x, block.x)
            val top = min(earlier.y, block.y)
            earlier.copy(
                text = text,
                x = left,
                y = top,
                width = max(earlier.x + earlier.width, block.x + block.width) - left,
                height = max(earlier.y + earlier.height, block.y + block.height) - top,
                symHeight = max(earlier.symHeight, block.symHeight),
                symWidth = max(earlier.symWidth, block.symWidth),
            )
        } else {
            val larger = if (earlier.width * earlier.height >= block.width * block.height) earlier else block
            larger.copy(
                text = text,
                bubble = (earlier.bubbleOrBox() + block.bubbleOrBox()).distinct(),
            )
        }
    }
    return joined
}

private fun TranslationBlock.bubbleOrBox(): List<Area> = bubble.ifEmpty { listOf(Area(x, y, width, height)) }

/**
 * Returns these blocks in reading order: in rows from the top, each read [rightToLeft] or left to right. Translators
 * get a page's blocks as one text, so a sentence running over several bubbles reads in order. A block joins a row
 * when it overlaps at least half of it or of itself vertically.
 */
fun List<TranslationBlock>.inReadingOrder(rightToLeft: Boolean): List<TranslationBlock> {
    val rows = mutableListOf<MutableList<TranslationBlock>>()
    var rowTop = 0f
    var rowBottom = 0f
    sortedBy { it.y }.forEach { block ->
        val overlap = rowBottom - block.y
        if (rows.isNotEmpty() && overlap >= min(block.height, rowBottom - rowTop) / 2) {
            rows.last() += block
            rowBottom = max(rowBottom, block.y + block.height)
        } else {
            rows += mutableListOf(block)
            rowTop = block.y
            rowBottom = block.y + block.height
        }
    }
    return rows.flatMap { row ->
        if (rightToLeft) row.sortedByDescending { it.x + it.width } else row.sortedBy { it.x }
    }
}

/** Whether these cells reach past each side of the box from [left], [top] to [right], [bottom], as a bubble does. */
private fun BooleanArray.surrounds(cols: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
    var above = false
    var below = false
    var before = false
    var after = false
    forEachIndexed { index, isSet ->
        if (!isSet) return@forEachIndexed
        val col = index % cols
        val row = index / cols
        if (col in left..right) {
            above = above || row < top
            below = below || row > bottom
        }
        if (row in top..bottom) {
            before = before || col < left
            after = after || col > right
        }
    }
    return above && below && before && after
}

private fun TranslationBlock.overlapsMostOf(other: TranslationBlock): Boolean {
    val overlapWidth = min(x + width, other.x + other.width) - max(x, other.x)
    val overlapHeight = min(y + height, other.y + other.height) - max(y, other.y)
    if (overlapWidth <= 0 || overlapHeight <= 0) return false
    return overlapWidth * overlapHeight > min(width * height, other.width * other.height) * MIN_JOINED_OVERLAP
}

/**
 * Takes out of [bubble] the drawings it encloses, like a character's face beside the text, so they are neither painted
 * over nor written on: marks taller and wider than [minSize] that none of the [blocks] reaches. Smaller marks are
 * letters OCR missed or specks, and are painted over.
 */
private fun keepArt(
    bubble: BooleanArray,
    inside: BooleanArray,
    cols: Int,
    left: Int,
    top: Int,
    cell: Int,
    minSize: Float,
    blocks: List<TranslationBlock>,
) {
    val rows = bubble.size / cols
    val seen = BooleanArray(bubble.size)
    val queue = ArrayDeque<Int>()
    val mark = mutableListOf<Int>()
    for (seed in bubble.indices) {
        if (seen[seed] || !bubble[seed] || inside[seed]) continue
        seen[seed] = true
        queue.add(seed)
        mark.clear()
        var minCol = cols
        var maxCol = 0
        var minRow = rows
        var maxRow = 0
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            mark.add(index)
            val col = index % cols
            val row = index / cols
            minCol = min(minCol, col)
            maxCol = max(maxCol, col)
            minRow = min(minRow, row)
            maxRow = max(maxRow, row)
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val c = col + dx
                    val r = row + dy
                    if (c !in 0..<cols || r !in 0..<rows) continue
                    val next = r * cols + c
                    if (!seen[next] && bubble[next] && !inside[next]) {
                        seen[next] = true
                        queue.add(next)
                    }
                }
            }
        }
        val markLeft = left + minCol * cell
        val markTop = top + minRow * cell
        val markRight = left + (maxCol + 1) * cell
        val markBottom = top + (maxRow + 1) * cell
        val isArt = markRight - markLeft >= minSize &&
            markBottom - markTop >= minSize &&
            blocks.none { it.overlaps(markLeft, markTop, markRight, markBottom) }
        if (isArt) mark.forEach { bubble[it] = false }
    }
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
private const val MIN_JOINED_OVERLAP = 0.25f
private const val CELLS_PER_LINE = 8f

// Averaged over 7 by 7 pixels, the dots of screentone vary less than this, its outline and white letters much more
private const val TONE_RADIUS = 3
private const val TONE_TOLERANCE = 30
private const val MAX_TONE_CELL = 4
private const val LEFT_EDGE = 1
private const val TOP_EDGE = 2
private const val RIGHT_EDGE = 4
private const val BOTTOM_EDGE = 8
private const val MAX_PAGE_EDGES = 1
private const val MAX_CUT_BUBBLE_AREA = 8
private const val MIN_CUT_BUBBLE_FILL = 0.5
private val REACH_LINES = listOf(6f, 12f, 24f)
private const val LINE_MARGIN_LETTERS = 0.25f
private const val TEXT_MARGIN_LINES = 0.25f

// Letters are about a line high and specks smaller, while a face drawn in a bubble spans a few lines
private const val ART_MIN_LINES = 1.5f

// Bubbles whose inside leaves the text much less room than the letters had are probably not bubbles at all
private const val MIN_TEXT_AREA = 0.6f

/** Cells in the bounding box of these cells of a grid [cols] wide. */
private fun List<Int>.boundingCells(cols: Int): Int =
    (maxOf { it % cols } - minOf { it % cols } + 1) * (maxOf { it / cols } - minOf { it / cols } + 1)
