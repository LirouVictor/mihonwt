package eu.kanade.translation.recognizer

import android.graphics.Bitmap
import android.graphics.Color
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Returns this block grown over the light space around its text on [bitmap], so the translation can use the whole
 * speech bubble rather than only where the English letters were. Each side moves out one pixel at a time while the
 * strip it would add is light, which keeps the box inside the bubble's outline. Blocks on dark art, or tilted, stay
 * as they are.
 */
fun TranslationBlock.grownIntoBubble(bitmap: Bitmap): TranslationBlock {
    if (abs(angle) > MAX_ANGLE) return this
    val lineHeight = max(symHeight, 1f)
    val maxGrowth = (lineHeight * MAX_GROWTH_LINES).toInt()
    var left = x.toInt().coerceIn(0, bitmap.width - 1)
    var top = y.toInt().coerceIn(0, bitmap.height - 1)
    var right = (x + width).toInt().coerceIn(left, bitmap.width - 1)
    var bottom = (y + height).toInt().coerceIn(top, bitmap.height - 1)
    val minLeft = max(0, left - maxGrowth)
    val minTop = max(0, top - maxGrowth)
    val maxRight = min(bitmap.width - 1, right + maxGrowth)
    val maxBottom = min(bitmap.height - 1, bottom + maxGrowth)

    val maxJump = (lineHeight * MAX_JUMP_LINES).toInt()
    var grew = true
    while (grew) {
        grew = false
        bitmap.grownEdge(left, -1, minLeft, maxJump) { bitmap.columnStrip(it, top, bottom) }?.let {
            left = it
            grew = true
        }
        bitmap.grownEdge(right, 1, maxRight, maxJump) { bitmap.columnStrip(it, top, bottom) }?.let {
            right = it
            grew = true
        }
        bitmap.grownEdge(top, -1, minTop, maxJump) { bitmap.rowStrip(it, left, right) }?.let {
            top = it
            grew = true
        }
        bitmap.grownEdge(bottom, 1, maxBottom, maxJump) { bitmap.rowStrip(it, left, right) }?.let {
            bottom = it
            grew = true
        }
    }

    // Stays clear of the outline, and leaves room for the padding the renderer adds around the text
    val margin = lineHeight / 2
    val newLeft = min(x, left + margin)
    val newTop = min(y, top + margin)
    val newRight = max(x + width, right - margin)
    val newBottom = max(y + height, bottom - margin)
    return copy(x = newLeft, y = newTop, width = newRight - newLeft, height = newBottom - newTop)
}

/**
 * Returns where the edge at [edge] can move to in direction [step] without passing [limit], or null if it can't grow.
 * It moves one strip when that strip is light. Otherwise it jumps up to [maxJump] strips past a small mark in the
 * middle of the bubble, like the serifs of an "I" OCR missed, when every strip in between has light ends and the strip
 * after the mark is light; the outline crosses the ends of a strip, so it still stops the box.
 */
private inline fun Bitmap.grownEdge(
    edge: Int,
    step: Int,
    limit: Int,
    maxJump: Int,
    strip: (Int) -> BooleanArray,
): Int? {
    for (k in 1..maxJump) {
        val position = edge + step * k
        if ((position - limit) * step > 0) return null
        val light = strip(position)
        if (light.count { it } >= light.size * MIN_LIGHT_SHARE) return position
        if (!light.isPassable()) return null
    }
    return null
}

private fun BooleanArray.isPassable(): Boolean {
    val end = max(1, (size * LIGHT_END_SHARE).toInt())
    return count { it } >= size * MIN_PASSABLE_LIGHT_SHARE &&
        (0..<end).all { this[it] } &&
        (size - end..<size).all { this[it] }
}

private fun Bitmap.columnStrip(x: Int, top: Int, bottom: Int) = BooleanArray(bottom - top + 1) {
    isLight(getPixel(x, top + it))
}

private fun Bitmap.rowStrip(y: Int, left: Int, right: Int) = BooleanArray(right - left + 1) {
    isLight(getPixel(left + it, y))
}

/** Reads [LightPixels] off this bitmap. */
fun Bitmap.lightPixels() = LightPixels { left, top, right, bottom ->
    val width = right - left
    val colors = IntArray(width * (bottom - top))
    getPixels(colors, 0, width, left, top, width, bottom - top)
    BooleanArray(colors.size) { isLight(colors[it]) }
}

private fun isLight(color: Int): Boolean =
    Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f >= LIGHT_LUMINANCE

private const val MAX_ANGLE = 10f
private const val MAX_GROWTH_LINES = 4
private const val LIGHT_LUMINANCE = 200f

private const val MAX_JUMP_LINES = 1.5f

// Grows past specks and screentone dots, but stops at the bubble's outline, which fills a strip as soon as the box
// reaches it
private const val MIN_LIGHT_SHARE = 0.9f
private const val MIN_PASSABLE_LIGHT_SHARE = 0.5f
private const val LIGHT_END_SHARE = 0.15f
