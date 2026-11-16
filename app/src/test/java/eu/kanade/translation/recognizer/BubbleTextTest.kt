package eu.kanade.translation.recognizer

import eu.kanade.translation.model.FoundText
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

    @Test
    fun `a bubble outline read as a tall letter is left out of the word`() {
        val letters = listOf(Letter("J", 0f, -10f, 4f, 40f)) +
            "DONT".mapIndexed { i, c -> Letter(c.toString(), 6f + i * 8f, 0f, 8f, 13f) }
        val word = wordOf(letters, 0, 0f)!!
        assertEquals("DONT", word.text)
        assertEquals(6f, word.x)
        assertEquals(13f, word.letterHeight)
    }

    @Test
    fun `a line is cut where a bubble outline runs between two words`() {
        val outlineX = 86
        val pixels = LightPixels { left, top, right, bottom ->
            BooleanArray((right - left) * (bottom - top)) { left + it % (right - left) != outlineX }
        }
        val words = line(0f, "VIRUSES..." to 0f, "SUBJECT!" to 12f) + line(100f, "OF" to 0f, "US" to 6f)
        assertEquals(listOf("VIRUSES...", "SUBJECT!", "OF US"), bubbleBlocks(words, pixels).map { it.text })
    }

    @Test
    fun `found text is laid over the OCR words in its box, and words outside every box are dropped`() {
        val words =
            line(0f, "DONT" to 0f, "GOT" to 40f) + line(16f, "WAN-" to 0f, "IT!" to 40f) + line(200f, "260" to 0f)
        val found = listOf(
            FoundText(0f, 0f, 30f, 28f, "DON'T WAN-NA!", "Não quero!"),
            FoundText(74f, 2f, 100f, 30f, "GOT IT!", "Entendi!"),
            FoundText(300f, 300f, 330f, 340f, "WHAT?!", "Quê?!"),
        )
        val blocks = blocksOfFoundText(found, words)
        assertEquals(listOf("Não quero!", "Entendi!", "Quê?!"), blocks.map { it.translation })
        // From the words: DONT and WAN- span 0..32 across, 0..29 down
        assertEquals(listOf(0f, 0f, 32f, 29f), blocks[0].let { listOf(it.x, it.y, it.width, it.height) })
        // Without words, the box itself with the page's letter height
        assertEquals(
            listOf(300f, 300f, 30f, 40f, 13f),
            blocks[2].let {
                listOf(it.x, it.y, it.width, it.height, it.symHeight)
            },
        )
    }

    @Test
    fun `detections keep text boxes sure enough and drop whole bubbles`() {
        val boxes = textBoxes(
            labels = longArrayOf(0, 1, 1, 2, 2),
            boxes = Array(5) { floatArrayOf(0f, 0f, 10f, 10f) },
            scores = floatArrayOf(0.9f, 0.9f, 0.2f, 0.4f, 0.6f),
        )
        assertEquals(2, boxes.size)
    }

    @Test
    fun `words are grouped by the detected box around them, in lines`() {
        val words = line(0f, "DONT" to 0f, "GOT" to 40f) + line(16f, "WAN-" to 0f, "IT!" to 40f) +
            line(200f, "BOOM" to 0f)
        val boxes = listOf(TextBox(0f, 0f, 32f, 29f), TextBox(72f, 0f, 96f, 29f), TextBox(0f, -5f, 100f, 40f))
        assertEquals(listOf("DONT\nWAN-", "GOT\nIT!"), blocksInBoxes(boxes, words).map { it.text })
    }

    @Test
    fun `an outline read as a huge letter joins no box`() {
        val words = line(0f, "WRONG." to 0f) + line(100f, "GOT" to 0f, "YOU" to 6f) +
            Word("o", 60f, -150f, 90f, 280f, 280f, line++, 0f)
        val boxes = listOf(TextBox(0f, 0f, 48f, 13f), TextBox(0f, 100f, 60f, 113f))
        val blocks = blocksInBoxes(boxes, words)
        assertEquals(listOf("WRONG.", "GOT YOU"), blocks.map { it.text })
        assertEquals(listOf(13f, 13f), blocks.map { it.symHeight })
    }
}
