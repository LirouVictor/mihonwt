package eu.kanade.translation.recognizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrSpellingTest {
    private val spelling = OcrSpelling(
        mapOf(
            "JUST" to 0, "MUST" to 0, "DREARY" to 1, "NIGHTS" to 0, "DAMN" to 1, "HERE" to 0, "AMAZING" to 1,
            "ASSASSIN" to 2, "CHAIN" to 0, "THEYRE" to 0, "DONT" to 0, "CAR" to 0, "MESS" to 0, "GOOD" to 0,
            "THANK" to 0, "OPEN" to 0, "BAT" to 1, "GRIZZLY" to 2, "THE" to 0, "BEAR" to 1,
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
}
