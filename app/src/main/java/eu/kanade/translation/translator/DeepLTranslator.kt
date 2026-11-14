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

    override suspend fun translatePage(
        texts: List<String>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): List<String> {
        val terms = glossaryPattern(glossary.keys)
        val translations = glossary.mapKeys { it.key.lowercase() }
        return texts.chunked(MAX_TEXTS).flatMap { chunk ->
            val body = buildJsonObject {
                putJsonArray("text") { chunk.forEach { add(it.withTermsKept(terms, translations)) } }
                put("source_lang", "EN")
                put("target_lang", "PT-BR")
                put("formality", "prefer_less")
                // Each block is otherwise translated on its own. Context isn't billed
                put("context", texts.joinToString("\n"))
                put("tag_handling", "xml")
                putJsonArray("ignore_tags") { add(KEEP_TAG) }
            }
            val request = Request.Builder()
                .url("$baseUrl/v2/translate")
                .header("Authorization", "DeepL-Auth-Key $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val replies = (send(request, onRetry)["translations"] as? JsonArray).orEmpty()
                .map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty().fromXml() }
            if (replies.size != chunk.size) throw IOException("$name returned ${replies.size} of ${chunk.size}")
            replies
        }
    }

    private companion object {
        const val MAX_TEXTS = 50
    }
}

/** Matches any of [terms] as whole words, longest first so a name isn't cut short by a shorter one inside it. */
internal fun glossaryPattern(terms: Collection<String>): Regex? {
    val words = terms.filter { it.isNotBlank() }.sortedByDescending { it.length }
    if (words.isEmpty()) return null
    return Regex(words.joinToString("|", """\b(?:""", """)\b""") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
}

/**
 * Returns this text as XML for DeepL, with each glossary term replaced by its translation in a tag DeepL leaves
 * untouched, so names stay as the series' glossary has them. [translations] are keyed by lowercase term.
 */
internal fun String.withTermsKept(terms: Regex?, translations: Map<String, String>): String {
    if (terms == null) return escapeXml()
    val result = StringBuilder()
    var end = 0
    terms.findAll(this).forEach { match ->
        val translation = translations[match.value.lowercase()] ?: return@forEach
        result.append(substring(end, match.range.first).escapeXml())
        result.append("<$KEEP_TAG>").append(translation.escapeXml()).append("</$KEEP_TAG>")
        end = match.range.last + 1
    }
    return result.append(substring(end).escapeXml()).toString()
}

/** Returns DeepL's XML reply as plain text. */
internal fun String.fromXml(): String = replace(TAG, "")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val KEEP_TAG = "keep"
private val TAG = Regex("</?$KEEP_TAG>")
