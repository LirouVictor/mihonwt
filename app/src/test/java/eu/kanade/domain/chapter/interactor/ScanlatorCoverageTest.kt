package eu.kanade.domain.chapter.interactor

import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ScanlatorCoverageTest {

    @Test
    fun `coverage counts distinct chapter numbers across groups including fractional chapters`() {
        val coverage = listOf(
            chapter(1, 1.0, "TCB"),
            chapter(2, 1.0, "TCB"),
            chapter(3, 1.5, "TCB"),
            chapter(4, 1.0, "VIZ"),
            chapter(5, 2.0, "VIZ"),
            chapter(6, 3.0, null),
            chapter(7, -1.0, "Extras"),
        ).scanlatorCoverage()

        assertEquals(4, coverage.numberedChapters)
        assertEquals(mapOf("TCB" to 2, "VIZ" to 2, "Extras" to 0), coverage.counts)
        assertTrue(coverage.hasUnidentifiedScanlator)
    }

    @Test
    fun `navigation skips duplicate releases and keeps each unnumbered entry`() {
        val preferred = chapter(2, 1.0, "TCB")
        val chapters = listOf(
            chapter(1, 1.0, "VIZ"),
            preferred,
            chapter(3, 2.0, "VIZ"),
            chapter(4, 2.0, "TCB"),
            chapter(5, -1.0, "TCB"),
            chapter(6, -1.0, "TCB"),
        )

        assertEquals(listOf(2L, 4L, 5L, 6L), chapters.removeDuplicates(preferred).map { it.id })
    }

    private fun chapter(id: Long, number: Double, scanlator: String?) =
        Chapter.create().copy(id = id, chapterNumber = number, scanlator = scanlator)
}
