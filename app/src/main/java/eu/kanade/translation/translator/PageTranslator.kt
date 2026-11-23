// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.translation.TargetLanguage
import tachiyomi.domain.translation.TranslationService
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Translates the text blocks found on a chapter's pages. */
abstract class PageTranslator {

    /** Shown in error messages and in the step times of a chapter. */
    abstract val name: String

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
     * [before] is the page read just before them, whose last words a sentence on the first page may go on from.
     */
    abstract suspend fun translate(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray> = emptyMap(),
        glossary: Map<String, String> = emptyMap(),
        onRetry: (attempt: Int) -> Unit = {},
        before: PageTranslation? = null,
    ): Map<String, String>

    /**
     * Sends [request], retrying rate limits and overloads, and returns the response's JSON body. Throws
     * [QuotaExceededException] when the service's daily or monthly quota is used up, or its rate limit outlasts the
     * retries. A request still unanswered after [stalledAfter] is sent again once.
     */
    protected suspend fun send(
        request: Request,
        onRetry: (attempt: Int) -> Unit,
        stalledAfter: Duration? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        var attempt = 0
        var reply = call(request, stalledAfter)
        // Rate limits and "model overloaded" errors usually clear within seconds, a used up daily quota doesn't
        while (reply.code in RETRY_CODES && attempt < RETRY_DELAYS.size) {
            if (reply.code == 429 && DAILY_LIMIT.containsMatchIn(reply.body)) throw QuotaExceededException(name)
            onRetry(attempt + 1)
            // Retrying before the time the service asks for only fails again
            delay(reply.retryAfter?.coerceAtMost(MAX_ASKED_DELAY) ?: RETRY_DELAYS[attempt])
            attempt++
            reply = call(request, stalledAfter)
        }
        if (reply.code == 429 || reply.code == DEEPL_QUOTA_EXCEEDED) throw QuotaExceededException(name)
        val json = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
        if (reply.code !in 200..299 || json == null) {
            // DeepL puts the message at the top level rather than in an error object
            val error = json?.get("error") as? JsonObject ?: json
            throw ApiException(
                message = "$name HTTP ${reply.code}: " +
                    ((error?.get("message") as? JsonPrimitive)?.contentOrNull ?: reply.body.take(200)),
                code = (error?.get("code") as? JsonPrimitive)?.contentOrNull,
                status = reply.code,
            )
        }
        json
    }

    // Only the request is timed, not the waits between retries, which would otherwise be cut short and start over
    private suspend fun call(request: Request, stalledAfter: Duration?): Reply =
        stalledAfter?.let { withTimeoutOrNull(it) { execute(request) } } ?: execute(request)

    private suspend fun execute(request: Request): Reply = client.newCall(request).await().use { response ->
        val body = response.body.string()
        val asked = response.header("Retry-After")?.toDoubleOrNull()
            ?: RETRY_DELAY_IN_BODY.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        Reply(response.code, body, asked?.seconds)
    }

    private class Reply(val code: Int, val body: String, val retryAfter: Duration?)

    /** An error response with HTTP [status], and the API's own error [code] when it gives one. */
    class ApiException(message: String, val code: String?, val status: Int) : IOException(message)

    /** The [service] won't take more requests for now, so the user should switch to another one. */
    class QuotaExceededException(val service: String) : IOException("$service quota exceeded")

    companion object {
        /** Returns the translator for [service]. [customBaseUrl] is only used by [TranslationService.CUSTOM]. */
        fun create(
            service: TranslationService,
            apiKey: String,
            model: String,
            customBaseUrl: String,
            language: TargetLanguage,
        ): PageTranslator {
            return when (service) {
                TranslationService.GEMINI -> GeminiTranslator(apiKey = apiKey, model = model, language = language)
                TranslationService.DEEPL -> DeepLTranslator(apiKey = apiKey, language = language)
                TranslationService.OFFLINE -> OfflineTranslator(language)
                TranslationService.CUSTOM -> OpenAiCompatibleTranslator(customBaseUrl, apiKey, model, language)
                TranslationService.GROQ, TranslationService.OPENROUTER, TranslationService.CEREBRAS ->
                    OpenAiCompatibleTranslator(service.baseUrl!!, apiKey, model, language)
            }
        }

        private val RETRY_CODES = setOf(429, 500, 502, 503)
        private val RETRY_DELAYS = listOf(15.seconds, 30.seconds, 60.seconds)
        private val MAX_ASKED_DELAY = 60.seconds

        // Gemini says when to retry in its error details, as "retryDelay": "37s"
        private val RETRY_DELAY_IN_BODY = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"")

        // Gemini names the quota, as in "GenerateRequestsPerDayPerProjectPerModel-FreeTier", and OpenAI-compatible
        // APIs like Groq say "tokens per day" in the message
        private val DAILY_LIMIT = Regex("""PerDay|per day""", RegexOption.IGNORE_CASE)

        // DeepL's own status for a used up monthly character allowance
        private const val DEEPL_QUOTA_EXCEEDED = 456
    }
}
