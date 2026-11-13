package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class PageTranslationProcessingTest {

    private data class Settings(
        override val dualPageSplit: Boolean = false,
        override val dualPageInvert: Boolean = false,
        override val dualPageRotateToFit: Boolean = false,
        override val dualPageRotateToFitInvert: Boolean = false,
    ) : DualPageSettings

    private fun block(text: String, x: Float, y: Float) = TranslationBlock(
        text = text,
        width = 100f,
        height = 50f,
        x = x,
        y = y,
        symHeight = 10f,
        symWidth = 8f,
        angle = 0f,
    )

    // A 1001 px wide spread: the halves ImageUtil cuts are 500 px, the right one starting at 501
    private val spread = PageTranslation(
        blocks = mutableListOf(block("left", 10f, 20f), block("right", 700f, 30f)),
        imgWidth = 1001f,
        imgHeight = 800f,
    )

    private val page = ReaderPage(0).apply { chapter = mockk(relaxed = true) }

    @Test
    fun `split spread shows the reading side first and the other half on the inserted page`() {
        val settings = Settings(dualPageSplit = true)

        val first = settings.processPagedTranslation(page, spread, isLeftToRight = false)
        first.imgWidth shouldBe 500f
        first.blocks.map { it.text to it.x } shouldBe listOf("right" to 199f)

        val second = settings.processPagedTranslation(InsertPage(page), spread, isLeftToRight = false)
        second.blocks.map { it.text to it.x } shouldBe listOf("left" to 10f)
    }

    @Test
    fun `inverted split swaps the halves`() {
        val settings = Settings(dualPageSplit = true, dualPageInvert = true)

        val first = settings.processPagedTranslation(page, spread, isLeftToRight = true)
        first.blocks.map { it.text } shouldBe listOf("right")
    }

    @Test
    fun `narrow page is left alone`() {
        val narrow = spread.copy(imgWidth = 700f)

        Settings(dualPageSplit = true).processPagedTranslation(page, narrow, isLeftToRight = true) shouldBe narrow
    }

    @Test
    fun `rotating clockwise moves the top left corner to the top right`() {
        val rotated = Settings(dualPageRotateToFit = true).processPagedTranslation(page, spread, isLeftToRight = true)

        rotated.imgWidth shouldBe 800f
        rotated.imgHeight shouldBe 1001f
        val left = rotated.blocks.first()
        left.x shouldBe 800f - 20f - 50f
        left.y shouldBe 10f
        left.width shouldBe 50f
        left.height shouldBe 100f
        left.angle shouldBe 90f
    }

    @Test
    fun `rotating anticlockwise moves the top left corner to the bottom left`() {
        val settings = Settings(dualPageRotateToFit = true, dualPageRotateToFitInvert = true)
        val left = settings.processPagedTranslation(page, spread, isLeftToRight = true).blocks.first()

        left.x shouldBe 20f
        left.y shouldBe 1001f - 10f - 100f
        left.angle shouldBe -90f
    }

    @Test
    fun `strip puts the right half above the left one`() {
        val strip = Settings(dualPageSplit = true).processStripTranslation(spread)

        strip.imgWidth shouldBe 500f
        strip.imgHeight shouldBe 1600f
        strip.blocks.map { Triple(it.text, it.x, it.y) } shouldBe listOf(
            Triple("right", 199f, 30f),
            Triple("left", 10f, 820f),
        )
    }
}
