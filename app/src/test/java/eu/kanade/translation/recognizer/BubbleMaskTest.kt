package eu.kanade.translation.recognizer

import eu.kanade.translation.model.TranslationBlock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

class BubbleMaskTest {

    private val width = 400
    private val height = 300

    /** An ellipse outlined in black around 200, 150 with radii 120 and 80, its inside white. */
    private fun page(): Array<BooleanArray> = Array(height) { y ->
        BooleanArray(width) { x -> abs(hypot((x - 200) / 120.0, (y - 150) / 80.0) - 1) * 100 > 2.5 }
    }

    private fun Array<BooleanArray>.dark(left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top..<bottom) for (x in left..<right) this[y][x] = false
    }

    private fun Array<BooleanArray>.pixels() = LightPixels { left, top, right, bottom ->
        BooleanArray((right - left) * (bottom - top)) { this[top + it / (right - left)][left + it % (right - left)] }
    }

    private fun block(x: Float, y: Float, width: Float, height: Float) = TranslationBlock(
        text = "SEE.",
        x = x,
        y = y,
        width = width,
        height = height,
        symWidth = 10f,
        symHeight = 16f,
        angle = 0f,
    )

    private fun TranslationBlock.covers(px: Float, py: Float) =
        bubble.any { px >= it.x && px < it.x + it.width && py >= it.y && py < it.y + it.height }

    @Test
    fun `covers the bubble with its stray marks and stays inside the outline`() {
        val page = page()
        page.dark(160, 142, 240, 158)
        // An "I" OCR missed, on the line above
        page.dark(196, 118, 204, 134)
        val text = block(160f, 142f, 80f, 16f)

        val placed = text.inBubble(width, height, listOf(text), page.pixels())

        assertNotNull(placed)
        placed!!
        assertTrue(placed.covers(200f, 126f))
        assertTrue(placed.covers(200f, 150f))
        assertTrue(placed.covers(110f, 150f))
        assertFalse(placed.covers(60f, 150f))
        assertFalse(placed.covers(200f, 60f))
        for ((cx, cy) in listOf(placed.x to placed.y, placed.x + placed.width to placed.y + placed.height)) {
            assertTrue(hypot((cx - 200.0) / 120, (cy - 150.0) / 80) < 1)
        }
        assertTrue(placed.width * placed.height > text.width * text.height)
    }

    @Test
    fun `finds nothing around text outside a closed bubble`() {
        val page = Array(height) { BooleanArray(width) { true } }
        page.dark(160, 142, 240, 158)
        val text = block(160f, 142f, 80f, 16f)

        assertNull(text.inBubble(width, height, listOf(text), page.pixels()))
    }

    @Test
    fun `splits a bubble shared by two blocks between them`() {
        val page = page()
        page.dark(110, 120, 170, 136)
        page.dark(230, 164, 290, 180)
        val first = block(110f, 120f, 60f, 16f)
        val second = block(230f, 164f, 60f, 16f)
        val blocks = listOf(first, second)

        val a = first.inBubble(width, height, blocks, page.pixels())!!
        val b = second.inBubble(width, height, blocks, page.pixels())!!

        val overlapX = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
        val overlapY = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
        assertTrue(overlapX <= 0 || overlapY <= 0)
    }
}
