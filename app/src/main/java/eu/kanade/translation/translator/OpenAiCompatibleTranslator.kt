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
        val json = try {
            send(request(systemPrompt, userText, jsonMode = true), onRetry)
        } catch (e: ApiException) {
            // Groq rejects the whole reply when the model's JSON has a flaw, which translate() can often parse anyway
            if (e.code != "json_validate_failed") throw e
            send(request(systemPrompt, userText, jsonMode = false), onRetry)
        }

        val choice = (json["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw IOException("$name returned no choices")
        if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length") {
            throw IOException("$name cut the reply short: the model's output limit is too small for this chapter")
        }
        return choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.ifEmpty { null }
            ?: throw IOException("$name returned no text, finish reason ${choice["finish_reason"]}")
    }

    override suspend fun models(): List<String> {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .header("Authorization", "Bearer $apiKey")
            .build()
        return (send(request) {}["data"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .sorted()
    }

    private fun request(systemPrompt: String, userText: String, jsonMode: Boolean): Request {
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
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
}
