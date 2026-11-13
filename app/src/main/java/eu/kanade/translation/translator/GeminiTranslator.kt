// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

/**
 * Translates every page of a chapter in a single request to the Gemini REST API.
 *
 * The REST API is called directly because Google's Android SDK for it is deprecated.
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String,
) {

    // Not NetworkHelper's client: its verbose logging would print the API key header, and its read timeout is too
    // short for a whole chapter.
    private val client = OkHttpClient.Builder()
        .readTimeout(3.minutes)
        .callTimeout(5.minutes)
        .build()

    /** Fills [eu.kanade.translation.model.TranslationBlock.translation] and drops blocks marked as watermarks. */
    suspend fun translate(pages: Map<String, PageTranslation>) {
        val input = buildJsonObject {
            pages.forEach { (name, page) ->
                putJsonArray(name) { page.blocks.forEach { add(JsonPrimitive(it.text)) } }
            }
        }
        val output = Json.parseToJsonElement(generate(input.toString())).jsonObject

        pages.forEach { (name, page) ->
            val translations = output[name] as? JsonArray
            page.blocks.forEachIndexed { i, block ->
                block.translation = translations?.getOrNull(i)?.jsonPrimitive?.contentOrNull ?: block.text
            }
            page.blocks = page.blocks.filterNot { it.translation.contains(WATERMARK_PLACEHOLDER) }.toMutableList()
        }
    }

    private suspend fun generate(userText: String): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", SYSTEM_PROMPT) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", userText) } }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
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

        val response = client.newCall(request).await()
        val json = response.use { Json.parseToJsonElement(it.body.string()).jsonObject }
        if (!response.isSuccessful) {
            val message = json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw IOException("Gemini HTTP ${response.code}: $message")
        }

        val candidate = (json["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw IOException("Gemini returned no candidates: ${json["promptFeedback"]}")
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        return parts
            .map { it.jsonObject }
            .filterNot { it["thought"]?.jsonPrimitive?.booleanOrNull == true }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .ifEmpty { throw IOException("Gemini returned no text, finish reason ${candidate["finishReason"]}") }
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        const val WATERMARK_PLACEHOLDER = "RTMTH"

        // Comics often trip the default filters, which would drop whole pages
        val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )

        val SYSTEM_PROMPT = """
            ## System Prompt for Manhwa/Manga/Manhua Translation

            You are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua) while preserving the original structure and removing any watermarks or site links.

            **Here's how you should operate:**

            1. **Input:** You'll receive a JSON object where keys are image filenames (e.g., "001.jpg") and values are lists of text strings extracted from those images.

            2. **Translation:** Translate all text strings to the target language `Brazilian Portuguese`. Ensure the translation is natural and fluent, adapting idioms and expressions to fit the target language's cultural context.

            3. **Watermark/Site Link Removal:** Replace any watermarks or site links (e.g., "colamanga.com") with the placeholder "$WATERMARK_PLACEHOLDER".

            4. **Structure Preservation:** Maintain the exact same structure as the input JSON. The output JSON should have the same number of keys (image filenames) and the same number of text strings within each list.

            **Example:**

            **Input:**

            ```json
            {"001.jpg":["english1","english2"],"002.jpg":["english3","colamanga.com"]}
            ```

            **Output:**

            ```json
            {"001.jpg":["portuguese1","portuguese2"],"002.jpg":["portuguese3","$WATERMARK_PLACEHOLDER"]}
            ```

            **Key Points:**

            * Prioritize accurate and natural-sounding translations.
            * Be meticulous in removing all watermarks and site links.
            * Ensure the output JSON structure perfectly mirrors the input structure.
            Return {[key:string]:Array<String>}
        """.trimIndent()
    }
}
