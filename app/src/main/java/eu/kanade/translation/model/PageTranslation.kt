// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.model

import eu.kanade.translation.recognizer.Word
import kotlinx.serialization.Serializable

/** Text blocks found on one page, in the coordinates of the image they were recognized on. */
@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
    /** The words OCR read, from which [blocks] were grouped, kept so that grouping can be checked on real pages. */
    val words: List<Word> = emptyList(),
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
)

/** A rectangle on the page. */
@Serializable
data class Area(val x: Float, val y: Float, val width: Float, val height: Float)

private val HYPHEN_BREAK = Regex("""(?<=\p{L})-\s*\n\s*(?=\p{L})""")
private val LINE_BREAK = Regex("""\s*\n\s*""")

/** Joins text broken over lines, including words hyphenated across them, so it can be wrapped anew. */
fun String.joinLines(): String = replace(HYPHEN_BREAK, "").replace(LINE_BREAK, " ")

/** Text a language model found on a page image, in a box at page coordinates, with its translation. */
data class FoundText(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
    val translation: String,
)
