// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withSave
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.joinLines
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

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
        // Translations are in Brazilian Portuguese, and hyphenation follows the locale's rules
        textLocale = Locale.forLanguageTag("pt-BR")
    }

    /** Height of a capital letter as a share of the text size, to match the size of the original letters. */
    private val capHeightRatio = Rect().let { bounds ->
        textPaint.textSize = 100f
        textPaint.getTextBounds("H", 0, 1, bounds)
        bounds.height() / 100f
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

        // Text wider than its box ran into the art and the next speech bubble
        val box = RectF(0f, 0f, width + symWidth / 2, height + symHeight / 2)
        val textWidth = box.width().toInt()
        val textHeight = box.height()
        return Block(
            centerX = block.x + block.width / 2,
            centerY = block.y + block.height / 2,
            angle = block.angle,
            box = box.apply { offset(-centerX(), -centerY()) },
            cornerRadius = symHeight / 4,
            // Translations of chapters saved before line breaks were dropped still carry the English ones, and a
            // longer Portuguese line kept whole forced a tiny text size
            // Never larger than the original lettering, which a short reply in a big bubble would otherwise dwarf
            text = fitText(block.translation.joinLines(), textWidth, textHeight, symHeight / capHeightRatio),
        )
    }

    /**
     * Lays [text] out in the largest whole text size that fits [width] by [height], up to [maxSize]. Words are kept
     * whole unless hyphenating them allows a much larger size, as in a narrow bubble with a long word.
     */
    private fun fitText(text: String, width: Int, height: Float, maxSize: Float): StaticLayout {
        val words = text.split(WORD_BREAK)
        val whole = largestFitting(text, width, height, maxSize, hyphenate = false) { size ->
            textPaint.textSize = size
            words.all { textPaint.measureText(it) <= width }
        }
        val hyphenated = largestFitting(text, width, height, maxSize, hyphenate = true) { true }
        return if (whole.first >= hyphenated.first * MIN_WHOLE_WORD_SIZE) whole.second else hyphenated.second
    }

    private inline fun largestFitting(
        text: String,
        width: Int,
        height: Float,
        maxSize: Float,
        hyphenate: Boolean,
        fits: (size: Float) -> Boolean,
    ): Pair<Int, StaticLayout> {
        var low = 1
        var high = min(height, maxSize).toInt().coerceAtLeast(1)
        var best = 1 to layOutText(text, width, 1f, hyphenate)
        while (low <= high) {
            val size = (low + high) / 2
            val layout = layOutText(text, width, size.toFloat(), hyphenate)
            if (layout.height <= height && fits(size.toFloat())) {
                best = size to layout
                low = size + 1
            } else {
                high = size - 1
            }
        }
        return best
    }

    private fun layOutText(text: String, width: Int, size: Float, hyphenate: Boolean): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(textPaint), width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .apply {
                if (hyphenate) {
                    setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                }
            }
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

    private companion object {
        /** Splits words, and after hyphens, where a line can break without hyphenation. */
        val WORD_BREAK = Regex("""\s+|(?<=-)""")

        /** Whole words are kept when they allow at least this share of the size hyphenation would. */
        const val MIN_WHOLE_WORD_SIZE = 0.8f
    }
}
