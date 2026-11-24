// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.translator

import eu.kanade.translation.model.FoundText
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.joinLines
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tachiyomi.domain.translation.TargetLanguage
import java.io.IOException

/** Translates every page of a chapter in a single request to a language model, into [language]. */
abstract class LanguageModelTranslator(private val language: TargetLanguage) : PageTranslator() {

    /**
     * Also drops blocks the model marks as watermarks. The [series] title lets the model use the names and tone it
     * knows from it, [images] of the pages let it read the text in context, and [glossary] keeps the terms of earlier
     * chapters translated the same way.
     */
    override suspend fun translate(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
        before: PageTranslation?,
    ): Map<String, String> {
        return try {
            translateBatch(pages, series, images, glossary, onRetry)
        } catch (e: TruncatedReplyException) {
            // A whole chapter can exceed the model's output limit, above all when its reasoning counts against it
            if (pages.size < 2) throw e
            pages.entries.chunked((pages.size + 1) / 2).fold(emptyMap()) { terms, half ->
                terms + translate(half.associate { it.key to it.value }, series, images, glossary, onRetry)
            }
        }
    }

    private suspend fun translateBatch(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): Map<String, String> {
        // Blocks are numbered rather than listed: with a list, the model sometimes merges two fragments of a sentence
        // and every later block on the page gets the translation of the one after it. Their text goes without line
        // breaks, which the model would copy into translations that need longer lines.
        val input = buildJsonObject {
            pages.forEach { (name, page) ->
                putJsonObject(name) {
                    page.blocks.forEachIndexed { i, block -> put(i.toString(), block.text.joinLines()) }
                }
            }
        }
        val systemPrompt = systemPrompt(translationPrompt(language), series, glossary)
        val pageImages = images.filterKeys { it in pages }
        var output = request(systemPrompt, input.toString(), pageImages, onRetry)
        // Models now and then skip a block, which would stay in English; a second answer is usually complete
        if (countMissing(pages, output) > 0) {
            val retry = request(systemPrompt, input.toString(), pageImages, onRetry)
            if (countMissing(pages, retry) < countMissing(pages, output)) output = retry
        }

        pages.forEach { (name, page) ->
            val translations = output[name] as? JsonObject
            page.blocks.forEachIndexed { i, block ->
                block.translation = (translations?.get(i.toString()) as? JsonPrimitive)?.contentOrNull ?: block.text
            }
            page.blocks = page.blocks.filterNot { it.translation.contains(WATERMARK_PLACEHOLDER) }.toMutableList()
        }
        return output.glossary()
    }

    /**
     * Finds the speech bubbles, captions and narration on the [images] of [pages] and translates them, leaving out
     * text that is part of the artwork, like sound effects and signs, which OCR can't tell apart from lettering. The
     * pages' OCR text helps the model read small letters. Returns what it found on each page it answered for, and
     * the terms for the glossary.
     */
    suspend fun findText(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): Pair<Map<String, List<FoundText>>, Map<String, String>> {
        val pageImages = images.filterKeys { it in pages }
        val input = buildJsonObject {
            pageImages.keys.forEach { name ->
                putJsonArray(name) { pages.getValue(name).blocks.forEach { add(it.text.joinLines()) } }
            }
        }
        val prompt = systemPrompt(findTextPrompt(language), series, glossary)
        val output = try {
            try {
                request(prompt, input.toString(), pageImages, onRetry, FIND_TEXT_SCHEMA)
            } catch (e: InvalidReplyException) {
                // Long replies of nested boxes now and then come out malformed even in JSON mode
                request(prompt, input.toString(), pageImages, onRetry, FIND_TEXT_SCHEMA)
            }
        } catch (e: TruncatedReplyException) {
            if (pageImages.size < 2) throw e
            val halves = pageImages.keys.chunked((pageImages.size + 1) / 2).map { names ->
                findText(pages.filterKeys { it in names }, series, images, glossary, onRetry)
            }
            return halves.fold(emptyMap<String, List<FoundText>>()) { found, half -> found + half.first } to
                halves.fold(emptyMap()) { terms, half -> terms + half.second }
        }
        val found = (output["pages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { entry ->
            val name = (entry["page"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in pageImages }
                ?: return@mapNotNull null
            val page = pages.getValue(name)
            val texts = entry["texts"] as? JsonArray ?: return@mapNotNull null
            name to texts.mapNotNull { (it as? JsonObject)?.toFoundText(page.imgWidth, page.imgHeight) }
        }.toMap()
        return found to output.terms()
    }

    /**
     * Returns the English text of each block of [pages] as the model reads it on their [images], or as context tells
     * without them, fixing what OCR misread, for a translator that can't see the pages: by page name, then by block
     * index. Blocks the model didn't answer for are left out.
     */
    suspend fun correctText(
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        onRetry: (attempt: Int) -> Unit,
    ): Map<String, Map<Int, String>> {
        val pageImages = images.filterKeys { it in pages }.takeIf { readsImages }.orEmpty()
        val input = buildJsonObject {
            pages.forEach { (name, page) ->
                putJsonObject(name) {
                    page.blocks.forEachIndexed { i, block -> put(i.toString(), block.text.joinLines()) }
                }
            }
        }
        val prompt = systemPrompt(
            if (pageImages.isEmpty()) CORRECT_TEXT_ONLY_PROMPT else CORRECT_TEXT_PROMPT,
            series,
            emptyMap(),
        )
        val output = try {
            request(prompt, input.toString(), pageImages, onRetry, CORRECT_TEXT_SCHEMA)
        } catch (e: TruncatedReplyException) {
            if (pages.size < 2) throw e
            return pages.entries.chunked((pages.size + 1) / 2).fold(emptyMap()) { corrected, half ->
                corrected + correctText(half.associate { it.key to it.value }, series, images, onRetry)
            }
        }
        return (output["pages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { entry ->
            val name = (entry["page"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in pages }
                ?: return@mapNotNull null
            val blocks = (entry["blocks"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull {
                val index = (it["n"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val text = (it["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                index to text
            }
            name to blocks.toMap()
        }.toMap()
    }

    /**
     * Returns the names and recurring terms of [series] the model knows, as characters, places, groups and attacks,
     * with how translations usually give them, or none when it doesn't know the series.
     */
    suspend fun seriesTerms(series: String): Map<String, String> =
        request(seriesTermsPrompt(language), series, emptyMap(), {}, SERIES_TERMS_SCHEMA).terms()

    private fun JsonObject.terms(): Map<String, String> =
        (this["glossary"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { entry ->
            val term = (entry["term"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val translation = (entry["translation"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            term to translation
        }.toMap()

    private fun JsonObject.toFoundText(width: Float, height: Float): FoundText? {
        val box = (this["box_2d"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
            ?.takeIf { it.size == 4 } ?: return null
        val (top, left, bottom, right) = box.map { it.coerceIn(0f, BOX_SCALE) / BOX_SCALE }
        if (bottom <= top || right <= left) return null
        return FoundText(
            left = left * width,
            top = top * height,
            right = right * width,
            bottom = bottom * height,
            text = (this["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            translation = (this["translation"] as? JsonPrimitive)?.contentOrNull ?: return null,
        )
    }

    private fun JsonObject.glossary(): Map<String, String> = (this[GLOSSARY_KEY] as? JsonObject).orEmpty()
        .mapNotNull { (term, translation) -> (translation as? JsonPrimitive)?.contentOrNull?.let { term to it } }
        .toMap()

    private fun systemPrompt(prompt: String, series: String, glossary: Map<String, String>) = buildString {
        append(prompt)
        append("\n\nThe pages are from \"$series\".")
        if (glossary.isNotEmpty()) {
            append("\n\nEarlier chapters translated these names and terms like this; keep them: ")
            append(JsonObject(glossary.mapValues { JsonPrimitive(it.value) }))
        }
    }

    private suspend fun request(
        systemPrompt: String,
        input: String,
        images: Map<String, ByteArray>,
        onRetry: (attempt: Int) -> Unit,
        schema: JsonObject? = null,
    ): JsonObject {
        val reply = complete(systemPrompt, input, images, onRetry, schema)
        return parseReply(reply) ?: throw InvalidReplyException(name, reply)
    }

    private fun countMissing(pages: Map<String, PageTranslation>, output: JsonObject): Int {
        return pages.entries.sumOf { (name, page) ->
            val translations = output[name] as? JsonObject
            page.blocks.indices.count { (translations?.get(it.toString()) as? JsonPrimitive)?.contentOrNull == null }
        }
    }

    /** Returns the names of the models this API key can use, to pick one from. */
    abstract suspend fun models(): List<String>

    /**
     * Returns the model's reply to [userText], which should be a JSON object, with [images] if it [readsImages], and
     * following [schema], in Gemini's form, if the API can enforce one.
     */
    protected abstract suspend fun complete(
        systemPrompt: String,
        userText: String,
        images: Map<String, ByteArray>,
        onRetry: (attempt: Int) -> Unit,
        schema: JsonObject? = null,
    ): String

    /** Thrown when the reply isn't a JSON object. */
    class InvalidReplyException(name: String, reply: String) :
        IOException(
            "$name returned invalid JSON (${reply.length} characters): " +
                if (reply.length <= 400) reply else "${reply.take(200)} … ${reply.takeLast(200)}",
        )

    /** Thrown when the reply hit the model's output limit, so a smaller batch may fit. */
    class TruncatedReplyException(name: String) :
        IOException("$name cut the reply short: the model's output limit is too small even for one page")

    private companion object {
        private const val WATERMARK_PLACEHOLDER = "RTMTH"

        // Page names are file names, which never start with an underscore
        private const val GLOSSARY_KEY = "_glossary"

        // Gemini gives boxes as [ymin, xmin, ymax, xmax] scaled to 0-1000, the form it was trained to detect in
        private const val BOX_SCALE = 1000f

        private val FIND_TEXT_SCHEMA = Json.parseToJsonElement(
            """
            {"type":"OBJECT","required":["pages","glossary"],"properties":{
              "pages":{"type":"ARRAY","items":{"type":"OBJECT","required":["page","texts"],"properties":{
                "page":{"type":"STRING"},
                "texts":{"type":"ARRAY","items":{"type":"OBJECT","required":["box_2d","text","translation"],
                  "propertyOrdering":["box_2d","text","translation"],"properties":{
                  "box_2d":{"type":"ARRAY","items":{"type":"INTEGER"}},
                  "text":{"type":"STRING"},
                  "translation":{"type":"STRING"}}}}}}},
              "glossary":{"type":"ARRAY","items":{"type":"OBJECT","required":["term","translation"],"properties":{
                "term":{"type":"STRING"},
                "translation":{"type":"STRING"}}}}}}
            """,
        ).jsonObject

        private val CORRECT_TEXT_SCHEMA = Json.parseToJsonElement(
            """
            {"type":"OBJECT","required":["pages"],"properties":{
              "pages":{"type":"ARRAY","items":{"type":"OBJECT","required":["page","blocks"],"properties":{
                "page":{"type":"STRING"},
                "blocks":{"type":"ARRAY","items":{"type":"OBJECT","required":["n","text"],"properties":{
                  "n":{"type":"INTEGER"},
                  "text":{"type":"STRING"}}}}}}}}}
            """,
        ).jsonObject

        private val CORRECT_TEXT_PROMPT = """
            You fix OCR mistakes in comic lettering. You'll receive page images, each after its file name, and a JSON object mapping each file name to numbered blocks of the English text that OCR read on that page; each block is one speech bubble or caption.

            For each block, return its text as it is actually lettered on the page image: fix misread letters (D read for P, 0 for O, 2 for Z or ?), words run together or split, lines OCR skipped inside the same bubble, and punctuation. Keep the text in English and in the same case as lettered; never translate, rephrase, or move text between blocks. If a block is not lettering (sound effects drawn in the art, signs, noise), return it unchanged.

            Under "pages", return an entry per page with its file name as "page" and, under "blocks", each block's number as "n" and its corrected "text".
        """.trimIndent()

        private val CORRECT_TEXT_ONLY_PROMPT = """
            You fix OCR mistakes in comic lettering. You'll receive a JSON object mapping each page's file name to numbered blocks of the English text that OCR read on that page; each block is one speech bubble or caption.

            For each block, return its text as it is most likely lettered, judging from the context, the series and how English reads: fix misread letters (D read for P, 0 for O, 2 for Z or ?), words run together or split, and punctuation. Keep the text in English and in the same case as lettered; never translate, rephrase, or move text between blocks. If a block is not lettering (sound effects, signs, noise) or you can't tell what it says, return it unchanged.

            Under "pages", return an entry per page with its file name as "page" and, under "blocks", each block's number as "n" and its corrected "text".
        """.trimIndent()

        private val SERIES_TERMS_SCHEMA = Json.parseToJsonElement(
            """
            {"type":"OBJECT","required":["glossary"],"properties":{
              "glossary":{"type":"ARRAY","items":{"type":"OBJECT","required":["term","translation"],"properties":{
                "term":{"type":"STRING"},
                "translation":{"type":"STRING"}}}}}}
            """,
        ).jsonObject

        private fun seriesTermsPrompt(language: TargetLanguage) = """
            You help translate a comic from English into ${language.englishName}. The user gives its title. Under "glossary", list the names and recurring terms of this series that a translation must keep consistent: its main and recurring characters, by their full names and by the short names they are usually called, and its places, groups, ships, powers and attacks.
            Give each "term" as English releases write it, and its "translation" as ${language.englishName} releases usually give it; names usually stay as they are.
            List at most 150, the most important first. If you don't know this series, return an empty list rather than guessing.
        """.trimIndent()

        private fun findTextPrompt(language: TargetLanguage) = """
            You translate comics (manga, manhwa, manhua) from their page images.

            You'll receive page images, each after its file name, and a JSON object mapping each file name to the text that OCR read on that page. OCR misreads letters, and splits or joins text across bubbles, so use its text only to help you read small letters; the image is what counts.

            On each page, find every speech bubble, thought bubble, caption box and narration box, and translate its text to `${language.englishName}`, as a good fan translator would today: natural, colloquial speech that suits each character, with current everyday words. Avoid formal, old-fashioned or overly literal wording and false cognates.${language.styleNotesText} Keep the names of characters, places, attacks and powers as in the original, unless the series has a well-known ${language.englishName} name for them, and keep honorifics such as "-san".

            Leave out text that is part of the artwork rather than lettering for the reader: sound effects, signs, labels, prices, writing on clothes or objects, and also watermarks, site links and credits. Text written vertically one letter per line is still a bubble's text.

            Give each bubble or box once, with all of its text, even when its lines are far apart. Two bubbles that touch are two entries.

            Under "pages", return an entry for each page with its file name as "page" and, under "texts", each bubble or box with "box_2d": the box around its letters as [ymin, xmin, ymax, xmax], scaled from 0 to 1000 over the page image; "text": its original text; and "translation".

            Under "glossary", return each name and recurring term on these pages (characters, places, attacks, powers, invented words) as "term", with how you translated it as "translation".
        """.trimIndent()

        private fun translationPrompt(language: TargetLanguage) = """
            ## System Prompt for Manhwa/Manga/Manhua Translation

            You are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua) while preserving the original structure and removing any watermarks or site links.

            **Here's how you should operate:**

            1. **Input:** You'll receive a JSON object where keys are image filenames (e.g., "001.jpg") and values are objects mapping a block number to the text extracted from one speech bubble or caption.

            2. **Translation:** Translate every block to the target language `${language.englishName}`, as a good fan translator would today: natural, colloquial speech that suits each character, with current everyday words. Avoid formal, old-fashioned or overly literal wording and false cognates.${language.styleNotesText} Adapt idioms and expressions to fit the target language's cultural context.

            3. **Names:** Keep the names of characters, places, attacks and powers as in the original (e.g. "Acrobatic Silky" stays "Acrobatic Silky"), unless the series has a well-known ${language.englishName} name for them. Keep honorifics such as "-san" and "-sama".

            4. **One block, one translation:** The text was read by OCR, so a sentence may be split across consecutive blocks. Translate each block on its own, keeping each part of the sentence in its own block. Never merge blocks or move text from one block to another. Fix obvious OCR mistakes using the context.

            5. **Watermark and noise removal:** Replace any watermarks or site links (e.g., "colamanga.com"), and any block that isn't readable text (OCR noise from the artwork), with the placeholder "$WATERMARK_PLACEHOLDER".

            6. **Structure Preservation:** Return the same image filenames and, for each, exactly the same block numbers as the input.

            7. **Page images:** When an image of a page follows the text, use it to fix OCR mistakes and to tell who is speaking and in what tone. Still translate only the given blocks.

            8. **Glossary:** Also return, under the key "$GLOSSARY_KEY", an object mapping each name and recurring term on these pages (characters, places, attacks, powers, invented words) to how you translated it.

            **Example:**

            **Input:**

            ```json
            {"001.jpg":{"0":"IF IT","1":"ISN'T MISS VAMOLA!","2":"COULD I GET YOUR HELP?"},"002.jpg":{"0":"english3","1":"colamanga.com","2":"Rp aouariun"}}
            ```

            **Output:**

            ```json
            {"001.jpg":{"0":"${language.example[0]}","1":"${language.example[1]}","2":"${language.example[2]}"},"002.jpg":{"0":"${language.example[3]}","1":"$WATERMARK_PLACEHOLDER","2":"$WATERMARK_PLACEHOLDER"},"$GLOSSARY_KEY":{"Vamola":"Vamola"}}
            ```

            Return {[filename:string]:{[block:string]:string}, "$GLOSSARY_KEY":{[term:string]:string}}
        """.trimIndent()
    }
}

/**
 * Returns the JSON object in a model's [reply], or null if there is none. Without a JSON mode, models may wrap it in a
 * code block or a sentence, or give the pages as an array of objects, which are merged.
 */
internal fun parseReply(reply: String): JsonObject? {
    val start = reply.indexOfFirst { it == '{' || it == '[' }.takeIf { it >= 0 } ?: return null
    val end = reply.indexOfLast { it == '}' || it == ']' }.takeIf { it > start } ?: return null
    val json = reply.substring(start, end + 1)
    val element = runCatching { Json.parseToJsonElement(json) }
        // Objects one after the other, as when the model left out the array's brackets
        .recoverCatching { Json.parseToJsonElement("[$json]") }
        .getOrNull() ?: return null
    return when (element) {
        is JsonObject -> element
        is JsonArray -> element.filterIsInstance<JsonObject>().takeIf { it.isNotEmpty() }?.let(::merged)
        else -> null
    }
}

private fun merged(objects: List<JsonObject>): JsonObject = JsonObject(
    objects.fold(emptyMap()) { merged, next ->
        merged + next.mapValues { (key, value) ->
            val earlier = merged[key]
            if (earlier is JsonObject && value is JsonObject) JsonObject(earlier + value) else value
        }
    },
)
