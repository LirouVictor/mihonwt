package eu.kanade.translation.recognizer

import eu.kanade.translation.model.Area
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageSeamsTest {

    private fun block(
        text: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        letter: Float,
        bubble: Area? = null,
    ) = TranslationBlock(
        text = text,
        x = x,
        y = y,
        width = width,
        height = height,
        symWidth = letter,
        symHeight = letter,
        angle = 0f,
        bubble = listOfNotNull(bubble),
    )

    private fun piece(vararg blocks: TranslationBlock) =
        PageTranslation(blocks = blocks.toMutableList(), imgWidth = 800f, imgHeight = 3840f)

    // Surviving the Apocalypse ch. 1, pages 11 and 12: "HE'S A SICK, / SICK MAN." cut through its second line
    @Test
    fun `a caption cut through a line is joined`() {
        val upper = block("HE'S A SICK,", 33f, 3701f, 731f, 96f, 67.5f)
        val lower = block("SILA IVIAIN,", 70f, 0f, 657f, 117f, 40.7f)
        assertEquals(listOf(upper to lower), blocksAcrossSeam(piece(upper), piece(lower)))
    }

    // Pages 28 and 29: "THIS / UNDERGROUND BUNKER IS..." in a bubble cut by the edge, its lines a little further apart
    @Test
    fun `a bubble running into the edge is joined across a wider gap`() {
        val upper = block("THIS", 201f, 3694f, 250f, 115f, 25.5f)
        val lower = block("BUNKER IS THE ONLY", 175f, 19f, 314f, 110f, 28f, Area(150f, 0f, 360f, 150f))
        assertEquals(listOf(upper to lower), blocksAcrossSeam(piece(upper), piece(lower)))
    }

    // Pages 27 and 28: a bubble ending 81 px above the edge and a sound effect at the top of the next piece
    @Test
    fun `texts more than a line apart stay apart`() {
        val upper = block("HEH,\nPATHETIC.", 180f, 3609f, 199f, 150f, 41f, Area(150f, 3550f, 260f, 244f))
        val lower = block("QuVER", 272f, 0f, 199f, 77f, 72f)
        assertEquals(emptyList<Any>(), blocksAcrossSeam(piece(upper), piece(lower)))
    }

    @Test
    fun `texts side by side across the edge stay apart`() {
        val upper = block("QilVER", 630f, 3459f + 300f, 149f, 70f, 40f)
        val lower = block("LIAR...", 337f, 10f, 215f, 115f, 32.5f)
        assertEquals(emptyList<Any>(), blocksAcrossSeam(piece(upper), piece(lower)))
    }

    @Test
    fun `only strip pieces are joined`() {
        assertTrue(isStripPiece(800, 3840))
        assertFalse(isStripPiece(1100, 1600))
        assertFalse(isStripPiece(2200, 1600))
    }

    @Test
    fun `a translation is split by the share of letters on each side`() {
        assertEquals("ELE É UM HOMEM" to "DOENTE, DOENTE.", splitTranslation("ELE É UM HOMEM DOENTE, DOENTE.", 0.5f))
        assertEquals("ESTE" to "BUNKER É A ÚNICA SAÍDA.", splitTranslation("ESTE BUNKER É A ÚNICA SAÍDA.", 0.1f))
        assertEquals("SIM!" to "", splitTranslation("SIM!", 0.5f))
        assertEquals(0.25f, letterShare("THIS", "UNDERGROUNDS"))
    }
}
