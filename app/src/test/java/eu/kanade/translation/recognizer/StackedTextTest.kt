package eu.kanade.translation.recognizer

import eu.kanade.translation.model.Area
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StackedTextTest {

    @Test
    fun `letters stacked down a narrow box are found one per row, a dot joined to its mark`() {
        // "W", "H", a gap, "A", then "?" whose dot sits just under it
        val marks = listOf(
            Area(10f, 10f, 20f, 20f),
            Area(10f, 36f, 20f, 20f),
            Area(10f, 82f, 20f, 20f),
            Area(15f, 108f, 10f, 14f),
            Area(17f, 125f, 6f, 5f),
        )
        val letters = stackedLetters(TextBox(5f, 5f, 35f, 135f), pixels(marks))!!
        assertEquals(
            listOf(
                Area(10f, 10f, 20f, 20f),
                Area(10f, 36f, 20f, 20f),
                Area(10f, 82f, 20f, 20f),
                Area(15f, 108f, 10f, 22f),
            ),
            letters,
        )
        assertFalse(stackedWordBreakBefore(letters, 1))
        assertTrue(stackedWordBreakBefore(letters, 2))
    }

    @Test
    fun `lines of text and wide boxes are not stacks`() {
        val lines = listOf(Area(5f, 10f, 70f, 20f), Area(5f, 40f, 70f, 20f), Area(5f, 70f, 70f, 20f))
        assertNull(stackedLetters(TextBox(0f, 0f, 80f, 200f), pixels(lines)))
        val letters = listOf(Area(10f, 10f, 20f, 20f), Area(10f, 40f, 20f, 20f), Area(10f, 70f, 20f, 20f))
        assertNull(stackedLetters(TextBox(0f, 0f, 60f, 100f), pixels(letters)))
    }

    private fun pixels(dark: List<Area>) = LightPixels { left, top, right, bottom ->
        BooleanArray((right - left) * (bottom - top)) { i ->
            val x = left + i % (right - left)
            val y = top + i / (right - left)
            dark.none { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
        }
    }
}
