package eu.kanade.translation.recognizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BubbleTextTest {

    private var line = 0

    /** Words of one line, each 8 px per letter and 6 px apart, starting at [x] or after a [gap] from the last one. */
    private fun line(y: Float, vararg words: Pair<String, Float>): List<Word> {
        val number = line++
        var x = 0f
        return words.map { (text, gap) ->
            x += gap
            Word(text, x, y, text.length * 8f, 13f, 13f, number, 0f).also { x += it.width }
        }
    }

    @Test
    fun `a line running across touching bubbles is cut between them`() {
        val words = line(0f, "KINDS" to 100f, "I" to 50f) +
            line(16f, "TORTURE" to 0f, "|" to 6f, "VIRUSES..." to 6f, "SUBJECT!" to 20f) +
            line(32f, "HER" to 10f)
        assertEquals(
            listOf("KINDS\nVIRUSES...", "I\nSUBJECT!", "TORTURE\nHER"),
            bubbleBlocks(words).map { it.text },
        )
    }

    @Test
    fun `words of a bubble stay together and lines are joined`() {
        val words = line(0f, "HOW" to 0f, "TRULY" to 6f) + line(16f, "MARVEL-" to 4f) + line(32f, "OUS!" to 14f)
        assertEquals(listOf("HOW TRULY\nMARVEL-\nOUS!"), bubbleBlocks(words).map { it.text })
    }

    @Test
    fun `marks without letters are dropped`() {
        assertEquals(emptyList<String>(), bubbleBlocks(line(0f, "--" to 0f)).map { it.text })
    }

    @Test
    fun `numbers and lone strokes from the art are dropped, a lone I is kept`() {
        val words = line(0f, "260" to 0f) + line(100f, "K" to 0f) + line(200f, "I" to 0f)
        assertEquals(listOf("I"), bubbleBlocks(words).map { it.text })
    }

    @Test
    fun `a hyphen or punctuation read apart stays with its word`() {
        val words = line(0f, "MARVEL" to 0f, "-" to 20f) + line(16f, "OUS" to 0f, "?!" to 20f)
        assertEquals(listOf("MARVEL-\nOUS?!"), bubbleBlocks(words).map { it.text })
    }

}
