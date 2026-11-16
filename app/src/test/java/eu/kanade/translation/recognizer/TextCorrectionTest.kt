package eu.kanade.translation.recognizer

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
    fun `other text or nothing isn't`() {
        assertFalse(isCorrectionOf("HEY! LOOK!", "WE FOUND 'IM!"))
        assertFalse(isCorrectionOf("MOMMY", ""))
    }
}
