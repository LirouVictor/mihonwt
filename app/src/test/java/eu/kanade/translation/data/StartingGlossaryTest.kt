package eu.kanade.translation.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartingGlossaryTest {

    @Test
    fun `names are kept with their parts, and single English words left out`() {
        val glossary = startingGlossary(
            characters = listOf("Monkey D. Luffy", "Sanji", "Brook", "Big Mom"),
            terms = mapOf("Supreme King Haki" to "Haki do Rei Supremo", "Fire" to "Fogo", "Germa" to "Germa"),
            isWord = { it.uppercase() in setOf("MONKEY", "BROOK", "BIG", "MOM", "FIRE") },
        )

        assertEquals(
            mapOf(
                "Monkey D. Luffy" to "Monkey D. Luffy",
                "Luffy" to "Luffy",
                "Sanji" to "Sanji",
                "Big Mom" to "Big Mom",
                "Supreme King Haki" to "Haki do Rei Supremo",
                "Germa" to "Germa",
            ),
            glossary,
        )
    }

    @Test
    fun `AniList characters are read with their other names`() {
        val reply = Json.parseToJsonElement(
            """
            {"data":{"Media":{"characters":{"nodes":[
              {"name":{"full":"Zoro Roronoa","alternative":["Pirate Hunter","","Zolo","Lorenor Zorro"]}},
              {"name":{"full":"Nami","alternative":[]}},
              {"name":{"full":"Ken Takakura","alternative":["Okarun (オカルン)", "ジジ"]}}
            ]}}}}
            """,
        ) as JsonObject

        assertEquals(
            listOf("Zoro Roronoa", "Zolo", "Nami", "Ken Takakura", "Okarun"),
            CharacterLookup.characterNamesOf(reply),
        )
        assertEquals(
            emptyList<String>(),
            CharacterLookup.characterNamesOf(Json.parseToJsonElement("""{"data":{"Media":null}}""") as JsonObject),
        )
    }

    @Test
    fun `Jikan characters are read main first, first name first`() {
        val reply = Json.parseToJsonElement(
            """
            {"data":[
              {"character":{"name":"Vinsmoke, Judge"},"role":"Supporting","favorites":10},
              {"character":{"name":"Nami"},"role":"Main","favorites":5},
              {"character":{"name":"Roronoa, Zoro"},"role":"Main","favorites":50}
            ]}
            """,
        ) as JsonObject

        assertEquals(listOf("Zoro Roronoa", "Nami", "Judge Vinsmoke"), CharacterLookup.jikanNamesOf(reply))
    }
}
