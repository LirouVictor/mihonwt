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
                .toMutableList()
            // With the whole page as context, a service sometimes moves words of other blocks into one, so such a
            // block is translated again beside only its neighbors
            leakedTranslations(texts, translations).forEach { i ->
                val neighbors = (previous + texts.take(i)).takeLast(LEAK_CONTEXT_BEFORE) +
                    texts.subList(i, minOf(i + 1 + LEAK_CONTEXT_AFTER, texts.size))
                translations[i] = translatePage(listOf(texts[i]), neighbors, glossary, onRetry).single()
            }
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
        const val LEAK_CONTEXT_BEFORE = 2
        const val LEAK_CONTEXT_AFTER = 1
    }
}

/**
 * Returns the indices of the [translations] of [sources] that seem to hold words of other blocks: those much longer
 * or shorter than their source, and those sharing a run of words with another block's translation whose source
 * doesn't share words with theirs.
 */
internal fun leakedTranslations(sources: List<String>, translations: List<String>): Set<Int> {
    val leaked = mutableSetOf<Int>()
    sources.indices.forEach { i ->
        val sourceWords = wordsOf(sources[i]).size
        val translatedWords = wordsOf(translations[i]).size
        val tooLong = translatedWords > sourceWords * MAX_WORD_RATIO + EXTRA_WORDS
        val tooShort = sourceWords >= MIN_WORDS_FOR_SHORT && translatedWords < sourceWords * MIN_WORD_RATIO
        if (tooLong || tooShort) leaked += i
    }
    val translatedRuns = translations.map { runsOf(it, SHARED_RUN) }
    val sourceRuns = sources.map { runsOf(it, SHARED_SOURCE_RUN) }
    for (i in translations.indices) {
        for (j in i + 1..<translations.size) {
            val shared = (translatedRuns[i] intersect translatedRuns[j]).isNotEmpty()
            if (shared && (sourceRuns[i] intersect sourceRuns[j]).isEmpty()) leaked += listOf(i, j)
        }
    }
    return leaked
}

private fun wordsOf(text: String) = WORD.findAll(text.lowercase()).map { it.value }.toList()

private fun runsOf(text: String, length: Int) = wordsOf(text).windowed(length).toSet()

private val WORD = Regex("""\p{L}+(?:['’]\p{L}+)?""")

// Portuguese and Spanish take a few more words than English, but not twice as many
private const val MAX_WORD_RATIO = 1.8
private const val EXTRA_WORDS = 2
private const val MIN_WORD_RATIO = 0.6
private const val MIN_WORDS_FOR_SHORT = 6
private const val SHARED_RUN = 4
private const val SHARED_SOURCE_RUN = 3

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
