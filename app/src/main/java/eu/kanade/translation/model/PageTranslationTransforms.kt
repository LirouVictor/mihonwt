package eu.kanade.translation.model

import android.graphics.Rect
import tachiyomi.core.common.util.system.ImageUtil

/** The blocks on one half of the image, as [ImageUtil.splitInHalf] cuts it. */
fun PageTranslation.half(side: ImageUtil.Side): PageTranslation {
    val halfWidth = (imgWidth.toInt() / 2).toFloat()
    val left = halfLeft(side)
    return PageTranslation(
        blocks = blocks
            .filter { it.centerX >= left && it.centerX < left + halfWidth }
            .mapTo(mutableListOf()) { it.copy(x = it.x - left) },
        imgWidth = halfWidth,
        imgHeight = imgHeight,
    )
}

/** The blocks of the image [ImageUtil.splitAndMerge] makes: the [upperSide] half above the other. */
fun PageTranslation.stackedHalves(upperSide: ImageUtil.Side): PageTranslation {
    val upper = half(upperSide)
    val lowerSide = if (upperSide == ImageUtil.Side.LEFT) ImageUtil.Side.RIGHT else ImageUtil.Side.LEFT
    val lower = half(lowerSide)
    return PageTranslation(
        blocks = (upper.blocks + lower.blocks.map { it.copy(y = it.y + imgHeight) }).toMutableList(),
        imgWidth = upper.imgWidth,
        imgHeight = imgHeight * 2,
    )
}

/** The blocks of the image [ImageUtil.rotateImage] makes, turned clockwise by [degrees], 90 or -90. */
fun PageTranslation.rotated(degrees: Float): PageTranslation {
    val clockwise = degrees > 0
    return PageTranslation(
        blocks = blocks.mapTo(mutableListOf()) {
            it.copy(
                x = if (clockwise) imgHeight - it.y - it.height else it.y,
                y = if (clockwise) it.x else imgWidth - it.x - it.width,
                width = it.height,
                height = it.width,
                symWidth = it.symHeight,
                symHeight = it.symWidth,
                angle = it.angle + degrees,
            )
        },
        imgWidth = imgHeight,
        imgHeight = imgWidth,
    )
}

/** The blocks of the image cut down to [rect]. */
fun PageTranslation.cropped(rect: Rect): PageTranslation {
    return PageTranslation(
        blocks = blocks.mapTo(mutableListOf()) { it.copy(x = it.x - rect.left, y = it.y - rect.top) },
        imgWidth = rect.width().toFloat(),
        imgHeight = rect.height().toFloat(),
    )
}

private fun PageTranslation.halfLeft(side: ImageUtil.Side): Float {
    val width = imgWidth.toInt()
    return when (side) {
        ImageUtil.Side.LEFT -> 0f
        ImageUtil.Side.RIGHT -> (width - width / 2).toFloat()
    }
}

private val TranslationBlock.centerX: Float
    get() = x + width / 2
