package eu.kanade.translation.recognizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PaddleOcrTest {

    /** Probabilities of [classes] classes over steps, where each step is sure of the class given for it. */
    private fun steps(classes: Int, vararg best: Int, probability: Float = 0.9f): FloatArray {
        val probabilities = FloatArray(best.size * classes) { (1 - probability) / (classes - 1) }
        best.forEachIndexed { step, c -> probabilities[step * classes + c] = probability }
        return probabilities
    }

    @Test
    fun `repeats are merged and blanks dropped, a blank between them keeps a double letter`() {
        val decoded = decodeCtc(steps(5, 0, 1, 1, 0, 2, 0, 2, 4, 3, 3), 10, 5)!!
        assertEquals(listOf(1, 2, 2, 4, 3), decoded.indices.toList())
        assertEquals(listOf(1, 4, 6, 7, 8), decoded.starts.toList())
        // Each letter runs until the next starts, and the last one to its own end
        assertEquals(listOf(4, 6, 7, 8, 10), decoded.ends.toList())
    }

    @Test
    fun `a line the recognizer is unsure of is dropped`() {
        assertNull(decodeCtc(steps(5, 1, 2, probability = 0.4f), 2, 5))
        assertNull(decodeCtc(steps(5, 0, 0), 2, 5))
    }

    @Test
    fun `pages are read at a multiple of 32 with the short side at least 736 and the long one at most 1280`() {
        assertEquals(768 to 1152, detectorInputSize(784, 1145))
        assertEquals(800 to 1280, detectorInputSize(1200, 1885))
        assertEquals(736 to 1088, detectorInputSize(400, 590))
        assertEquals(832 to 1280, detectorInputSize(1280, 2000))
        assertEquals(640 to 1280, detectorInputSize(1000, 2048))
        // A caption cropped from a page is enlarged only as far as its length allows
        assertEquals(1280 to 64, detectorInputSize(1000, 47))
    }

    @Test
    fun `each patch of text is a line grown back to its letters and more for the crop`() {
        val width = 40
        val height = 20
        val probabilities = FloatArray(width * height)
        // A line of 20 by 4 cells at 10, 8, and a speck too small to be text
        for (y in 8..<12) for (x in 10..<30) probabilities[y * width + x] = 0.9f
        probabilities[2 * width + 2] = 0.9f
        val lines = textLines(probabilities, width, height, pageWidth = 80, pageHeight = 40)
        assertEquals(1, lines.size)
        // The 2 by 2 dilation adds a cell on the right and below: a patch of 21 by 5 at 10, 8
        fun grown(distance: Float) = Box(
            (10 - distance) * 2,
            (8 - distance) * 2,
            (31 + distance) * 2,
            (13 + distance) * 2,
        )
        assertEquals(grown(21 * 5 * 1.6f / (2 * (21 + 5))), lines[0].crop)
        assertEquals(grown(21 * 5 * 0.5f / (2 * (21 + 5))), lines[0].bounds)
    }

    @Test
    fun `a patch the detector is unsure of on average is not a line`() {
        val width = 40
        val height = 20
        val probabilities = FloatArray(width * height)
        for (y in 8..<12) for (x in 10..<30) probabilities[y * width + x] = 0.35f
        assertEquals(0, textLines(probabilities, width, height, 40, 20).size)
    }
}
