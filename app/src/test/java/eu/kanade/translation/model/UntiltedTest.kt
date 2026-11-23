package eu.kanade.translation.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UntiltedTest {
    private fun block(width: Float, height: Float, angle: Float) = TranslationBlock(
        text = "GRAB",
        width = width,
        height = height,
        x = 0f,
        y = 0f,
        symHeight = 114.8f,
        symWidth = 114.8f,
        angle = angle,
    )

    @Test
    fun `tilted text is measured along its tilt`() {
        // One Piece 1199 p.8: "GRAB" lettered at -19°
        val size = block(181.5f, 154f, -19f).untilted()

        assertEquals(154f, size.width, 1f)
        assertEquals(110f, size.height, 1f)
        assertEquals(90f, size.letterHeight, 1f)
    }

    @Test
    fun `upright and sideways text keeps its box`() {
        listOf(0f, 5f, 90f, -88f).forEach { angle ->
            assertEquals(UntiltedSize(181.5f, 154f, 114.8f), block(181.5f, 154f, angle).untilted())
        }
    }
}
