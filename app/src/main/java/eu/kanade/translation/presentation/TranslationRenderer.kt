// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import eu.kanade.translation.model.isInCapitals
import eu.kanade.translation.model.joinLines
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the translation of a page over its image: each speech bubble painted white, or a white box over blocks found
 * outside one, and the translated text in the largest size that fits. Lays the text out once, in the coordinates of
 * [translation]'s image, so drawing it at any zoom only scales the canvas. With [onTap], the page keeps its original
 * lettering with each translated block only tinted, and [translationAt] gives the translation of a tapped bubble to
 * show apart.
 */
class TranslationRenderer(context: Context, translation: PageTranslation, var onTap: Boolean = false) {

    /** Width of the image the blocks are placed on. */
    val imageWidth = translation.imgWidth

    private val imageHeight = translation.imgHeight

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // Without antialiasing, as soft edges between the rectangles a bubble is painted with left faint lines
    private val bubblePaint = Paint().apply { color = Color.WHITE }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HIGHLIGHT_COLOR }

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
        .let { blocks ->
            // A block the translator gave back unchanged, as a sign or a name, keeps its original lettering, unless a
            // bubble it shares is painted over for the others
            val painted = blocks.filterNot { it.isUntranslated() }.mapNotNull { it.bubbleBounds() }
            blocks.filter { block ->
                !block.isUntranslated() ||
                    block.bubbleBounds()?.let { bounds -> painted.any { RectF.intersects(it, bounds) } } == true
            }
        }
        .let { blocks -> blocks.map { layOut(it, blocks) } }

    private fun TranslationBlock.isUntranslated(): Boolean {
        fun String.letters() = filter(Char::isLetterOrDigit).lowercase()
        return translation.letters() == text.letters()
    }

    private fun TranslationBlock.bubbleBounds(): RectF? = bubble.takeIf { it.isNotEmpty() }?.let { areas ->
        RectF(
            areas.minOf { it.x },
            areas.minOf { it.y },
            areas.maxOf { it.x + it.width },
            areas.maxOf { it.y + it.height },
        )
    }

    /**
     * Width the upright [block] may take in its bubble: as far as the bubble reaches on both sides of its center along
     * its height, short of the other [blocks] beside it. A bubble shared by several blocks otherwise kept each to the
     * width of its original letters, as a narrow "SAY WHAT?!" in a wide bubble.
     */
    private fun roomInBubble(block: TranslationBlock, blocks: List<TranslationBlock>): Float {
        val centerX = block.x + block.width / 2
        val top = block.y
        val bottom = block.y + block.height
        val rows = block.bubble.filter { it.y < bottom && it.y + it.height > top && centerX in it.x..it.x + it.width }
        if (rows.isEmpty()) return block.width
        var left = rows.maxOf { it.x }
        var right = rows.minOf { it.x + it.width }
        blocks.filter { it !== block && it.y < bottom && it.y + it.height > top }.forEach { other ->
            if (other.x + other.width / 2 < centerX) {
                left = max(left, other.x + other.width)
            } else {
                right = min(right, other.x)
            }
        }
        val margin = block.symHeight / 2
        return max(block.width, 2 * (min(centerX - left, right - centerX) - margin))
    }

    /** Draws onto [canvas] with the image scaled by [scale] and its top left corner at [left], [top]. */
    fun draw(canvas: Canvas, scale: Float, left: Float, top: Float) {
        canvas.withSave {
            translate(left, top)
            scale(scale, scale)
            if (onTap) {
                blocks.forEach { it.drawHighlight(this) }
                return@withSave
            }
            // Bubbles first, as blocks sharing one would otherwise paint over each other's text
            blocks.forEach { it.drawBackground(this) }
            blocks.forEach { it.drawText(this) }
        }
    }

    /**
     * Returns the translation of the highlighted text at [x], [y] on the image when translations are shown on tap, or
     * else of the bubble there, so that each of the bubbles joined into one painted area shows its own text.
     */
    fun translationAt(x: Float, y: Float): String? {
        if (!onTap) return null
        val tapped = blocks.firstOrNull { it.boxContains(x, y) } ?: blocks.firstOrNull { it.bubbleContains(x, y) }
        return tapped?.translation
    }

    private fun layOut(block: TranslationBlock, blocks: List<TranslationBlock>): Block {
        // Text sideways on the image, as on a page rotated to fit, is laid out along the long side of its block
        val isSideways = abs(block.angle % 180) in 45f..135f
        val isUpright = abs(block.angle) < MAX_UPRIGHT_ANGLE
        val width = when {
            isSideways -> block.height
            isUpright -> roomInBubble(block, blocks)
            else -> block.width
        }
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
        val isStacked = !isSideways && height >= block.width * MIN_STACKED_RATIO && translation.none(Char::isWhitespace)
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
        // Text widened past the edge of the page, as in a bubble cut by it, moves in to stay whole
        val centerX = block.x + block.width / 2
        val centerY = block.y + block.height / 2
        return Block(
            centerX = if (isUpright) centerX.keptOnPage(box.width(), imageWidth) else centerX,
            centerY = if (isUpright) centerY.keptOnPage(box.height(), imageHeight) else centerY,
            angle = block.angle,
            box = box.apply { offset(-centerX(), -centerY()) },
            bubble = bubble,
            cornerRadius = symHeight / 4,
            background = block.background.takeIf { bubble.isEmpty() },
            translation = translation,
            text = text,
        )
    }

    private fun Float.keptOnPage(size: Float, pageSize: Float): Float =
        if (size >= pageSize) this else coerceIn(size / 2, pageSize - size / 2)

    /**
     * The translation as drawn: in capitals when the original lettering was, as models often answer in sentence case,
     * or with a single word in capitals.
     */
    private fun TranslationBlock.displayedTranslation(): String {
        val text = translation.joinLines()
        return if (this.text.isInCapitals()) text.uppercase(textPaint.textLocale) else text
    }

    /**
     * Lays [text] out in the largest whole text size that fits [width] by [height], up to [maxSize]. Words are kept
     * whole unless hyphenating them allows a much larger size, as in a narrow bubble with a long word. Words are never
     * cut between letters: when the box is too narrow for them at a readable size, the text widens up to [maxWidth].
     */
    private fun fitText(text: String, width: Int, maxWidth: Int, height: Float, maxSize: Float): StaticLayout {
        val words = text.split(WORD_BREAK)
        // Checked on the layout itself, as a word measured just narrow enough on its own was still cut, as "VENC EMOS"
        val whole = largestFitting(text, { width }, height, maxSize, hyphenate = false) { _, layout ->
            layout.breaksOnlyBetweenWords(hyphenated = false)
        }
        val hyphenated = largestFitting(text, { width }, height, maxSize, hyphenate = true) { _, layout ->
            layout.breaksOnlyBetweenWords(hyphenated = true)
        }
        val best = if (whole.first >= hyphenated.first * MIN_WHOLE_WORD_SIZE) whole else hyphenated
        if (best.first >= maxSize * MIN_READABLE_SIZE) return best.second
        val widenedWidth = { size: Float -> widestWord(words, size).coerceIn(width, maxWidth) }
        val widened = largestFitting(text, widenedWidth, height, maxSize, hyphenate = false) { size, layout ->
            widestWord(words, size) <= maxWidth && layout.breaksOnlyBetweenWords(hyphenated = false)
        }
        return if (widened.first > best.first) widened.second else best.second
    }

    private fun widestWord(words: List<String>, size: Float): Int {
        textPaint.textSize = size
        return ceil(words.maxOf { textPaint.measureText(it) }).toInt()
    }

    /**
     * Whether each line ends at a space or a hyphen, or, when [hyphenated], at a hyphen the layout added inside a word,
     * leaving at least [MIN_SYLLABLE] letters on each side. A word too wide for the line is otherwise cut anywhere
     * without a hyphen, as "VEN CEMOS", or before its punctuation, as "NÃO" over a lone "!".
     */
    private fun StaticLayout.breaksOnlyBetweenWords(hyphenated: Boolean): Boolean {
        return (0..<lineCount - 1).all { line ->
            val end = getLineEnd(line)
            val last = text[end - 1]
            if (last.isWhitespace() || last == '-') return@all true
            if (!hyphenated || !last.isLetter() || !text[end].isLetter()) return@all false
            val addedHyphen = getLineWidth(line) - paint.measureText(text, getLineStart(line), end) >
                paint.measureText("-") / 2
            val before = text.substring(0, end).takeLastWhile(Char::isLetter).length
            val after = text.substring(end).takeWhile(Char::isLetter).length
            addedHyphen && before >= MIN_SYLLABLE && after >= MIN_SYLLABLE
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
        /** The art's color under text outside a bubble, whose translation is then outlined to stand out from it. */
        val background: Int?,
        val translation: String,
        val text: StaticLayout,
    ) {
        fun boxContains(x: Float, y: Float): Boolean {
            return abs(x - centerX) <= box.width() / 2 && abs(y - centerY) <= box.height() / 2
        }

        // One shape, as rectangles filled one by one leave hairline gaps where their scaled edges round apart
        private val bubbleShape = Path().apply { bubble.forEach { addRect(it, Path.Direction.CW) } }

        fun bubbleContains(x: Float, y: Float): Boolean = bubble.any { it.contains(x, y) }

        fun drawBackground(canvas: Canvas) = canvas.withSave {
            drawPath(bubbleShape, bubblePaint)
            if (bubble.isEmpty()) {
                translate(centerX, centerY)
                rotate(angle)
                boxPaint.color = background ?: Color.WHITE
                drawRoundRect(box, cornerRadius, cornerRadius, boxPaint)
            }
        }

        fun drawHighlight(canvas: Canvas) = canvas.withSave {
            translate(centerX, centerY)
            rotate(angle)
            drawRoundRect(box, cornerRadius, cornerRadius, highlightPaint)
        }

        fun drawText(canvas: Canvas) = canvas.withSave {
            translate(centerX, centerY)
            rotate(angle)
            translate(-text.width / 2f, -text.height / 2f)
            if (background != null) {
                val paint = text.paint
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = paint.textSize * OUTLINE_WIDTH
                paint.color = Color.WHITE
                text.draw(this)
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
            }
            text.draw(this)
        }
    }

    private companion object {
        /** Translucent orange, to tint translated text without hiding it. */
        const val HIGHLIGHT_COLOR = 0x40FF9800

        /** Width of the white outline around text outside a bubble, as a share of the text size. */
        const val OUTLINE_WIDTH = 0.2f

        /**
         * Splits words where a line can break without hyphenation. Not after hyphens: the line breaker kept
         * "VAMO-CHAN!" whole and cut it between letters.
         */
        val WORD_BREAK = Regex("""\s+""")

        /** Whole words are kept when they allow at least this share of the size hyphenation would. */
        const val MIN_WHOLE_WORD_SIZE = 0.8f

        /** Share of the original letter size below which text is let out of its box rather than shrunk further. */
        const val MIN_READABLE_SIZE = 0.6f

        const val MIN_SYLLABLE = 3

        /** How much wider than its box text may grow when its words don't fit at a readable size. */
        const val MAX_WIDENING = 1.4f

        /** Tilt in degrees up to which a block is taken as upright, for widening it in its bubble. */
        const val MAX_UPRIGHT_ANGLE = 10f

        /** Height to width ratio from which a box holding a single word is lettered a letter per line. */
        const val MIN_STACKED_RATIO = 3f
    }
}
