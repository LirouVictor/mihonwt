// Adapted from https://github.com/mihonapp/subsampling-scale-image-view
// Copyright David Morrissey and Mihon contributors, Apache License 2.0

package eu.kanade.translation.presentation

import android.graphics.Rect
import ca.mpreg.imagedecoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.CropBorders
import okio.BufferedSource
import kotlin.math.ceil
import kotlin.math.sqrt

// Must match the subsampling view's decoder, whose crop rect isn't exposed
private const val BORDER_PIXELS = 4_000_000.0

/**
 * The part of [image] the subsampling view shows when it crops borders, or null when it shows all of it. Found the
 * same way as its decoder does, so a translation can be moved onto the image as shown.
 */
fun findBorderCrop(image: BufferedSource): Rect? {
    ImageDecoder.open(image.peek().inputStream()).use { dec ->
        if (dec.isHdr) return null
        val sample = ceil(sqrt(dec.width.toDouble() * dec.height / BORDER_PIXELS)).toInt().coerceAtLeast(1)
        val (borders, sw, sh) = dec.decode(null, sample).use { frame ->
            Triple(
                CropBorders.findCropBorders(frame.toByteArray(), frame.width, frame.height),
                frame.width,
                frame.height,
            )
        }
        if (borders.size < 4) return null
        val (l, t, w, h) = borders
        if (w <= 0 || h <= 0 || l < 0 || t < 0 || l + w > sw || t + h > sh) return null
        val slack = if (sample > 1) 1 else 0
        val crop = Rect(
            ((l - slack).coerceAtLeast(0).toLong() * sample).toInt(),
            ((t - slack).coerceAtLeast(0).toLong() * sample).toInt(),
            ((l + w + slack).toLong() * sample).coerceAtMost(dec.width.toLong()).toInt(),
            ((t + h + slack).toLong() * sample).coerceAtMost(dec.height.toLong()).toInt(),
        )
        return crop.takeIf { !it.isEmpty }
    }
}
