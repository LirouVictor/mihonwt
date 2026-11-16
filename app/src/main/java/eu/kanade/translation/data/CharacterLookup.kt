package eu.kanade.translation.data

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Looks up a series' characters on AniList, or on MyAnimeList through Jikan when AniList doesn't know it. Both APIs
 * are free and need no key.
 */
class CharacterLookup(private val client: OkHttpClient) {

    /** Returns the names of the main characters of the comic titled [title], with the names they also go by. */
    suspend fun characterNames(title: String): List<String> = aniList(title).ifEmpty { jikan(title) }

    private suspend fun aniList(title: String): List<String> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("query", QUERY)
            putJsonObject("variables") { put("search", title) }
        }
        val request = Request.Builder()
            .url(URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        characterNamesOf(fetch(request))
    }

    private suspend fun jikan(title: String): List<String> = withContext(Dispatchers.IO) {
        val search = "$JIKAN_URL/manga".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .addQueryParameter("limit", "1")
            .build()
        val id = ((fetch(Request.Builder().url(search).build())["data"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("mal_id")?.jsonPrimitive?.longOrNull
            ?: return@withContext emptyList()
        jikanNamesOf(fetch(Request.Builder().url("$JIKAN_URL/manga/$id/characters").build()))
    }

    private suspend fun fetch(request: Request): JsonObject {
        val response = client.newCall(request).await()
        val text = response.use { it.body.string() }
        if (!response.isSuccessful) throw IOException("${request.url.host} HTTP ${response.code}: ${text.take(200)}")
        return Json.parseToJsonElement(text) as JsonObject
    }

    companion object {
        private const val URL = "https://graphql.anilist.co"
        private const val JIKAN_URL = "https://api.jikan.moe/v4"
        private const val MAX_CHARACTERS = 50
        private const val QUERY = """
            query (${'$'}search: String) {
              Media(search: ${'$'}search, type: MANGA) {
                characters(sort: [ROLE, RELEVANCE, ID], perPage: $MAX_CHARACTERS) {
                  nodes { name { full alternative } }
                }
              }
            }
        """

        /** Returns the names in an AniList reply to [QUERY]. */
        internal fun characterNamesOf(reply: JsonObject): List<String> {
            val media = (reply["data"] as? JsonObject)?.get("Media") as? JsonObject ?: return emptyList()
            val nodes = ((media["characters"] as? JsonObject)?.get("nodes") as? JsonArray).orEmpty()
            return nodes.flatMap { node ->
                val name = (node as? JsonObject)?.get("name") as? JsonObject ?: return@flatMap emptyList()
                val full = (name["full"] as? JsonPrimitive)?.contentOrNull
                // Other names are often nicknames in English, as "Straw Hat", or in other languages, as "Shanks den
                // røde", which a translation shouldn't keep, so only one-word names like "Okarun" are taken
                val alternative = (name["alternative"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.replace(IN_BRACKETS, "")?.trim() }
                    .filter { ONE_WORD.matches(it) }
                listOfNotNull(full) + alternative
            }.cleaned()
        }

        /**
         * Returns the names in a Jikan reply listing a comic's characters: the main ones and then the best liked,
         * turned from MyAnimeList's "Roronoa, Zoro" into "Zoro Roronoa".
         */
        internal fun jikanNamesOf(reply: JsonObject): List<String> =
            (reply["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .sortedWith(
                    compareByDescending<JsonObject> { it["role"]?.jsonPrimitive?.contentOrNull == "Main" }
                        .thenByDescending { it["favorites"]?.jsonPrimitive?.longOrNull ?: 0 },
                )
                .take(MAX_CHARACTERS)
                .mapNotNull { (it["character"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                .map { name -> name.split(", ", limit = 2).reversed().joinToString(" ") }
                .cleaned()

        // Names come with their Japanese spelling, as "Okarun (オカルン)" or "ジジ", which English text never has
        private fun List<String>.cleaned(): List<String> = map { it.replace(IN_BRACKETS, "").trim() }
            .filter { name -> name.any { it.isLetter() && Character.UnicodeScript.of(it.code) == LATIN } }
            .distinct()

        private val IN_BRACKETS = Regex("""\s*[(（][^)）]*[)）]""")
        private val LATIN = Character.UnicodeScript.LATIN
        private val ONE_WORD = Regex("""[A-Za-z]+""")
    }
}
