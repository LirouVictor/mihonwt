package eu.kanade.translation.recognizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextCorrectionTest {

    @Test
    fun `fixed misreadings and a missed line are corrections`() {
        assertTrue(
            isCorrectionOf(
                "JUST DUT THEm on THE SHID, AND IS DOne!! HA",
                "JUST PUT THEM ON THE SHIP, AND YOUR JOB IS DONE!!",
            ),
        )
        assertTrue(isCorrectionOf("WHO IS THAT2!!", "WHO IS THAT?!!"))
    }

    @Test
    fun `corrections given in another order are matched to their block`() {
        val page = listOf("HEY!\nLOOK!", "LOOK\nAT 'IM!", "IT'S\nTAKA-\nKURA!")
        assertEquals("IT'S\nTAKA-\nKURA!", correctionOf("IT'S TAKA KURA!", page))
        assertEquals("LOOK\nAT 'IM!", correctionOf("LOOK Be AT \"IM!", page))
        assertEquals(null, correctionOf("PLEASE COME TO ME, ALIENS!", page))
    }

    @Test
    fun `letters of another script aren't a fix`() {
        assertFalse(isCorrectionOf("THEY'LL FALL ALL THE WAY", "THEY'لل FALL ALL THE WAY"))
        assertTrue(isCorrectionOf("CONGRATULATIONS", "CONGRATULATIÓNS"))
    }

    @Test
    fun `other text or nothing isn't`() {
        assertFalse(isCorrectionOf("HEY! LOOK!", "WE FOUND 'IM!"))
        assertFalse(isCorrectionOf("MOMMY", ""))
    }
}
