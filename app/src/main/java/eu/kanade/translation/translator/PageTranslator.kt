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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.translation.TranslationService
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Translates the text blocks found on a chapter's pages. */
abstract class PageTranslator {

    /** Shown in error messages. */
    protected abstract val name: String

    // Not NetworkHelper's client: its verbose logging would print the API key header, and its read timeout is too
    // short for a whole chapter.
    private val client = OkHttpClient.Builder()
        .readTimeout(3.minutes)
        .callTimeout(5.minutes)
        .build()

    /** Whether [translate] uses the page images, so they are worth preparing. */
    open val readsImages = false

    /**
     * Fills [eu.kanade.translation.model.TranslationBlock.translation] of every block on [pages], keyed by page name,
     * and returns how the names and recurring terms on these pages were translated, when the translator can tell.
     */
    abstract suspend fun translate(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray> = emptyMap(),
        glossary: Map<String, String> = emptyMap(),
        onRetry: (attempt: Int) -> Unit = {},
    ): Map<String, String>

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
            // DeepL puts the message at the top level rather than in an error object
            val error = json?.get("error") as? JsonObject ?: json
            throw ApiException(
                message = "$name HTTP ${response.code}: " +
                    ((error?.get("message") as? JsonPrimitive)?.contentOrNull ?: body.take(200)),
                code = (error?.get("code") as? JsonPrimitive)?.contentOrNull,
            )
        }
        json
    }

    /** An error response, with the API's own error [code] when it gives one. */
    class ApiException(message: String, val code: String?) : IOException(message)

    companion object {
        /** Returns the translator for [service]. [customBaseUrl] is only used by [TranslationService.CUSTOM]. */
        fun create(service: TranslationService, apiKey: String, model: String, customBaseUrl: String): PageTranslator {
            return when (service) {
                TranslationService.GEMINI -> GeminiTranslator(apiKey = apiKey, model = model)
                TranslationService.DEEPL -> DeepLTranslator(apiKey = apiKey)
                TranslationService.CUSTOM -> OpenAiCompatibleTranslator(customBaseUrl, apiKey, model)
                TranslationService.GROQ, TranslationService.OPENROUTER, TranslationService.CEREBRAS ->
                    OpenAiCompatibleTranslator(service.baseUrl!!, apiKey, model)
            }
        }

        private val RETRY_CODES = setOf(429, 500, 502, 503)
        private val RETRY_DELAYS = listOf(15.seconds, 30.seconds, 60.seconds)
    }
}
