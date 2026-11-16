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
    fun `serifed T misread as 7 is fixed`() {
        assertEquals("...TO DIE!! THE", "...7O DIE!! 7HE".fixLetteringMistakes())
        assertEquals("AT 7PM ON THE 7TH, 70 OF THEM", "AT 7PM ON THE 7TH, 70 OF THEM".fixLetteringMistakes())
    }

    @Test
    fun `words after an ellipsis misread as digits are fixed`() {
        assertEquals("...SO I'M GONNA", "...50 I'M GONNA".fixLetteringMistakes())
        assertEquals("...IS YOU, AIRA!", "...15 YOU, AIRA!".fixLetteringMistakes())
        assertEquals("...50 years", "...50 years".fixLetteringMistakes())
    }

    @Test
    fun `outline strokes read as characters are dropped or fixed`() {
        assertEquals("REMEM-\nBER IT?", "REMEM-|\nBER IT?".fixLetteringMistakes())
        assertEquals("THE END OF IT, FOLKS!", "THE END OF IT, FOLKS/".fixLetteringMistakes())
        assertEquals("FOR A FAVOR?", "FOR A FAVOR?{".fixLetteringMistakes())
    }

    @Test
    fun `an ellipsis before a line break is fixed`() {
        assertEquals("...IS\nBREAKING...", "...15\nBREAKING...".fixLetteringMistakes())
    }

    @Test
    fun `numbers and slashes between words stay`() {
        assertEquals("CHAPTER 233. AND/OR 1 MORE", "CHAPTER 233. AND/OR 1 MORE".fixLetteringMistakes())
    }
}
