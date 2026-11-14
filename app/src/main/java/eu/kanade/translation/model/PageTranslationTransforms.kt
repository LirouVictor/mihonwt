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
            .mapTo(mutableListOf()) { it.shifted(-left, 0f) },
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
        blocks = (upper.blocks + lower.blocks.map { it.shifted(0f, imgHeight) }).toMutableList(),
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
                bubble = it.bubble.map { area ->
                    Area(
                        x = if (clockwise) imgHeight - area.y - area.height else area.y,
                        y = if (clockwise) area.x else imgWidth - area.x - area.width,
                        width = area.height,
                        height = area.width,
                    )
                },
            )
        },
        imgWidth = imgHeight,
        imgHeight = imgWidth,
    )
}

/** The blocks of the image cut down to [rect]. */
fun PageTranslation.cropped(rect: Rect): PageTranslation {
    return PageTranslation(
        blocks = blocks.mapTo(mutableListOf()) { it.shifted(-rect.left.toFloat(), -rect.top.toFloat()) },
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

private fun TranslationBlock.shifted(dx: Float, dy: Float) = copy(
    x = x + dx,
    y = y + dy,
    bubble = bubble.map { it.copy(x = it.x + dx, y = it.y + dy) },
)

private val TranslationBlock.centerX: Float
    get() = x + width / 2
