package eu.kanade.translation.translator

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ParseReplyTest {

    private val pages = Json.parseToJsonElement("""{"011.webp":{"0":"UAU!!"},"012.webp":{"0":"OLÁ"}}""")

    @Test
    fun `object in a code block`() {
        assertEquals(pages, parseReply("Here:\n```json\n$pages\n```"))
    }

    @Test
    fun `array of one object`() {
        assertEquals(pages, parseReply("[\n$pages\n]"))
    }

    @Test
    fun `array of an object per page`() {
        assertEquals(pages, parseReply("""[{"011.webp":{"0":"UAU!!"}},{"012.webp":{"0":"OLÁ"}}]"""))
    }

    @Test
    fun `objects without brackets`() {
        assertEquals(pages, parseReply("""{"011.webp":{"0":"UAU!!"}}, {"012.webp":{"0":"OLÁ"}}"""))
    }

    @Test
    fun `blocks of one page split over objects`() {
        assertEquals(
            Json.parseToJsonElement("""{"011.webp":{"0":"A","1":"B"}}"""),
            parseReply("""[{"011.webp":{"0":"A"}},{"011.webp":{"1":"B"}}]"""),
        )
    }

    @Test
    fun `cut reply`() {
        assertNull(parseReply("""[{"011.webp":{"0":"UAU!!"},"012.webp":{"0":"O"""))
    }
}
