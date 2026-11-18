package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.isInCapitals
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
        before: PageTranslation?,
    ): Map<String, String> {
        var previous = before?.blocks?.map { it.text.joinLines().toSentenceCase() }.orEmpty()
        pages.values.forEach { page ->
            val texts = page.blocks.map { it.text.joinLines().toSentenceCase() }
            // A sentence can go on from the previous page, as "And you..." then "...are already doing that"
            val translations = translatePage(texts, previous.takeLast(CONTEXT_BLOCKS) + texts, glossary, onRetry)
            page.blocks.forEachIndexed { i, block -> block.translation = translations[i] }
            previous = texts
        }
        return emptyMap()
    }

    /**
     * Returns the translation of each of [texts], the blocks of one page in reading order, keeping the terms of the
     * series' [glossary] as it gives them when the service can. [context] is the text around them, to translate them
     * as parts of one conversation when the service can.
     */
    protected abstract suspend fun translatePage(
        texts: List<String>,
        context: List<String>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): List<String>

    private companion object {
        const val CONTEXT_BLOCKS = 3
    }
}

/**
 * Returns this text in sentence case when it is in capitals, as comic lettering usually is: translation models are
 * trained on ordinary text, and leave words in capitals untranslated or take them for names. The renderer puts the
 * translation back in capitals.
 */
internal fun String.toSentenceCase(): String {
    if (!isInCapitals()) return this
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

private const val SENTENCE_ENDS = ".!?"
private val PRONOUN_I = Regex("""\bi\b""")
