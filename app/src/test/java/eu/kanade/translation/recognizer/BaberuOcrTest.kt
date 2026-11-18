package eu.kanade.translation.recognizer

import eu.kanade.translation.model.TranslationBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaberuOcrTest {

    @Test
    fun `a block alone in a box is reread`() {
        val blocks = listOf(block(10f, 10f, 40f, 40f), block(200f, 10f, 40f, 40f))
        val boxes = listOf(TextBox(0f, 0f, 60f, 60f), TextBox(190f, 0f, 250f, 60f))
        assertEquals(mapOf(0 to boxes[0], 1 to boxes[1]), boxesToReread(blocks, boxes))
    }

    @Test
    fun `blocks sharing a box, outside boxes or in wide captions are not`() {
        val shared = listOf(block(10f, 10f, 20f, 20f), block(30f, 30f, 20f, 20f))
        assertEquals(emptyMap<Int, TextBox>(), boxesToReread(shared, listOf(TextBox(0f, 0f, 60f, 60f))))
        val outside = listOf(block(100f, 100f, 20f, 20f))
        assertEquals(emptyMap<Int, TextBox>(), boxesToReread(outside, listOf(TextBox(0f, 0f, 60f, 60f))))
        val caption = listOf(block(0f, 0f, 400f, 20f))
        assertEquals(emptyMap<Int, TextBox>(), boxesToReread(caption, listOf(TextBox(0f, 0f, 400f, 20f))))
    }

    private fun block(x: Float, y: Float, width: Float, height: Float) = TranslationBlock(
        text = "TEXT",
        width = width,
        height = height,
        x = x,
        y = y,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )
}
