package eu.kanade.translation.translator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockTranslatorTest {

    @Test
    fun `capitals become sentence case`() {
        assertEquals("I'm not going! Are you? I think so.", "I'M NOT GOING! ARE YOU? I THINK SO.".toSentenceCase())
    }

    @Test
    fun `mixed case is kept`() {
        assertEquals("Miss Vamola, help!", "Miss Vamola, help!".toSentenceCase())
    }
}
