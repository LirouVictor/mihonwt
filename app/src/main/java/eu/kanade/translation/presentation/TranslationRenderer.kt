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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the translation of a page over its image: each speech bubble painted white, or a white box over blocks found
 * outside one, and the translated text in the largest size that fits. Lays the text out once, in the coordinates of
 * [translation]'s image, so drawing it at any zoom only scales the canvas. With [onTap], nothing is drawn: the page
 * keeps its original lettering, and [translationAt] gives the translation of a tapped bubble to show apart.
 */
class TranslationRenderer(context: Context, translation: PageTranslation, private val onTap: Boolean = false) {

    /** Width of the image the blocks are placed on. */
    val imageWidth = translation.imgWidth

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // Without antialiasing, as the soft edges of the rectangles a bubble is painted with left faint lines between them
    private val bubblePaint = Paint().apply { color = Color.WHITE }

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
        if (onTap) return
        canvas.withSave {
            translate(left, top)
            scale(scale, scale)
            // Bubbles first, as blocks sharing one would otherwise paint over each other's text
            blocks.forEach { it.drawBackground(this) }
            blocks.forEach { it.drawText(this) }
        }
    }

    /** Returns the translation of the block at [x], [y] on the image when translations are shown on tap. */
    fun translationAt(x: Float, y: Float): String? {
        if (!onTap) return null
        return blocks.firstOrNull { it.contains(x, y) }?.translation
    }

    private fun layOut(block: TranslationBlock): Block {
        // Text sideways on the image, as on a page rotated to fit, is laid out along the long side of its block
        val isSideways = abs(block.angle % 180) in 45f..135f
        val width = if (isSideways) block.height else block.width
        val height = if (isSideways) block.width else block.height
        val symWidth = if (isSideways) block.symHeight else block.symWidth
        val symHeight = if (isSideways) block.symWidth else block.symHeight

        // Text wider than its box ran into the art and the next speech bubble. A box found in a bubble is already
        // clear of its outline
        val padding = if (block.bubble.isEmpty()) 1f else 0f
        val boxWidth = width + padding * symWidth / 2
        val boxHeight = height + padding * symHeight / 2
        val bubble = block.bubble.map { RectF(it.x, it.y, it.x + it.width, it.y + it.height) }
        // A word too long for the box may take some of the bubble's width beside it, but never the art's
        val bubbleWidth = bubble.maxOfOrNull { it.right }?.minus(bubble.minOf { it.left })
        val maxWidth = min(boxWidth * MAX_WIDENING, bubbleWidth ?: Float.MAX_VALUE).coerceAtLeast(boxWidth)
        val translation = block.displayedTranslation()
        // Lettering stacked a letter per line in a tall, narrow bubble stays that way
        val isStacked = !isSideways && height >= width * MIN_STACKED_RATIO && translation.none(Char::isWhitespace)
        // Never larger than the original lettering, which a short reply in a big bubble would otherwise dwarf
        val text = fitText(
            if (isStacked) translation.toList().joinToString("\n") else translation,
            boxWidth.toInt(),
            maxWidth.toInt(),
            boxHeight,
            symHeight / capHeightRatio,
        )
        // A box outside a bubble grows with text that had to be widened, so the white still covers it
        val box = RectF(0f, 0f, max(boxWidth, text.width.toFloat()), boxHeight)
        return Block(
            centerX = block.x + block.width / 2,
            centerY = block.y + block.height / 2,
            angle = block.angle,
            box = box.apply { offset(-centerX(), -centerY()) },
            bubble = bubble,
            cornerRadius = symHeight / 4,
            translation = translation,
            text = text,
        )
    }

    /**
     * The translation as drawn: in capitals when the original lettering was, as models often answer in sentence case,
     * or with a single word in capitals.
     */
    private fun TranslationBlock.displayedTranslation(): String {
        val text = translation.joinLines()
        val letters = this.text.filter(Char::isLetter)
        val isCapitals = letters.isNotEmpty() && letters.count(Char::isUpperCase) >= letters.length * MIN_CAPITALS
        return if (isCapitals) text.uppercase(textPaint.textLocale) else text
    }

    /**
     * Lays [text] out in the largest whole text size that fits [width] by [height], up to [maxSize]. Words are kept
     * whole unless hyphenating them allows a much larger size, as in a narrow bubble with a long word. Words are never
     * cut between letters: when the box is too narrow for them at a readable size, the text widens up to [maxWidth].
     */
    private fun fitText(text: String, width: Int, maxWidth: Int, height: Float, maxSize: Float): StaticLayout {
        val words = text.split(WORD_BREAK)
        val whole = largestFitting(text, { width }, height, maxSize, hyphenate = false) { size, _ ->
            textPaint.textSize = size
            words.all { textPaint.measureText(it) <= width }
        }
        val hyphenated = largestFitting(text, { width }, height, maxSize, hyphenate = true) { _, layout ->
            layout.breaksOnlyBetweenWords()
        }
        val best = if (whole.first >= hyphenated.first * MIN_WHOLE_WORD_SIZE) whole else hyphenated
        if (best.first >= maxSize * MIN_READABLE_SIZE) return best.second
        val widenedWidth = { size: Float -> widestWord(words, size).coerceIn(width, maxWidth) }
        val widened = largestFitting(text, widenedWidth, height, maxSize, hyphenate = false) { size, _ ->
            widestWord(words, size) <= maxWidth
        }
        return if (widened.first > best.first) widened.second else best.second
    }

    private fun widestWord(words: List<String>, size: Float): Int {
        textPaint.textSize = size
        return ceil(words.maxOf { textPaint.measureText(it) }).toInt()
    }

    /**
     * Whether each line ends between words, or inside one at what is likely a hyphenation point, leaving at least
     * [MIN_SYLLABLE] letters on each side. A word too long for the width is otherwise cut anywhere, as "AIR A!".
     */
    private fun StaticLayout.breaksOnlyBetweenWords(): Boolean {
        return (0..<lineCount - 1).all { line ->
            val end = getLineEnd(line)
            if (!text[end - 1].isLetter() || !text[end].isLetter()) return@all true
            val before = text.substring(0, end).takeLastWhile(Char::isLetter).length
            val after = text.substring(end).takeWhile(Char::isLetter).length
            before >= MIN_SYLLABLE && after >= MIN_SYLLABLE
        }
    }

    private inline fun largestFitting(
        text: String,
        width: (size: Float) -> Int,
        height: Float,
        maxSize: Float,
        hyphenate: Boolean,
        fits: (size: Float, layout: StaticLayout) -> Boolean,
    ): Pair<Int, StaticLayout> {
        var low = 1
        var high = min(height, maxSize).toInt().coerceAtLeast(1)
        var best = 1 to layOutText(text, width(1f), 1f, hyphenate)
        while (low <= high) {
            val size = (low + high) / 2
            val layout = layOutText(text, width(size.toFloat()), size.toFloat(), hyphenate)
            if (layout.height <= height && fits(size.toFloat(), layout)) {
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
        val bubble: List<RectF>,
        val cornerRadius: Float,
        val translation: String,
        val text: StaticLayout,
    ) {
        fun contains(x: Float, y: Float): Boolean {
            return bubble.any { it.contains(x, y) } ||
                (abs(x - centerX) <= box.width() / 2 && abs(y - centerY) <= box.height() / 2)
        }

        fun drawBackground(canvas: Canvas) = canvas.withSave {
            bubble.forEach { drawRect(it, bubblePaint) }
            if (bubble.isEmpty()) {
                translate(centerX, centerY)
                rotate(angle)
                drawRoundRect(box, cornerRadius, cornerRadius, boxPaint)
            }
        }

        fun drawText(canvas: Canvas) = canvas.withSave {
            translate(centerX, centerY)
            rotate(angle)
            translate(-text.width / 2f, -text.height / 2f)
            text.draw(this)
        }
    }

    private companion object {
        /** Splits words, and after hyphens, where a line can break without hyphenation. */
        val WORD_BREAK = Regex("""\s+|(?<=-)""")

        /** Whole words are kept when they allow at least this share of the size hyphenation would. */
        const val MIN_WHOLE_WORD_SIZE = 0.8f

        /** Share of the original letter size below which text is let out of its box rather than shrunk further. */
        const val MIN_READABLE_SIZE = 0.6f

        const val MIN_SYLLABLE = 3

        /** How much wider than its box text may grow when its words don't fit at a readable size. */
        const val MAX_WIDENING = 1.4f

        /** Height to width ratio from which a box holding a single word is lettered a letter per line. */
        const val MIN_STACKED_RATIO = 3f

        /** Share of capital letters above which the original counts as lettered in capitals, despite OCR misreads. */
        const val MIN_CAPITALS = 0.8f
    }
}
