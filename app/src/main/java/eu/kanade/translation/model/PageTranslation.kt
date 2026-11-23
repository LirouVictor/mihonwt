// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.model

import eu.kanade.translation.recognizer.Word
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Text blocks found on one page, in the coordinates of the image they were recognized on. */
@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
    /** The words OCR read, from which [blocks] were grouped, kept so that grouping can be checked on real pages. */
    val words: List<Word> = emptyList(),
    /** The language of the translations, as a BCP 47 tag. Chapters translated before there was a choice are in it. */
    var language: String = "pt-BR",
)

@Serializable
data class TranslationBlock(
    var text: String,
    var translation: String = "",
    var width: Float,
    var height: Float,
    var x: Float,
    var y: Float,
    var symHeight: Float,
    var symWidth: Float,
    val angle: Float,
    /** The inside of the speech bubble around the text, painted over before the translation is drawn. */
    val bubble: List<Area> = emptyList(),
    /** The color of the art around text outside a bubble, which covers it before its outlined translation is drawn. */
    val background: Int? = null,
) {
    /**
     * The rest of this block's text, on the next piece of a strip cut through it. This block then holds the whole text,
     * and its translation is split between them, [shareAbove] of it here.
     */
    @Transient
    var continuation: TranslationBlock? = null

    @Transient
    var shareAbove = 1f

    /** Whether this block is the [continuation] of one on the piece above, translated with it. */
    @Transient
    var continuesAbove = false
}

/** A rectangle on the page. */
@Serializable
data class Area(val x: Float, val y: Float, val width: Float, val height: Float)

// Also with only a space after the hyphen, as a line break becomes when a model rereads the text
private val HYPHEN_BREAK = Regex("""(?<=\p{L})-\s+(?=\p{L})""")
private val LINE_BREAK = Regex("""\s*\n\s*""")

/** Joins text broken over lines, including words hyphenated across them, so it can be wrapped anew. */
fun String.joinLines(): String = joinHyphenatedWords().replace(LINE_BREAK, " ")

/** Joins words hyphenated across lines, as "PRO-\nTECTING" or "DE- STROYED". */
fun String.joinHyphenatedWords(): String = replace(HYPHEN_BREAK, "")

/**
 * Whether this text is lettered in capitals. Most of its letters is enough, as OCR reads some capitals as small
 * letters, like the "e" of "SMASHED me".
 */
fun String.isInCapitals(): Boolean {
    val letters = filter(Char::isLetter)
    return letters.count(Char::isUpperCase) * 2 > letters.length
}

/** Text a language model found on a page image, in a box at page coordinates, with its translation. */
data class FoundText(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
    val translation: String,
)

/**
 * The name a page read online is translated under: its number padded as the downloader names its file, without the
 * extension only a download knows.
 */
fun onlinePageName(index: Int, count: Int): String {
    val digits = count.toString().length.coerceAtLeast(3)
    return "%0${digits}d".format(Locale.ENGLISH, index + 1)
}

/**
 * Returns the translation of the page named [name], also when the chapter was translated while downloaded and is read
 * online or the other way round, so the name differs by its extension.
 */
fun Map<String, PageTranslation>.forPage(name: String): PageTranslation? {
    get(name)?.let { return it }
    val stem = name.substringBeforeLast('.')
    return entries.firstOrNull { it.key.substringBeforeLast('.') == stem }?.value
}

/** The size of a block's text along its own tilt, and of its letters. */
data class UntiltedSize(val width: Float, val height: Float, val letterHeight: Float)

/**
 * Returns the size of this block's text along its tilt. OCR gives upright boxes, so text tilted 20°, as a shout
 * lettered aslant, has a box and letters much taller and wider than they are, and its translation drawn at that size
 * and tilt covered far more than the original. Text upright or set sideways, and tilts too close to 45° to tell width
 * from height, keep their box.
 */
fun TranslationBlock.untilted(): UntiltedSize {
    val unchanged = UntiltedSize(width, height, symHeight)
    val tilt = Math.toRadians(abs(angle % 180).let { min(it, 180 - it) }.toDouble())
    if (tilt < Math.toRadians(MIN_TILT) || tilt >= Math.PI / 4) return unchanged
    val cos = cos(tilt).toFloat()
    val sin = sin(tilt).toFloat()
    // Box width = w·cos + h·sin and height = w·sin + h·cos, solved for w and h
    val determinant = cos * cos - sin * sin
    if (determinant < MIN_DETERMINANT) return unchanged
    val untiltedWidth = (width * cos - height * sin) / determinant
    val untiltedHeight = (height * cos - width * sin) / determinant
    if (untiltedWidth <= 0f || untiltedHeight <= 0f) return unchanged
    // A letter about as wide as it is tall
    return UntiltedSize(untiltedWidth, untiltedHeight, symHeight / (cos + sin))
}

// As the renderer takes blocks up to 10° as upright
private const val MIN_TILT = 10.0
private const val MIN_DETERMINANT = 0.3f
