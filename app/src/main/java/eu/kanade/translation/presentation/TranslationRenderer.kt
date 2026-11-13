// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withSave
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs

/**
 * Draws the translation of a page over its image: a white box over each block of text and the translated text in
 * the largest size that fits. Lays the text out once, in the coordinates of [translation]'s image, so drawing it at
 * any zoom only scales the canvas.
 */
class TranslationRenderer(context: Context, translation: PageTranslation) {

    /** Width of the image the blocks are placed on. */
    val imageWidth = translation.imgWidth

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = ResourcesCompat.getFont(context, R.font.comic_neue_bold)
    }

    private val blocks = translation.blocks
        .filter { it.translation.isNotBlank() && it.width > 0 && it.height > 0 }
        .map(::layOut)

    /** Draws onto [canvas] with the image scaled by [scale] and its top left corner at [left], [top]. */
    fun draw(canvas: Canvas, scale: Float, left: Float, top: Float) {
        canvas.withSave {
            translate(left, top)
            scale(scale, scale)
            blocks.forEach { it.draw(this) }
        }
    }

    private fun layOut(block: TranslationBlock): Block {
        // Text sideways on the image, as on a page rotated to fit, is laid out along the long side of its block
        val isSideways = abs(block.angle % 180) in 45f..135f
        val width = if (isSideways) block.height else block.width
        val height = if (isSideways) block.width else block.height
        val symWidth = if (isSideways) block.symHeight else block.symWidth
        val symHeight = if (isSideways) block.symWidth else block.symHeight

        // The text may spill a little past the box, over the rest of the speech bubble
        val box = RectF(0f, 0f, width + symWidth / 2, height + symHeight / 2)
        val textWidth = (width + symWidth * 2).toInt()
        val textHeight = height + symHeight
        return Block(
            centerX = block.x + block.width / 2,
            centerY = block.y + block.height / 2,
            angle = block.angle,
            box = box.apply { offset(-centerX(), -centerY()) },
            cornerRadius = symHeight / 4,
            text = fitText(block.translation, textWidth, textHeight),
        )
    }

    /** Lays [text] out in the largest whole text size that fits [width] by [height] without breaking a word. */
    private fun fitText(text: String, width: Int, height: Float): StaticLayout {
        val longestWord = text.split(' ', '\n').maxBy { it.length }
        var low = 1
        var high = height.toInt().coerceAtLeast(1)
        var best = layOutText(text, width, low.toFloat())
        while (low <= high) {
            val size = (low + high) / 2
            val layout = layOutText(text, width, size.toFloat())
            if (layout.height <= height && textPaint.measureText(longestWord) <= width) {
                best = layout
                low = size + 1
            } else {
                high = size - 1
            }
        }
        return best
    }

    private fun layOutText(text: String, width: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(textPaint), width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    private inner class Block(
        val centerX: Float,
        val centerY: Float,
        val angle: Float,
        val box: RectF,
        val cornerRadius: Float,
        val text: StaticLayout,
    ) {
        fun draw(canvas: Canvas) = canvas.withSave {
            translate(centerX, centerY)
            rotate(angle)
            drawRoundRect(box, cornerRadius, cornerRadius, boxPaint)
            translate(-text.width / 2f, -text.height / 2f)
            text.draw(this)
        }
    }
}
