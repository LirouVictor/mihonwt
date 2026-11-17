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

    // The quality model and custom instructions may not be offered on every plan, so a request they make fail is sent
    // again without them, and later ones leave them out
    private var withExtras = true

    override suspend fun translatePage(
        texts: List<String>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): List<String> {
        val terms = glossaryPattern(glossary.keys)
        val translations = glossary.mapKeys { it.key.lowercase() }
        return texts.chunked(MAX_TEXTS).flatMap { chunk ->
            val sent = chunk.map { it.withTermsKept(terms, translations) }
            val replies = requestWithExtrasFallback(sent, texts, onRetry).toMutableList()
            // DeepL drops everything after a kept name that starts the text and is followed by "'s", as in
            // "<keep>Yuno</keep>'S NOT THE ONLY ONE...", so such a text is sent again without kept names
            val truncated = replies.indices.filter { replies[it].lostTextAfterKept(sent[it]) }
            if (truncated.isNotEmpty()) {
                val again = requestWithExtrasFallback(truncated.map { chunk[it].escapeXml() }, texts, onRetry)
                truncated.zip(again).forEach { (i, reply) -> replies[i] = reply }
            }
            replies.map { it.fromXml() }
        }
    }

    /** Returns DeepL's XML replies to the XML [chunk], one for each text. */
    private suspend fun requestWithExtrasFallback(
        chunk: List<String>,
        texts: List<String>,
        onRetry: (attempt: Int) -> Unit,
    ): List<String> {
        val replies = try {
            request(chunk, texts, onRetry)
        } catch (e: ApiException) {
            if (!withExtras || e.status !in REJECTED_CODES) throw e
            withExtras = false
            request(chunk, texts, onRetry)
        }
        if (replies.size != chunk.size) throw IOException("$name returned ${replies.size} of ${chunk.size}")
        return replies
    }

    private suspend fun request(
        chunk: List<String>,
        texts: List<String>,
        onRetry: (attempt: Int) -> Unit,
    ): List<String> {
        val body = buildJsonObject {
            putJsonArray("text") { chunk.forEach { add(it) } }
            put("source_lang", "EN")
            put("target_lang", "PT-BR")
            put("formality", "prefer_less")
            // Each block is otherwise translated on its own. Context isn't billed
            put("context", texts.joinToString("\n"))
            put("tag_handling", "xml")
            putJsonArray("ignore_tags") { add(KEEP_TAG) }
            if (withExtras) {
                put("model_type", "quality_optimized")
                putJsonArray("custom_instructions") { CUSTOM_INSTRUCTIONS.forEach { add(it) } }
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/v2/translate")
            .header("Authorization", "DeepL-Auth-Key $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return (send(request, onRetry)["translations"] as? JsonArray).orEmpty()
            .map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private companion object {
        const val MAX_TEXTS = 50
        val REJECTED_CODES = setOf(400, 403)

        val CUSTOM_INSTRUCTIONS = listOf(
            "This is manga dialogue: use natural, colloquial Brazilian Portuguese as people speak it today.",
            "Use Brazilian spelling and vocabulary, never European Portuguese forms.",
            "Keep character names, nicknames, titles of techniques and honorifics such as -chan or -san untranslated.",
        )
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

/**
 * Returns DeepL's XML reply as plain text. DeepL sometimes contracts the "de" before a kept name into a bare "d", as
 * in "guarda d<keep>Vlad</keep>" for "Vlad's guard", drops the space before it, or tacks letters onto its end, as in
 * "<keep>Vamola</keep>e, você tá bem?!".
 */
internal fun String.fromXml(): String = replace(CONTRACTED_DE, "de ")
    .replace(GLUED_TO_KEPT, " ")
    .replace(GLUED_AFTER_KEPT, "")
    .replace(TAG, "")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

/** Whether this XML reply to the XML text [sent] is only its kept names, while [sent] had words besides them. */
internal fun String.lostTextAfterKept(sent: String): Boolean {
    fun String.hasWordsOutsideKept() = replace(KEPT, "").any(Char::isLetter)
    return KEPT in sent && sent.hasWordsOutsideKept() && !hasWordsOutsideKept()
}

private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val KEEP_TAG = "keep"
private val TAG = Regex("</?$KEEP_TAG>")
private val KEPT = Regex("<$KEEP_TAG>.*?</$KEEP_TAG>")
private val CONTRACTED_DE = Regex("""\bd(?=<$KEEP_TAG>)""")
private val GLUED_TO_KEPT = Regex("""(?<=\p{L})(?=<$KEEP_TAG>)""")
private val GLUED_AFTER_KEPT = Regex("""(?<=</$KEEP_TAG>)\p{L}+""")
