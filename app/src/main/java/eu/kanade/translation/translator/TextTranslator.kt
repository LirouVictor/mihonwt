// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.translation.TranslationService
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Translates every page of a chapter in a single request to a language model. */
abstract class TextTranslator {

    /** Shown in error messages. */
    protected abstract val name: String

    // Not NetworkHelper's client: its verbose logging would print the API key header, and its read timeout is too
    // short for a whole chapter.
    private val client = OkHttpClient.Builder()
        .readTimeout(3.minutes)
        .callTimeout(5.minutes)
        .build()

    /** Fills [eu.kanade.translation.model.TranslationBlock.translation] and drops blocks marked as watermarks. */
    suspend fun translate(pages: Map<String, PageTranslation>, onRetry: (attempt: Int) -> Unit = {}) {
        // Blocks are numbered rather than listed: with a list, the model sometimes merges two fragments of a sentence
        // and every later block on the page gets the translation of the one after it
        val input = buildJsonObject {
            pages.forEach { (name, page) ->
                putJsonObject(name) { page.blocks.forEachIndexed { i, block -> put(i.toString(), block.text) } }
            }
        }
        val reply = complete(SYSTEM_PROMPT, input.toString(), onRetry)
        // Without a JSON mode, models may wrap the object in a code block or a sentence
        val output = runCatching {
            Json.parseToJsonElement(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)).jsonObject
        }.getOrElse { throw IOException("$name returned invalid JSON: ${reply.take(200)}") }

        pages.forEach { (name, page) ->
            val translations = output[name] as? JsonObject
            page.blocks.forEachIndexed { i, block ->
                block.translation = (translations?.get(i.toString()) as? JsonPrimitive)?.contentOrNull ?: block.text
            }
            page.blocks = page.blocks.filterNot { it.translation.contains(WATERMARK_PLACEHOLDER) }.toMutableList()
        }
    }

    /** Returns the names of the models this API key can use, to pick one from. */
    abstract suspend fun models(): List<String>

    /** Returns the model's reply to [userText], which should be a JSON object. */
    protected abstract suspend fun complete(
        systemPrompt: String,
        userText: String,
        onRetry: (attempt: Int) -> Unit,
    ): String

    /** Sends [request], retrying rate limits and overloads, and returns the response's JSON body. */
    protected suspend fun send(
        request: Request,
        onRetry: (attempt: Int) -> Unit,
    ): JsonObject = withContext(Dispatchers.IO) {
        var attempt = 0
        var response = client.newCall(request).await()
        // Rate limits and "model overloaded" errors usually clear within seconds
        while (response.code in RETRY_CODES && attempt < RETRY_DELAYS.size) {
            response.close()
            onRetry(attempt + 1)
            delay(RETRY_DELAYS[attempt++])
            response = client.newCall(request).await()
        }
        val body = response.use { it.body.string() }
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (!response.isSuccessful || json == null) {
            val error = json?.get("error") as? JsonObject
            throw ApiException(
                message = "$name HTTP ${response.code}: " +
                    (error?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(200)),
                code = (error?.get("code") as? JsonPrimitive)?.contentOrNull,
            )
        }
        json
    }

    /** An error response, with the API's own error [code] when it gives one. */
    class ApiException(message: String, val code: String?) : IOException(message)

    companion object {
        /** Returns the translator for [service]. [customBaseUrl] is only used by [TranslationService.CUSTOM]. */
        fun create(service: TranslationService, apiKey: String, model: String, customBaseUrl: String): TextTranslator {
            val baseUrl = when (service) {
                TranslationService.GEMINI -> return GeminiTranslator(apiKey = apiKey, model = model)
                TranslationService.CUSTOM -> customBaseUrl
                else -> service.baseUrl!!
            }
            return OpenAiCompatibleTranslator(baseUrl = baseUrl, apiKey = apiKey, model = model)
        }

        private const val WATERMARK_PLACEHOLDER = "RTMTH"

        private val RETRY_CODES = setOf(429, 500, 502, 503)
        private val RETRY_DELAYS = listOf(15.seconds, 30.seconds, 60.seconds)

        private val SYSTEM_PROMPT = """
            ## System Prompt for Manhwa/Manga/Manhua Translation

            You are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua) while preserving the original structure and removing any watermarks or site links.

            **Here's how you should operate:**

            1. **Input:** You'll receive a JSON object where keys are image filenames (e.g., "001.jpg") and values are objects mapping a block number to the text extracted from one speech bubble or caption.

            2. **Translation:** Translate every block to the target language `Brazilian Portuguese`. Ensure the translation is natural and fluent, adapting idioms and expressions to fit the target language's cultural context.

            3. **One block, one translation:** The text was read by OCR, so a sentence may be split across consecutive blocks. Translate each block on its own, keeping each part of the sentence in its own block. Never merge blocks or move text from one block to another. Fix obvious OCR mistakes using the context.

            4. **Watermark and noise removal:** Replace any watermarks or site links (e.g., "colamanga.com"), and any block that isn't readable text (OCR noise from the artwork), with the placeholder "$WATERMARK_PLACEHOLDER".

            5. **Structure Preservation:** Return the same image filenames and, for each, exactly the same block numbers as the input.

            **Example:**

            **Input:**

            ```json
            {"001.jpg":{"0":"IF IT","1":"ISN'T MISS VAMOLA!","2":"COULD I GET YOUR HELP?"},"002.jpg":{"0":"english3","1":"colamanga.com","2":"Rp aouariun"}}
            ```

            **Output:**

            ```json
            {"001.jpg":{"0":"OLHA SÓ","1":"SE NÃO É A SRTA. VAMOLA!","2":"PODERIA ME AJUDAR?"},"002.jpg":{"0":"portuguese3","1":"$WATERMARK_PLACEHOLDER","2":"$WATERMARK_PLACEHOLDER"}}
            ```

            Return {[filename:string]:{[block:string]:string}}
        """.trimIndent()
    }
}
