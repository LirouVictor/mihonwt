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

    var grew = true
    while (grew) {
        grew = false
        if (left > minLeft && bitmap.isLightColumn(left - 1, top, bottom)) {
            left--
            grew = true
        }
        if (right < maxRight && bitmap.isLightColumn(right + 1, top, bottom)) {
            right++
            grew = true
        }
        if (top > minTop && bitmap.isLightRow(top - 1, left, right)) {
            top--
            grew = true
        }
        if (bottom < maxBottom && bitmap.isLightRow(bottom + 1, left, right)) {
            bottom++
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

private fun Bitmap.isLightColumn(x: Int, top: Int, bottom: Int): Boolean =
    (top..bottom).count { isLight(getPixel(x, it)) } >= (bottom - top + 1) * MIN_LIGHT_SHARE

private fun Bitmap.isLightRow(y: Int, left: Int, right: Int): Boolean =
    (left..right).count { isLight(getPixel(it, y)) } >= (right - left + 1) * MIN_LIGHT_SHARE

private fun isLight(color: Int): Boolean =
    Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f >= LIGHT_LUMINANCE

private const val MAX_ANGLE = 10f
private const val MAX_GROWTH_LINES = 4
private const val LIGHT_LUMINANCE = 200f

// Tolerates specks and screentone dots that scans leave inside bubbles
private const val MIN_LIGHT_SHARE = 0.97f
