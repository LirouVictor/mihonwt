// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.model

import kotlinx.serialization.Serializable

/** Text blocks found on one page, in the coordinates of the image they were recognized on. */
@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
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
)

private val HYPHEN_BREAK = Regex("""(?<=\p{L})-\s*\n\s*(?=\p{L})""")
private val LINE_BREAK = Regex("""\s*\n\s*""")

/** Joins text broken over lines, including words hyphenated across them, so it can be wrapped anew. */
fun String.joinLines(): String = replace(HYPHEN_BREAK, "").replace(LINE_BREAK, " ")
