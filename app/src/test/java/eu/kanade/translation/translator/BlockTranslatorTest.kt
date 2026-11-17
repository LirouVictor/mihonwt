package eu.kanade.translation.translator

import eu.kanade.translation.model.joinLines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockTranslatorTest {

    @Test
    fun `capitals become sentence case`() {
        assertEquals("I'm not going! Are you? I think so.", "I'M NOT GOING! ARE YOU? I THINK SO.".toSentenceCase())
    }

    @Test
    fun `capitals misread as small letters are still capitals`() {
        assertEquals("Smashed me and longinus!", "SMASHED\nme AND\nLONGInus!".joinLines().toSentenceCase())
    }

    @Test
    fun `mixed case is kept`() {
        assertEquals("Miss Vamola, help!", "Miss Vamola, help!".toSentenceCase())
    }

    @Test
    fun `glossary terms are kept as the glossary has them`() {
        val glossary = mapOf("Turbo Granny" to "Turbo Granny", "Turbo" to "Turbo", "Okarun" to "Okarun")
        val text = "The powers of turbo granny & okarun's <3".withTermsKept(
            glossaryPattern(glossary.keys),
            glossary.mapKeys { it.key.lowercase() },
        )
        assertEquals("The powers of <keep>Turbo Granny</keep> &amp; <keep>Okarun</keep>'s &lt;3", text)
        assertEquals("The powers of Turbo Granny & Okarun's <3", text.fromXml())
    }

    @Test
    fun `a contracted de before a kept name is spelled out`() {
        assertEquals(
            "Somos a guarda de Vlad, os poderes de Ken e da Turbo Granny.",
            "Somos a guarda d<keep>Vlad</keep>, os poderes de<keep>Ken</keep> e da <keep>Turbo Granny</keep>."
                .fromXml(),
        )
    }

    @Test
    fun `letters tacked onto a kept name are dropped`() {
        assertEquals("Vamola, você tá bem?!", "<keep>Vamola</keep>e, você tá bem?!".fromXml())
    }

    @Test
    fun `a reply cut after a kept name is noticed`() {
        val sent = "<keep>Yuno</keep>'S NOT THE ONLY ONE WITH THE POPULAR FAN CLUB ANYMORE!!"
        assertEquals(true, "<keep>Yuno</keep>".lostTextAfterKept(sent))
        assertEquals(false, "<keep>Yuno</keep> não é mais a única!!".lostTextAfterKept(sent))
        assertEquals(false, "<keep>Yuno</keep>".lostTextAfterKept("<keep>Yuno</keep>!"))
    }
}
