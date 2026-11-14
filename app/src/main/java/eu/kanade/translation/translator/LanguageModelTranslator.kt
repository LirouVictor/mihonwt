// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.joinLines
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/** Translates every page of a chapter in a single request to a language model. */
abstract class LanguageModelTranslator : PageTranslator() {

    /**
     * Also drops blocks the model marks as watermarks. The [series] title lets the model use the names and tone it
     * knows from it, [images] of the pages let it read the text in context, and [glossary] keeps the terms of earlier
     * chapters translated the same way.
     */
    override suspend fun translate(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): Map<String, String> {
        return try {
            translateBatch(pages, series, images, glossary, onRetry)
        } catch (e: TruncatedReplyException) {
            // A whole chapter can exceed the model's output limit, above all when its reasoning counts against it
            if (pages.size < 2) throw e
            pages.entries.chunked((pages.size + 1) / 2).fold(emptyMap()) { terms, half ->
                terms + translate(half.associate { it.key to it.value }, series, images, glossary, onRetry)
            }
        }
    }

    private suspend fun translateBatch(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): Map<String, String> {
        // Blocks are numbered rather than listed: with a list, the model sometimes merges two fragments of a sentence
        // and every later block on the page gets the translation of the one after it. Their text goes without line
        // breaks, which the model would copy into translations that need longer lines.
        val input = buildJsonObject {
            pages.forEach { (name, page) ->
                putJsonObject(name) {
                    page.blocks.forEachIndexed { i, block -> put(i.toString(), block.text.joinLines()) }
                }
            }
        }
        val systemPrompt = buildString {
            append(SYSTEM_PROMPT)
            append("\n\nThe pages are from \"$series\".")
            if (glossary.isNotEmpty()) {
                append("\n\nEarlier chapters translated these names and terms like this; keep them: ")
                append(JsonObject(glossary.mapValues { JsonPrimitive(it.value) }))
            }
        }
        val pageImages = images.filterKeys { it in pages }
        var output = request(systemPrompt, input.toString(), pageImages, onRetry)
        // Models now and then skip a block, which would stay in English; a second answer is usually complete
        if (countMissing(pages, output) > 0) {
            val retry = request(systemPrompt, input.toString(), pageImages, onRetry)
            if (countMissing(pages, retry) < countMissing(pages, output)) output = retry
        }

        pages.forEach { (name, page) ->
            val translations = output[name] as? JsonObject
            page.blocks.forEachIndexed { i, block ->
                block.translation = (translations?.get(i.toString()) as? JsonPrimitive)?.contentOrNull ?: block.text
            }
            page.blocks = page.blocks.filterNot { it.translation.contains(WATERMARK_PLACEHOLDER) }.toMutableList()
        }
        return (output[GLOSSARY_KEY] as? JsonObject).orEmpty()
            .mapNotNull { (term, translation) -> (translation as? JsonPrimitive)?.contentOrNull?.let { term to it } }
            .toMap()
    }

    private suspend fun request(
        systemPrompt: String,
        input: String,
        images: Map<String, ByteArray>,
        onRetry: (attempt: Int) -> Unit,
    ): JsonObject {
        val reply = complete(systemPrompt, input, images, onRetry)
        // Without a JSON mode, models may wrap the object in a code block or a sentence
        return runCatching {
            Json.parseToJsonElement(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)).jsonObject
        }.getOrElse { throw IOException("$name returned invalid JSON: ${reply.take(200)}") }
    }

    private fun countMissing(pages: Map<String, PageTranslation>, output: JsonObject): Int {
        return pages.entries.sumOf { (name, page) ->
            val translations = output[name] as? JsonObject
            page.blocks.indices.count { (translations?.get(it.toString()) as? JsonPrimitive)?.contentOrNull == null }
        }
    }

    /** Returns the names of the models this API key can use, to pick one from. */
    abstract suspend fun models(): List<String>

    /** Returns the model's reply to [userText], which should be a JSON object, with [images] if it [readsImages]. */
    protected abstract suspend fun complete(
        systemPrompt: String,
        userText: String,
        images: Map<String, ByteArray>,
        onRetry: (attempt: Int) -> Unit,
    ): String

    /** Thrown when the reply hit the model's output limit, so a smaller batch may fit. */
    class TruncatedReplyException(name: String) :
        IOException("$name cut the reply short: the model's output limit is too small even for one page")

    private companion object {
        private const val WATERMARK_PLACEHOLDER = "RTMTH"

        // Page names are file names, which never start with an underscore
        private const val GLOSSARY_KEY = "_glossary"

        private val SYSTEM_PROMPT = """
            ## System Prompt for Manhwa/Manga/Manhua Translation

            You are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua) while preserving the original structure and removing any watermarks or site links.

            **Here's how you should operate:**

            1. **Input:** You'll receive a JSON object where keys are image filenames (e.g., "001.jpg") and values are objects mapping a block number to the text extracted from one speech bubble or caption.

            2. **Translation:** Translate every block to the target language `Brazilian Portuguese`, as a good fan translator would today: natural, colloquial speech that suits each character, with current everyday words. Avoid formal, old-fashioned or overly literal wording and false cognates (e.g. "amusing" is "divertido", never "amusante"). Adapt idioms and expressions to fit the target language's cultural context.

            3. **Names:** Keep the names of characters, places, attacks and powers as in the original (e.g. "Acrobatic Silky" stays "Acrobatic Silky"), unless the series has a well-known Brazilian Portuguese name for them. Keep honorifics such as "-san" and "-sama".

            4. **One block, one translation:** The text was read by OCR, so a sentence may be split across consecutive blocks. Translate each block on its own, keeping each part of the sentence in its own block. Never merge blocks or move text from one block to another. Fix obvious OCR mistakes using the context.

            5. **Watermark and noise removal:** Replace any watermarks or site links (e.g., "colamanga.com"), and any block that isn't readable text (OCR noise from the artwork), with the placeholder "$WATERMARK_PLACEHOLDER".

            6. **Structure Preservation:** Return the same image filenames and, for each, exactly the same block numbers as the input.

            7. **Page images:** When an image of a page follows the text, use it to fix OCR mistakes and to tell who is speaking and in what tone. Still translate only the given blocks.

            8. **Glossary:** Also return, under the key "$GLOSSARY_KEY", an object mapping each name and recurring term on these pages (characters, places, attacks, powers, invented words) to how you translated it.

            **Example:**

            **Input:**

            ```json
            {"001.jpg":{"0":"IF IT","1":"ISN'T MISS VAMOLA!","2":"COULD I GET YOUR HELP?"},"002.jpg":{"0":"english3","1":"colamanga.com","2":"Rp aouariun"}}
            ```

            **Output:**

            ```json
            {"001.jpg":{"0":"OLHA SÓ","1":"SE NÃO É A SRTA. VAMOLA!","2":"PODERIA ME AJUDAR?"},"002.jpg":{"0":"portuguese3","1":"$WATERMARK_PLACEHOLDER","2":"$WATERMARK_PLACEHOLDER"},"$GLOSSARY_KEY":{"Vamola":"Vamola"}}
            ```

            Return {[filename:string]:{[block:string]:string}, "$GLOSSARY_KEY":{[term:string]:string}}
        """.trimIndent()
    }
}
