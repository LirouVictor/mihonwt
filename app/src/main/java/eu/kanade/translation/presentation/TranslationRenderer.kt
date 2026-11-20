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
class TranslationRenderer(
    context: Context,
    translation: PageTranslation,
    var onTap: Boolean = false,
    shortHyphenation: Boolean = false,
) {

    /** Fewest letters left on each side of a hyphen the layout adds inside a word. */
    private val minSyllable = if (shortHyphenation) MIN_SHORT_SYLLABLE else MIN_SYLLABLE

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
        // Hyphenation and capitals follow the language's rules
        textLocale = Locale.forLanguageTag(translation.language)
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
     * width of its original letters, as a narrow "SAY WHAT?!" in a wide bubble. Without [margin], all of it up to the
     * outline.
     */
    private fun roomInBubble(block: TranslationBlock, blocks: List<TranslationBlock>, margin: Boolean = true): Float {
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
        // Tall, condensed lettering in a narrow bubble would otherwise leave the text only a sliver of it
        val gap = if (margin) min(block.symHeight / 2, (right - left) * MAX_MARGIN_SHARE) else 0f
        return max(block.width, 2 * (min(centerX - left, right - centerX) - gap))
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
        // Upright text is widened only as far as its bubble reaches at its height, as two bubbles joined into one
        // painted area are as wide as both together and text widened over that ran across the outline between them
        val widest = if (isUpright) roomInBubble(block, blocks, margin = false) else bubbleWidth
        val maxWidth = min(boxWidth * MAX_WIDENING, widest ?: Float.MAX_VALUE).coerceAtLeast(boxWidth)
        val translation = block.displayedTranslation()
        // Never larger than the original lettering, which a short reply in a big bubble would otherwise dwarf
        val maxSize = symHeight / capHeightRatio
        val lines = fitText(translation, boxWidth.toInt(), maxWidth.toInt(), boxHeight, maxSize)
        // A single word stacked a letter per line in a tall, narrow box, as the original "WHAT?!" was, stays that way
        // when that keeps it larger
        val stacked = if (!isSideways && height >= block.width * MIN_STACKED_RATIO && block.text.isOneWord()) {
            fitText(stackedLetters(translation), boxWidth.toInt(), maxWidth.toInt(), boxHeight, maxSize)
                .takeIf { it.paint.textSize >= lines.paint.textSize }
        } else {
            null
        }
        val text = stacked ?: lines
        val centerX = block.x + block.width / 2
        val centerY = block.y + block.height / 2
        // Lines as wide as the bubble is at their height, as lettering follows a round bubble's shape, where that
        // allows larger text than its largest rectangle
        if (isUpright && stacked == null && bubble.isNotEmpty()) {
            fitInBubble(translation, bubble, block, block.bubbleSharers(blocks), maxSize)
                ?.takeIf { it.text.paint.textSize > text.paint.textSize }
                ?.let { shaped ->
                    return Block(
                        centerX = shaped.left + shaped.text.width / 2f,
                        centerY = shaped.top + shaped.height / 2,
                        angle = block.angle,
                        box = RectF(0f, 0f, shaped.text.width.toFloat(), shaped.height).apply {
                            offset(-centerX(), -centerY())
                        },
                        bubble = bubble,
                        cornerRadius = symHeight / 4,
                        background = null,
                        translation = translation,
                        text = shaped.text,
                    )
                }
        }
        // A box outside a bubble grows with text that had to be widened, so the white still covers it
        val box = RectF(0f, 0f, max(boxWidth, text.width.toFloat()), boxHeight)
        // Text widened past the edge of the page, as in a bubble cut by it, moves in to stay whole
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

    /**
     * The boxes of the other [blocks] with their text in this block's bubble, as in two touching bubbles painted as
     * one area, which this block's text must leave room for.
     */
    private fun TranslationBlock.bubbleSharers(blocks: List<TranslationBlock>): List<RectF> = blocks
        .filter { other ->
            other !== this && bubble.any { area ->
                val x = other.x + other.width / 2
                val y = other.y + other.height / 2
                x >= area.x && x < area.x + area.width && y >= area.y && y < area.y + area.height
            }
        }
        .map { RectF(it.x, it.y, it.x + it.width, it.y + it.height) }

    private fun String.isOneWord() = isNotBlank() && trim().none(Char::isWhitespace)

    /** [text] stacked a letter per line, with each run of punctuation on one line and a blank line between words. */
    private fun stackedLetters(text: String): String = text.split(WORD_BREAK).joinToString("\n\n") { word ->
        STACKED_PIECE.findAll(word).joinToString("\n") { it.value }
    }

    /**
     * Lays [text] out in [bubble] in the largest whole text size up to [maxSize] at which each line fits the width
     * the bubble has at its height, around the center of [block], with the lines centered on the bubble's height
     * there. Of a bubble shared with the text in [others], only the part nearer to [block] is used. Returns null when
     * even the smallest size doesn't fit.
     */
    private fun fitInBubble(
        text: String,
        bubble: List<RectF>,
        block: TranslationBlock,
        others: List<RectF>,
        maxSize: Float,
    ): ShapedText? {
        val centerX = block.x + block.width / 2
        val own = RectF(block.x, block.y, block.x + block.width, block.y + block.height)
        fun isOwn(y: Float) = bubble.any { centerX >= it.left && centerX < it.right && y >= it.top && y < it.bottom } &&
            others.none { it.distanceSquaredTo(centerX, y) < own.distanceSquaredTo(centerX, y) }
        val centerY = own.centerY()
        if (!isOwn(centerY)) return null
        var top = centerY
        while (isOwn(top - 1)) top -= 1
        var bottom = centerY
        while (isOwn(bottom + 1)) bottom += 1
        textPaint.textScaleX = 1f
        fun largest(hyphenate: Boolean): ShapedText? {
            var low = 1
            var high = min(bottom - top, maxSize).toInt()
            var best: ShapedText? = null
            while (low <= high) {
                val size = (low + high) / 2
                val shaped = shapedAt(text, bubble, own, others, centerX, top, bottom, size.toFloat(), hyphenate)
                if (shaped != null) {
                    best = shaped
                    low = size + 1
                } else {
                    high = size - 1
                }
            }
            return best
        }
        val whole = largest(hyphenate = false) ?: return largest(hyphenate = true)
        val hyphenated = largest(hyphenate = true) ?: return whole
        val wholeSize = whole.text.paint.textSize
        return if (wholeSize >= hyphenated.text.paint.textSize * MIN_WHOLE_WORD_SIZE) whole else hyphenated
    }

    private fun shapedAt(
        text: String,
        bubble: List<RectF>,
        own: RectF,
        others: List<RectF>,
        centerX: Float,
        top: Float,
        bottom: Float,
        size: Float,
        hyphenate: Boolean,
    ): ShapedText? {
        textPaint.textSize = size
        val lineHeight = textPaint.fontMetricsInt.let { it.descent - it.ascent }.toFloat()
        val margin = size * BUBBLE_MARGIN
        val maxLines = ((bottom - top - 2 * margin) / lineHeight).toInt()
        for (lines in 1..maxLines) {
            val stackTop = (top + bottom) / 2 - lines * lineHeight / 2
            val spans = (0..<lines).map { line ->
                val lineTop = stackTop + line * lineHeight
                // Taller text leaves a line even less room, so no larger count of lines fits either
                widthAt(bubble, own, others, centerX, lineTop - margin, lineTop + lineHeight + margin, margin, size)
                    ?: return null
            }
            val left = spans.minOf { it.start }
            val right = spans.maxOf { it.endInclusive }
            val layout = layOutText(
                text,
                (right - left).toInt(),
                size,
                hyphenate,
                leftIndents = IntArray(lines) { ceil(spans[it].start - left).toInt() },
                rightIndents = IntArray(lines) { ceil(right - spans[it].endInclusive).toInt() },
            )
            if (layout.lineCount <= lines && layout.breaksOnlyBetweenWords(hyphenate)) {
                return ShapedText(layout, left, stackTop, lines * lineHeight)
            }
        }
        return null
    }

    /**
     * The room [bubble] leaves from [top] to [bottom] around [centerX], short of [margin] on each side, or null where
     * it leaves less than [minWidth] or the bubble doesn't reach. Bubbles are flood filled in rectangles, one per run
     * of light cells, so at each height the runs side by side give the bubble's width there. Only the part nearer to
     * [own] than to the text of [others] is used, so texts sharing a bubble that both grow into it never meet.
     */
    private fun widthAt(
        bubble: List<RectF>,
        own: RectF,
        others: List<RectF>,
        centerX: Float,
        top: Float,
        bottom: Float,
        margin: Float,
        minWidth: Float,
    ): ClosedFloatingPointRange<Float>? {
        var left = -Float.MAX_VALUE
        var right = Float.MAX_VALUE
        val step = (bottom - top) / WIDTH_SAMPLES
        for (sample in 0..WIDTH_SAMPLES) {
            val y = min(top + sample * step, bottom - 0.5f)
            val runs = bubble.filter { y >= it.top && y < it.bottom }.sortedBy { it.left }
            var runLeft = Float.NaN
            var runRight = Float.NaN
            for (run in runs) {
                if (runRight.isNaN() || run.left > runRight + 1) {
                    if (centerX in runLeft..runRight) break
                    runLeft = run.left
                    runRight = run.right
                } else {
                    runRight = max(runRight, run.right)
                }
            }
            if (runLeft.isNaN() || centerX !in runLeft..runRight) return null
            fun isOwn(x: Float) = others.none { it.distanceSquaredTo(x, y) < own.distanceSquaredTo(x, y) }
            if (!isOwn(centerX)) return null
            while (runLeft < centerX && !isOwn(runLeft)) runLeft += 1
            while (runRight > centerX && !isOwn(runRight)) runRight -= 1
            left = max(left, runLeft + margin)
            right = min(right, runRight - margin)
        }
        return (left..right).takeIf { right - left >= minWidth }
    }

    private fun Float.keptOnPage(size: Float, pageSize: Float): Float =
        if (size >= pageSize) this else coerceIn(size / 2, pageSize - size / 2)

    /**
     * The translation as drawn: in capitals when the original lettering was, as models often answer in sentence case,
     * or with a single word in capitals.
     */
    private fun TranslationBlock.displayedTranslation(): String {
        // The line breaker never hyphenated a word with an ellipsis glued before it, as "...DESTRUÍDA!", which a
        // zero width space parts from it. A line can't end at that space, as it ends neither at a space nor a hyphen
        val text = translation.joinLines().replace(LEADING_ELLIPSIS, "$1\u200B")
        return if (this.text.isInCapitals()) text.uppercase(textPaint.textLocale) else text
    }

    private fun RectF.distanceSquaredTo(x: Float, y: Float): Float {
        val dx = max(max(left - x, 0f), x - right)
        val dy = max(max(top - y, 0f), y - bottom)
        return dx * dx + dy * dy
    }

    /**
     * Lays [text] out in the largest whole text size that fits [width] by [height], up to [maxSize]. Words are kept
     * whole unless hyphenating them allows a much larger size, as in a narrow bubble with a long word. Words are never
     * cut between letters: when the box is too narrow for them at a readable size, the text widens up to [maxWidth].
     * Text still too small there gets narrower letters when they make it much larger.
     */
    private fun fitText(text: String, width: Int, maxWidth: Int, height: Float, maxSize: Float): StaticLayout {
        textPaint.textScaleX = 1f
        val normal = fitTextAtScale(text, width, maxWidth, height, maxSize)
        if (normal.paint.textSize >= maxSize * MIN_READABLE_SIZE) return normal
        // Letterers squeeze the lettering of a narrow bubble the same way
        textPaint.textScaleX = CONDENSED_SCALE_X
        val condensed = fitTextAtScale(text, width, maxWidth, height, maxSize)
        textPaint.textScaleX = 1f
        return if (condensed.paint.textSize >= normal.paint.textSize * MIN_CONDENSED_GAIN) condensed else normal
    }

    private fun fitTextAtScale(text: String, width: Int, maxWidth: Int, height: Float, maxSize: Float): StaticLayout {
        // A line may also break after a hyphen, as in a stutter like "D-ESTRUÍDA!", which measured whole was too
        // wide to ever widen to
        val words = text.split(WORD_BREAK).flatMap { it.split(AFTER_HYPHEN) }
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
     * leaving at least [minSyllable] letters on each side. A word too wide for the line is otherwise cut anywhere
     * without a hyphen, as "VEN CEMOS", or before its punctuation, as "NÃO" over a lone "!". A line ending at a
     * hyphen of the text must not get another one, as "VAMO--" did.
     */
    private fun StaticLayout.breaksOnlyBetweenWords(hyphenated: Boolean): Boolean {
        return (0..<lineCount - 1).all { line ->
            val end = getLineEnd(line)
            val last = text[end - 1]
            val addedHyphen = getLineWidth(line) - paint.measureText(text, getLineStart(line), end) >
                paint.measureText("-") / 2
            if (last.isWhitespace()) return@all true
            if (last == '-') return@all !addedHyphen
            if (!hyphenated || !last.isLetter() || !text[end].isLetter()) return@all false
            val before = text.substring(0, end).takeLastWhile(Char::isLetter).length
            val after = text.substring(end).takeWhile(Char::isLetter).length
            addedHyphen && before >= minSyllable && after >= minSyllable
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

    private fun layOutText(
        text: String,
        width: Int,
        size: Float,
        hyphenate: Boolean,
        leftIndents: IntArray? = null,
        rightIndents: IntArray? = null,
    ): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(textPaint), width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setIndents(leftIndents, rightIndents)
            .apply {
                if (hyphenate) {
                    setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                }
            }
            .build()
    }

    /** Text laid out in a bubble's shape, its top left corner at [left], [top] and its lines [height] high. */
    private class ShapedText(val text: StaticLayout, val left: Float, val top: Float, val height: Float)

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

        val AFTER_HYPHEN = Regex("""(?<=[-\u200B])""")

        /** Dots or an ellipsis glued to the start of a word. */
        val LEADING_ELLIPSIS = Regex("""(?<=^|\s)([.…]+)(?=\p{L})""")

        /** Whole words are kept when they allow at least this share of the size hyphenation would. */
        const val MIN_WHOLE_WORD_SIZE = 0.8f

        /** Share of the original letter size below which text is let out of its box rather than shrunk further. */
        const val MIN_READABLE_SIZE = 0.6f

        const val CONDENSED_SCALE_X = 0.7f
        const val MIN_CONDENSED_GAIN = 1.2f

        const val MIN_SYLLABLE = 3
        const val MIN_SHORT_SYLLABLE = 2

        /** How much wider than its box text may grow when its words don't fit at a readable size. */
        const val MAX_WIDENING = 1.4f

        /** Tilt in degrees up to which a block is taken as upright, for widening it in its bubble. */
        const val MAX_UPRIGHT_ANGLE = 10f
        const val MAX_MARGIN_SHARE = 0.1f

        /** Height to width ratio from which a box holding a single word may be lettered a letter per line. */
        const val MIN_STACKED_RATIO = 2f

        /** A letter, or a run of punctuation like "?!", each on a line of its own when stacked. */
        val STACKED_PIECE = Regex("""[\p{L}\p{N}]|[^\p{L}\p{N}]+""")

        /** Room left between text laid out in a bubble's shape and its outline, as a share of the text size. */
        const val BUBBLE_MARGIN = 0.3f

        /** Heights at which the width of a line's stretch of bubble is checked. */
        const val WIDTH_SAMPLES = 8
    }
}
