package eu.kanade.translation.recognizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrSpellingTest {
    private val spelling = OcrSpelling(
        mapOf(
            "JUST" to 0, "MUST" to 0, "DREARY" to 1, "NIGHTS" to 0, "DAMN" to 1, "HERE" to 0, "AMAZING" to 1,
            "ASSASSIN" to 2, "CHAIN" to 0, "THEYRE" to 0, "DONT" to 0, "CAR" to 0, "MESS" to 0, "GOOD" to 0,
            "THANK" to 0, "OPEN" to 0, "BAT" to 1, "GRIZZLY" to 2, "THE" to 0, "BEAR" to 1,
            "ONLY" to 0, "OILY" to 2, "WIFE" to 0, "SAID" to 0, "SHOULD" to 0, "READER" to 0, "REQUEST" to 0,
            "NOR" to 0, "MAN" to 0, "TALK" to 0, "IN" to 0, "TALKING" to 0, "CHICKEN" to 0, "MODERN" to 1,
            "ITS" to 0, "OBVIOUS" to 0, "TO" to 0, "NO" to 0, "THIN" to 0, "EVERY" to 0, "ONES" to 0,
            "EVERYONE" to 0, "STONE" to 0, "BONNET" to 1, "PANDER" to 2, "FORGED" to 2, "TESTING" to 0,
            "PROTECTING" to 0, "PRO" to 0, "BONNETS" to 1,
        ),
    )

    @Test
    fun `words misread from lettering are fixed`() {
        assertEquals(
            "IT'S JUST ONE OF THOSE DAMN DREARY NIGHTS",
            spelling.fix("IT'S UST ONE OF THOSE PAMN PREARY NIGHTS"),
        )
        assertEquals("THE AMAZING ASSASSIN", spelling.fix("THE AnAZING AS5ASSIN"))
        assertEquals("HERE", spelling.fix("HEKE"))
        assertEquals("THEY'RE", spelling.fix("THEY'KE"))
        assertEquals("I DON'T", spelling.fix("I PON'T"))
        assertEquals("Grizzly bear", spelling.fix("Grzzly bear"))
        assertEquals("ONLY WIFE", spelling.fix("OWLY WWFE"))
    }

    @Test
    fun `unknown words without a single likely fix stay`() {
        // OCR misreads "D" as "P", never the other way, so "ODEN" is no "OPEN"
        assertEquals("ODEN VAMOLA CAK", spelling.fix("ODEN VAMOLA CAK"))
        // Short words take no fix but a dropped "J", as "CLAK" read as "CAK" isn't "CAR"
        assertEquals("CAK", spelling.fix("CAK"))
        // A name with vowels gains no "I", and an uppercase "N" is taken as read
        assertEquals("VAMO CHAN! THANK GOOD NESS", spelling.fix("VAMO CHAN! THANK GOOD NESS"))
        assertEquals("ON 233", spelling.fix("ON 233"))
    }

    @Test
    fun `names of the series are known`() {
        val names = OcrSpelling.namesOf(listOf("Gary Grizzly", "Vamola"))
        assertEquals("GARY GRIZZLY", spelling.fix("GARY GRZZLY", names))
        assertEquals("OPEN", spelling.fix("OPEN", names))
    }

    @Test
    fun `words OCR ran together are split`() {
        assertEquals("I SAID SHOULD I", spelling.fix("ISAID SHOULDI"))
        assertEquals("ITS OBVIOUS JUST TO I DON'T", spelling.fix("ITSOBVIOUS JUSTTO IDON'T"))
        assertEquals("READER REQUEST", spelling.fix("READERREQUEST"))
        // Short pairs are as likely a name or a dropped ending
        assertEquals("NORMAN TALKIN", spelling.fix("NORMAN TALKIN"))
    }

    @Test
    fun `any letter changes once the series' names are known`() {
        assertEquals("CHCKEN", spelling.fix("CHCKEN"))
        assertEquals("CHICKEN TALKING", spelling.fix("CHCKEN TALKIN", anyLetter = true))
        assertEquals("TAKAKURA", spelling.fix("TAKAKURA", setOf("TAKAKURA"), anyLetter = true))
        assertEquals("MODERN", spelling.fix("MODEM"))
    }

    @Test
    fun `short words are split off only where they aren't a name or an ending`() {
        assertEquals("NOTHIN TALKIN", spelling.fix("NOTHIN TALKIN"))
    }

    @Test
    fun `possessives of known words are kept`() {
        assertEquals("EVERYONE'S", spelling.fix("EVERYONE'S", anyLetter = true))
    }

    @Test
    fun `six-letter words only get a dropped letter back`() {
        assertEquals("SATONE BONNEY'S PANZER", spelling.fix("SATONE BONNEY'S PANZER", anyLetter = true))
    }

    @Test
    fun `a glued I is split off rather than dropped`() {
        assertEquals("I FORGED", spelling.fix("IFORGED", anyLetter = true))
    }

    @Test
    fun `words hyphenated across lines are checked whole`() {
        assertEquals("PROTECTING", spelling.fix("PRO-\nTECTING", anyLetter = true))
        assertEquals("BE PROTECTING", spelling.fix("BE PRO\nTECTING", anyLetter = true))
    }
}
