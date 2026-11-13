package eu.kanade.translation.translator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Translates with any API that follows OpenAI's chat completions, such as Groq, OpenRouter or DeepSeek. */
class OpenAiCompatibleTranslator(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : TextTranslator() {

    override val name = baseUrl.toHttpUrlOrNull()?.host ?: baseUrl

    override suspend fun complete(systemPrompt: String, userText: String, onRetry: (attempt: Int) -> Unit): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userText)
                }
            }
            putJsonObject("response_format") { put("type", "json_object") }
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val json = send(request, onRetry)

        val choice = (json["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw IOException("$name returned no choices")
        return choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.ifEmpty { null }
            ?: throw IOException("$name returned no text, finish reason ${choice["finish_reason"]}")
    }
}
