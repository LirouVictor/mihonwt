package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.half
import eu.kanade.translation.model.rotated
import eu.kanade.translation.model.stackedHalves
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil

/**
 * The image of [page] as a pager shows it: a wide page rotated to fit or split in two, the half on the reading
 * side first, so [isLeftToRight] tells which. [onPageSplit] is told of the [InsertPage] for the second half.
 */
fun DualPageSettings.processPagedImage(
    page: ReaderPage,
    imageSource: BufferedSource,
    isLeftToRight: Boolean,
    onPageSplit: (InsertPage) -> Unit,
): BufferedSource {
    if (dualPageRotateToFit) {
        return rotateDualPage(imageSource)
    }

    if (!dualPageSplit) {
        return imageSource
    }

    if (page is InsertPage) {
        return splitInHalf(imageSource, page, isLeftToRight)
    }

    val isDoublePage = ImageUtil.isWideImage(imageSource)
    if (!isDoublePage) {
        return imageSource
    }

    onPageSplit(InsertPage(page))

    return splitInHalf(imageSource, page, isLeftToRight)
}

/**
 * The image of a page as a strip shows it: a wide page rotated to fit, or split with its halves one above the other.
 */
fun DualPageSettings.processStripImage(imageSource: BufferedSource): BufferedSource {
    if (dualPageRotateToFit) {
        return rotateDualPage(imageSource)
    }

    if (dualPageSplit) {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (isDoublePage) {
            return ImageUtil.splitAndMerge(imageSource, stripUpperSide)
        }
    }

    return imageSource
}

private fun DualPageSettings.rotateDualPage(imageSource: BufferedSource): BufferedSource {
    val isDoublePage = ImageUtil.isWideImage(imageSource)
    return if (isDoublePage) {
        ImageUtil.rotateImage(imageSource, rotateToFitDegrees)
    } else {
        imageSource
    }
}

private fun DualPageSettings.splitInHalf(
    imageSource: BufferedSource,
    page: ReaderPage,
    isLeftToRight: Boolean,
): BufferedSource {
    return ImageUtil.splitInHalf(imageSource, splitSide(page, isLeftToRight))
}

/**
 * [translation] moved onto the image [processPagedImage] makes of its [page], whose image it was made from.
 */
fun DualPageSettings.processPagedTranslation(
    page: ReaderPage,
    translation: PageTranslation,
    isLeftToRight: Boolean,
): PageTranslation {
    // What ImageUtil.isWideImage tells of the same image
    val isDoublePage = translation.imgWidth > translation.imgHeight
    return when {
        dualPageRotateToFit -> if (isDoublePage) translation.rotated(rotateToFitDegrees) else translation
        !dualPageSplit -> translation
        page is InsertPage || isDoublePage -> translation.half(splitSide(page, isLeftToRight))
        else -> translation
    }
}

/**
 * [translation] moved onto the image [processStripImage] makes of the image it was made from.
 */
fun DualPageSettings.processStripTranslation(translation: PageTranslation): PageTranslation {
    val isDoublePage = translation.imgWidth > translation.imgHeight
    return when {
        !isDoublePage -> translation
        dualPageRotateToFit -> translation.rotated(rotateToFitDegrees)
        dualPageSplit -> translation.stackedHalves(stripUpperSide)
        else -> translation
    }
}

private val DualPageSettings.rotateToFitDegrees: Float
    get() = if (dualPageRotateToFitInvert) -90f else 90f

private val DualPageSettings.stripUpperSide: ImageUtil.Side
    get() = if (dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT

private fun DualPageSettings.splitSide(page: ReaderPage, isLeftToRight: Boolean): ImageUtil.Side {
    val side = when {
        isLeftToRight && page is InsertPage -> ImageUtil.Side.RIGHT
        !isLeftToRight && page is InsertPage -> ImageUtil.Side.LEFT
        isLeftToRight && page !is InsertPage -> ImageUtil.Side.LEFT
        else -> ImageUtil.Side.RIGHT
    }

    if (!dualPageInvert) return side
    return when (side) {
        ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
        ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
    }
}
