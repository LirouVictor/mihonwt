package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.TargetLanguage

class CorrectTextTest {

    private class Recorder(override val readsImages: Boolean) : LanguageModelTranslator(TargetLanguage.PT_BR) {
        override val name = "test"
        var prompt = ""
        var images = emptyMap<String, ByteArray>()

        override suspend fun models() = emptyList<String>()

        override suspend fun complete(
            systemPrompt: String,
            userText: String,
            images: Map<String, ByteArray>,
            onRetry: (attempt: Int) -> Unit,
            schema: JsonObject?,
        ): String {
            prompt = systemPrompt
            this.images = images
            return """{"pages":[{"page":"001.jpg","blocks":[{"n":0,"text":"I NEED IT"}]}]}"""
        }
    }

    private val pages = mapOf(
        "001.jpg" to PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    "I NEEO IT",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 5f,
                    symWidth = 5f,
                    angle = 0f,
                ),
            ),
        ),
    )
    private val images = mapOf("001.jpg" to byteArrayOf(1))

    @Test
    fun `reviews from the page images when the model reads them`() = runBlocking {
        val model = Recorder(readsImages = true)
        assertEquals(mapOf("001.jpg" to mapOf(0 to "I NEED IT")), model.correctText(pages, "Series", images) {})
        assertEquals(images.keys, model.images.keys)
        assertTrue(model.prompt.contains("page images"))
    }

    @Test
    fun `reviews from the text alone without images`() = runBlocking {
        val model = Recorder(readsImages = true)
        assertEquals(mapOf("001.jpg" to mapOf(0 to "I NEED IT")), model.correctText(pages, "Series", emptyMap()) {})
        assertFalse(model.prompt.contains("page images"))
    }

    @Test
    fun `sends no images to a model that can't read them`() = runBlocking {
        val model = Recorder(readsImages = false)
        model.correctText(pages, "Series", images) {}
        assertTrue(model.images.isEmpty())
        assertFalse(model.prompt.contains("page images"))
    }
}
