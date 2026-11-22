package eu.kanade.translation.recognizer

import eu.kanade.translation.model.Area
import eu.kanade.translation.model.TranslationBlock
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `covers letters touching the outline`() {
        val page = page()
        page.dark(82, 142, 160, 158)
        page.dark(170, 142, 240, 158)
        val text = block(82f, 142f, 158f, 16f)

        val placed = text.inBubble(width, height, listOf(text), page.pixels())

        assertNotNull(placed)
        assertTrue(placed!!.covers(100f, 150f))
        assertFalse(placed.covers(60f, 150f))
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

    @Test
    fun `splits a bubble between two blocks whose boxes overlap`() {
        val page = page()
        // A note beside the text, one of whose lines OCR read across both
        page.dark(110, 110, 170, 190)
        page.dark(150, 120, 290, 180)
        val note = block(110f, 110f, 60f, 80f)
        val text = block(150f, 120f, 140f, 60f)
        val blocks = listOf(note, text)

        val a = note.inBubble(width, height, blocks, page.pixels())!!
        val b = text.inBubble(width, height, blocks, page.pixels())!!

        val overlapX = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
        val overlapY = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
        assertTrue(overlapX <= 0 || overlapY <= 0)
    }

    @Test
    fun `finds a bubble cut by the page's edge, but not the page's margin`() {
        // The ellipse moved up so the top of the page cuts it, as One Piece 1199 p.7
        val page = Array(height) { y ->
            BooleanArray(width) { x -> abs(hypot((x - 200) / 120.0, (y - 40) / 80.0) - 1) * 100 > 2.5 }
        }
        val text = block(140f, 20f, 120f, 40f)
        page.dark(140, 20, 260, 30)
        page.dark(140, 50, 260, 60)

        val placed = text.inBubble(width, height, listOf(text), page.pixels())

        assertNotNull(placed)
        assertTrue(placed!!.covers(200f, 2f))
        assertFalse(placed.covers(200f, 200f))

        // A blank page has light space along all its edges
        val blank = Array(height) { BooleanArray(width) { true } }
        blank.dark(140, 20, 260, 30)
        blank.dark(140, 50, 260, 60)
        assertNull(text.inBubble(width, height, listOf(text), blank.pixels()))
    }

    @Test
    fun `joins blocks placed over each other`() {
        val note = block(110f, 110f, 60f, 80f).copy(text = "(NOTE)")
        val text = block(100f, 100f, 140f, 100f).copy(text = "THIS BODY")
        val apart = block(300f, 100f, 40f, 20f).copy(text = "HEY")

        val joined = listOf(note, text, apart).withOverlapsJoined()

        assertEquals(listOf("(NOTE)\nTHIS BODY", "HEY"), joined.map { it.text })
        assertEquals(140f, joined[0].width)
    }

    @Test
    fun `blocks joined outside bubbles take both boxes`() {
        // One Piece 1199 p.7: "HOW ELSE CAN WE EXPLAIN IT?" beside a bubble's text on the same light art
        val left = block(800f, 70f, 120f, 110f).copy(text = "HOW ELSE")
        val right = block(870f, 40f, 160f, 140f).copy(text = "BUT HE LOOKS LIKE HIM")

        val joined = listOf(right, left).withOverlapsJoined().single()

        assertEquals(800f, joined.x)
        assertEquals(40f, joined.y)
        assertEquals(230f, joined.width)
        assertEquals(140f, joined.height)
    }

    @Test
    fun `letters outside a bubble joined to one are painted over`() {
        val note = block(100f, 100f, 60f, 30f).copy(text = "(NOTE)")
        val text = block(100f, 100f, 140f, 100f).copy(text = "THIS BODY", bubble = listOf(Area(90f, 90f, 160f, 120f)))

        val joined = listOf(text, note).withOverlapsJoined().single()

        assertEquals(listOf(Area(90f, 90f, 160f, 120f), Area(100f, 100f, 60f, 30f)), joined.bubble)
    }

    @Test
    fun `blocks are read in rows, right to left for manga`() {
        fun block(text: String, x: Float, y: Float, width: Float, height: Float) =
            block(x, y, width, height).copy(text = text)
        val blocks = listOf(
            block("NORMAL LIFE", 40f, 615f, 110f, 135f),
            block("SAVE MY HEART?", 40f, 100f, 110f, 120f),
            block("I'LL BE A CRIMINAL.", 530f, 650f, 90f, 105f),
            block("BUT IF I KILL", 640f, 595f, 80f, 73f),
            block("WHAT DO I DO", 600f, 95f, 100f, 60f),
            block("I WOULDN'T BE ABLE", 230f, 595f, 110f, 120f),
        )

        assertEquals(
            listOf(
                "WHAT DO I DO",
                "SAVE MY HEART?",
                "BUT IF I KILL",
                "I'LL BE A CRIMINAL.",
                "I WOULDN'T BE ABLE",
                "NORMAL LIFE",
            ),
            blocks.inReadingOrder(rightToLeft = true).map { it.text },
        )
        assertEquals("SAVE MY HEART?", blocks.inReadingOrder(rightToLeft = false).first().text)
    }
}
