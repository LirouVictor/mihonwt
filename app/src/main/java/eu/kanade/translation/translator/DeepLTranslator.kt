package eu.kanade.translation.translator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Translates with DeepL's API. */
class DeepLTranslator(private val apiKey: String) : BlockTranslator() {

    override val name = "DeepL"

    // Keys of the free plan end in ":fx" and only work on its own address
    private val baseUrl = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"

    override suspend fun translatePage(texts: List<String>, onRetry: (attempt: Int) -> Unit): List<String> {
        return texts.chunked(MAX_TEXTS).flatMap { chunk ->
            val body = buildJsonObject {
                putJsonArray("text") { chunk.forEach { add(it) } }
                put("source_lang", "EN")
                put("target_lang", "PT-BR")
                put("formality", "prefer_less")
                // Each block is otherwise translated on its own. Context isn't billed
                put("context", texts.joinToString("\n"))
            }
            val request = Request.Builder()
                .url("$baseUrl/v2/translate")
                .header("Authorization", "DeepL-Auth-Key $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val translations = (send(request, onRetry)["translations"] as? JsonArray).orEmpty()
                .map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            if (translations.size !=
                chunk.size
            ) {
                throw IOException("$name returned ${translations.size} of ${chunk.size}")
            }
            translations
        }
    }

    private companion object {
        const val MAX_TEXTS = 50
    }
}
