// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.translator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.TargetLanguage
import java.io.IOException
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * Translates with the Gemini REST API.
 *
 * The REST API is called directly because Google's Android SDK for it is deprecated.
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String,
    language: TargetLanguage,
) : LanguageModelTranslator(language) {

    override val name = "Gemini"

    override val readsImages = true

    override suspend fun complete(
        systemPrompt: String,
        userText: String,
        images: Map<String, ByteArray>,
        onRetry: (attempt: Int) -> Unit,
        schema: JsonObject?,
    ): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", userText) }
                        images.forEach { (name, image) ->
                            addJsonObject { put("text", name) }
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", Base64.getEncoder().encodeToString(image))
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                if (schema != null) put("responseSchema", schema)
            }
            putJsonArray("safetySettings") {
                SAFETY_CATEGORIES.forEach { category ->
                    addJsonObject {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("$BASE_URL/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // A batch normally answers in 2-3 s, but now and then a request hangs for half a minute or more, holding up the
        // chapter, and asking again is quicker
        val json = send(request, onRetry, STALLED_AFTER)

        val candidate = (json["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw IOException("Gemini returned no candidates: ${json["promptFeedback"]}")
        if (candidate["finishReason"]?.jsonPrimitive?.contentOrNull == "MAX_TOKENS") throw TruncatedReplyException(name)
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        return parts
            .map { it.jsonObject }
            .filterNot { it["thought"]?.jsonPrimitive?.booleanOrNull == true }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .ifEmpty { throw IOException("Gemini returned no text, finish reason ${candidate["finishReason"]}") }
    }

    override suspend fun models(): List<String> {
        val request = Request.Builder()
            .url("$BASE_URL?pageSize=1000")
            .header("x-goog-api-key", apiKey)
            .build()
        return (send(request, onRetry = {})["models"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { model ->
                (model["supportedGenerationMethods"] as? JsonArray).orEmpty()
                    .any { it.jsonPrimitive.contentOrNull == "generateContent" }
            }
            .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        val STALLED_AFTER = 15.seconds

        // Comics often trip the default filters, which would drop whole pages
        val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )
    }
}
