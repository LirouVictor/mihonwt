package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.joinLines
import java.util.Locale

/**
 * Translates with a machine translation service, which is faster than a language model and doesn't make up words,
 * but can't fix OCR mistakes, drop watermarks or add to the glossary.
 */
abstract class BlockTranslator : PageTranslator() {

    override suspend fun translate(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): Map<String, String> {
        pages.values.forEach { page ->
            val translations =
                translatePage(page.blocks.map { it.text.joinLines().toSentenceCase() }, glossary, onRetry)
            page.blocks.forEachIndexed { i, block -> block.translation = translations[i] }
        }
        return emptyMap()
    }

    /**
     * Returns the translation of each of [texts], the blocks of one page in reading order, keeping the terms of the
     * series' [glossary] as it gives them when the service can.
     */
    protected abstract suspend fun translatePage(
        texts: List<String>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): List<String>
}

/**
 * Returns this text in sentence case when it is in capitals, as comic lettering usually is: translation models are
 * trained on ordinary text, and leave words in capitals untranslated or take them for names. The renderer puts the
 * translation back in capitals.
 */
internal fun String.toSentenceCase(): String {
    val letters = filter(Char::isLetter)
    if (letters.isEmpty() || letters.count(Char::isUpperCase) < letters.length * MIN_CAPITALS) return this
    val text = StringBuilder(lowercase(Locale.ENGLISH))
    var sentenceStart = true
    text.indices.forEach { i ->
        if (sentenceStart && text[i].isLetter()) {
            text[i] = text[i].uppercaseChar()
            sentenceStart = false
        } else if (text[i] in SENTENCE_ENDS) {
            sentenceStart = true
        }
    }
    return text.replace(PRONOUN_I, "I")
}

private const val MIN_CAPITALS = 0.8f
private const val SENTENCE_ENDS = ".!?"
private val PRONOUN_I = Regex("""\bi\b""")
