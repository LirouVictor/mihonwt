package eu.kanade.translation.recognizer

import android.graphics.Bitmap
import android.graphics.Color
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.untilted
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

/**
 * Returns this block with the color of the art just around it on [bitmap] as its background, when it isn't in a speech
 * bubble: the median of each channel over a ring a quarter of a letter out, along the text's tilt, so stray lines and
 * letters don't tint it. Each point of the ring is the average of the pixels around it, as screentone is dots on white whose
 * median is white, while the eye sees grey.
 */
fun TranslationBlock.withBackground(bitmap: Bitmap): TranslationBlock {
    if (bubble.isNotEmpty()) return this
    return copy(background = backgroundAround(bitmap.width, bitmap.height, bitmap::averageAround))
}

/**
 * Returns the color of the art around this block on a page of [imageWidth] by [imageHeight], as [withBackground] does,
 * with [colorAround] giving the average color around a pixel.
 */
fun TranslationBlock.backgroundAround(imageWidth: Int, imageHeight: Int, colorAround: (x: Int, y: Int) -> Int): Int {
    val size = untilted()
    // Close to the text, as a box grown over the light space around it stops half a letter from the panel's border
    // or other lines, where a ring further out lands
    val margin = size.letterHeight * RING_MARGIN_LETTERS
    val halfWidth = size.width / 2 + margin
    val halfHeight = size.height / 2 + margin
    val centerX = x + width / 2
    val centerY = y + height / 2
    val radians = Math.toRadians(angle.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    fun sample(u: Float, v: Float): Int {
        val px = (centerX + u * cos - v * sin).toInt().coerceIn(0, imageWidth - 1)
        val py = (centerY + u * sin + v * cos).toInt().coerceIn(0, imageHeight - 1)
        return colorAround(px, py)
    }
    val ring = buildList {
        var u = -halfWidth
        while (u <= halfWidth) {
            add(sample(u, -halfHeight))
            add(sample(u, halfHeight))
            u += RING_STEP
        }
        var v = -halfHeight
        while (v <= halfHeight) {
            add(sample(-halfWidth, v))
            add(sample(halfWidth, v))
            v += RING_STEP
        }
    }
    fun median(shift: Int) = ring.map { (it shr shift) and 0xFF }.sorted()[ring.size / 2]
    return (0xFF shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
}

private fun Bitmap.averageAround(x: Int, y: Int): Int {
    var red = 0
    var green = 0
    var blue = 0
    var count = 0
    for (py in max(0, y - RING_BLUR)..min(height - 1, y + RING_BLUR)) {
        for (px in max(0, x - RING_BLUR)..min(width - 1, x + RING_BLUR)) {
            val color = getPixel(px, py)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            count++
        }
    }
    return Color.rgb(red / count, green / count, blue / count)
}

/**
 * Returns this block placed in the bubble around it on [bitmap] when it has none yet, from the color of the art around
 * it, its [TranslationBlock.background], so the whole bubble is painted rather than a box around the text: a bubble of
 * screentone or flat tone, painted in that tone, or a white one the first search missed, as for text tilted in it,
 * painted white. [others] are the page's blocks.
 */
fun TranslationBlock.withToneBubble(bitmap: Bitmap, others: List<TranslationBlock>): TranslationBlock {
    val color = background ?: return this
    // Dark art around text is rarely a bubble, as the gate's bars beside a white bubble the fill can't find
    if (bubble.isNotEmpty() || luminance(color) < MIN_TONE_LUMINANCE) return this
    if (isLight(color)) {
        return inToneBubble(bitmap.width, bitmap.height, others, bitmap.lightPixels())?.copy(background = null) ?: this
    }
    val pixels = tonePixels(bitmap.width, bitmap.height, luminance(color).toInt()) { left, top, right, bottom ->
        val width = right - left
        val colors = IntArray(width * (bottom - top))
        bitmap.getPixels(colors, 0, width, left, top, width, bottom - top)
        IntArray(colors.size) { luminance(colors[it]).toInt() }
    }
    return inToneBubble(bitmap.width, bitmap.height, others, pixels) ?: this
}

/** Reads [LightPixels] off this bitmap. */
fun Bitmap.lightPixels() = LightPixels { left, top, right, bottom ->
    val width = right - left
    val colors = IntArray(width * (bottom - top))
    getPixels(colors, 0, width, left, top, width, bottom - top)
    BooleanArray(colors.size) { isLight(colors[it]) }
}

private fun isLight(color: Int): Boolean = luminance(color) >= LIGHT_LUMINANCE

private fun luminance(color: Int): Float =
    Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f

private const val MAX_ANGLE = 10f
private const val RING_STEP = 2
private const val RING_BLUR = 3
private const val MAX_GROWTH_LINES = 4
private const val LIGHT_LUMINANCE = 200f
private const val MIN_TONE_LUMINANCE = 80f
private const val RING_MARGIN_LETTERS = 0.25f

private const val MAX_JUMP_LINES = 1.5f

// Grows past specks and screentone dots, but stops at the bubble's outline, which fills a strip as soon as the box
// reaches it
private const val MIN_LIGHT_SHARE = 0.9f
private const val MIN_PASSABLE_LIGHT_SHARE = 0.5f
private const val LIGHT_END_SHARE = 0.15f
