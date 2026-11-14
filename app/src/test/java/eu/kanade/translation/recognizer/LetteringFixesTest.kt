package eu.kanade.translation.recognizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LetteringFixesTest {

    @Test
    fun `serifed I misread as other characters is fixed`() {
        assertEquals("...IS JUST LIKE", "...1S JUST LIKE".fixLetteringMistakes())
        assertEquals("WHAT IS THIS?!", "WHAT /S THIS?!".fixLetteringMistakes())
        assertEquals("THIS", "TH1S".fixLetteringMistakes())
        assertEquals("IT'S", "|T'S".fixLetteringMistakes())
    }

    @Test
    fun `numbers and slashes between words stay`() {
        assertEquals("CHAPTER 233. AND/OR 1 MORE", "CHAPTER 233. AND/OR 1 MORE".fixLetteringMistakes())
    }
}
