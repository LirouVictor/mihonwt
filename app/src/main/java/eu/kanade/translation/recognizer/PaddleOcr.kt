// Adapted from https://github.com/RapidAI/RapidOCR (its PaddleOCR pre- and post-processing)
// Copyright PaddlePaddle Authors and RapidAI, Apache License 2.0

package eu.kanade.translation.recognizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * On-device OCR with PaddleOCR's PP-OCRv6 small models, under the Apache License 2.0: a detector finds each line of
 * text on the page and a recognizer reads it. It reads comic lettering far better than ML Kit, which was made for
 * photos and documents. The app carries PaddlePaddle/PP-OCRv6_small_det_onnx at revision
 * 28fe5895c24fd108c19eb3e8479f4ab385fbfc62 and PaddlePaddle/PP-OCRv6_small_rec_onnx at revision
 * b8f84f0b80c529de40b4fbb3544b84fa7233a513, whose characters come from its inference.yml.
 */
class PaddleOcr private constructor(
    private val detector: OrtSession,
    private val recognizer: OrtSession,
    private val characters: List<String>,
) : Closeable {

    /** Returns the words on [page], at its coordinates, with lines numbered from [firstLine]. */
    fun read(page: Bitmap, firstLine: Int = 0): List<Word> {
        val lines = findLines(page)
        var number = firstLine
        return lines.flatMap { line -> readLine(page, line)?.let { lineWords(it, line, number++) }.orEmpty() }
    }

    private fun findLines(page: Bitmap): List<TextLine> {
        val (width, height) = detectorInputSize(page.width, page.height)
        val input = page.scale(width, height)
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        if (input !== page) input.recycle()
        val probabilities = run(detector, normalized(pixels, width, height, width), height, width)
        return textLines(probabilities, width, height, page.width, page.height)
    }

    private fun readLine(page: Bitmap, line: TextLine): ReadLine? {
        val crop = line.crop
        val left = crop.left.roundToInt().coerceIn(0, page.width - 1)
        val top = crop.top.roundToInt().coerceIn(0, page.height - 1)
        val right = crop.right.roundToInt().coerceIn(left + 1, page.width)
        val bottom = crop.bottom.roundToInt().coerceIn(top + 1, page.height)
        val vertical = bottom - top >= (right - left) * VERTICAL_RATIO
        // A column of text is read turned to lie flat, its top on the left
        val matrix = if (vertical) Matrix().apply { postRotate(-90f) } else null
        val image = Bitmap.createBitmap(page, left, top, right - left, bottom - top, matrix, false)
        val width = min(ceil(REC_HEIGHT.toFloat() * image.width / image.height).toInt(), MAX_REC_WIDTH)
        val paddedWidth = max(width, MIN_REC_WIDTH)
        val scaled = image.scale(width, REC_HEIGHT)
        if (image !== page && image !== scaled) image.recycle()
        val pixels = IntArray(width * REC_HEIGHT)
        scaled.getPixels(pixels, 0, width, 0, 0, width, REC_HEIGHT)
        if (scaled !== page) scaled.recycle()
        val probabilities =
            run(recognizer, normalized(pixels, width, REC_HEIGHT, paddedWidth), REC_HEIGHT, paddedWidth)
        val steps = probabilities.size / characters.size
        val decoded = decodeCtc(probabilities, steps, characters.size) ?: return null
        if (decoded.indices.all { characters[it].isBlank() }) return null
        // Each step of the recognizer covers the same width of the padded image
        val stepLength = paddedWidth.toFloat() / steps * (if (vertical) bottom - top else right - left) / width
        return ReadLine(decoded, left.toFloat(), top.toFloat(), stepLength, vertical)
    }

    private fun run(session: OrtSession, input: FloatArray, height: Int, width: Int): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, height.toLong(), width.toLong()),
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val buffer = output.floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    private fun lineWords(read: ReadLine, line: TextLine, number: Int): List<Word> {
        val bounds = line.bounds
        val letters = mutableListOf<MutableList<Letter>>(mutableListOf())
        read.decoded.indices.forEachIndexed { i, index ->
            val character = characters[index]
            if (character.isBlank()) {
                if (letters.last().isNotEmpty()) letters.add(mutableListOf())
                return@forEachIndexed
            }
            val start = read.decoded.starts[i] * read.stepLength
            val end = read.decoded.ends[i] * read.stepLength
            letters.last() += if (read.vertical) {
                Letter(character, bounds.left, read.top + start, bounds.width, end - start)
            } else {
                // Steps fall on the letters, and the line's bounds give how tall they are
                Letter(character, read.left + start, bounds.top, end - start, bounds.height)
            }
        }
        return letters.filter { it.isNotEmpty() }.mapNotNull { wordOf(it, number, 0f) }
    }

    override fun close() {
        detector.close()
        recognizer.close()
    }

    /** What was read on a line cropped at [left], [top], with each step of [decoded] [stepLength] long on the page. */
    private class ReadLine(
        val decoded: Decoded,
        val left: Float,
        val top: Float,
        val stepLength: Float,
        val vertical: Boolean,
    )

    companion object {
        private const val DETECTOR_ASSET = "ppocrv6_small_det.onnx"
        private const val RECOGNIZER_ASSET = "ppocrv6_small_rec.onnx"
        private const val CHARACTERS_ASSET = "ppocrv6_keys.txt"

        private const val REC_HEIGHT = 48
        private const val MIN_REC_WIDTH = 320

        // Wider lines are squeezed, as a run of thousands of pixels is no line of a bubble
        private const val MAX_REC_WIDTH = 3200
        private const val VERTICAL_RATIO = 1.5f

        /** Loads the models that come with the app. */
        suspend fun load(context: Context): PaddleOcr = withContext(Dispatchers.IO) {
            val environment = OrtEnvironment.getEnvironment()
            fun session(asset: String): OrtSession {
                val model = context.assets.open(asset).use { it.readBytes() }
                return try {
                    environment.createSession(model, ortSessionOptions(xnnpack = true))
                } catch (e: OrtException) {
                    logcat(LogPriority.WARN, e)
                    environment.createSession(model, ortSessionOptions(xnnpack = false))
                }
            }
            // The recognizer's classes are a blank, the characters of the file and a space
            val characters = listOf("") +
                context.assets.open(CHARACTERS_ASSET).bufferedReader().readLines().filter { it.isNotEmpty() } +
                listOf(" ")
            PaddleOcr(session(DETECTOR_ASSET), session(RECOGNIZER_ASSET), characters)
        }
    }
}

/** A box at page coordinates. */
internal data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** A line of text: the [crop] the recognizer reads, with room around it, and the [bounds] of its letters. */
internal class TextLine(val crop: Box, val bounds: Box)

/** What the recognizer read on a line: classes by [indices], each from step [starts] to [ends]. */
internal class Decoded(val indices: IntArray, val starts: IntArray, val ends: IntArray)

/**
 * Returns the size the detector reads a page of [width] by [height] at: sides a multiple of 32, the shorter at least
 * [DET_MIN_SIDE] where the longer can stay at most [DET_MAX_SIDE], as PaddleOCR reads pages.
 */
internal fun detectorInputSize(width: Int, height: Int): Pair<Int, Int> {
    // Unlike PaddleOCR, a long thin crop isn't enlarged past the longest side, which took gigabytes for a caption
    val scale = min(
        DET_MAX_SIDE.toFloat() / max(width, height),
        max(1f, DET_MIN_SIDE.toFloat() / min(width, height)),
    )

    // Halves round to even, as in PaddleOCR's Python
    fun side(length: Int) = max(32, round(length * scale / 32.0).toInt() * 32)
    return side(width) to side(height)
}

/** Returns the [pixels] of an image of [width] by [height] in blue, green, red planes from -1 to 1, [planeWidth] wide. */
internal fun normalized(pixels: IntArray, width: Int, height: Int, planeWidth: Int): FloatArray {
    val plane = planeWidth * height
    val input = FloatArray(3 * plane)
    for (y in 0..<height) {
        for (x in 0..<width) {
            val color = pixels[y * width + x]
            val i = y * planeWidth + x
            input[i] = (color and 0xFF) / 127.5f - 1f
            input[plane + i] = (color shr 8 and 0xFF) / 127.5f - 1f
            input[2 * plane + i] = (color shr 16 and 0xFF) / 127.5f - 1f
        }
    }
    return input
}

/**
 * Returns the lines of text in the detector's [probabilities] of text, a map of [width] by [height], at the
 * coordinates of a page of [pageWidth] by [pageHeight]. As in PaddleOCR, each patch of likely text is a line, grown
 * back by as much as the model was trained to shrink it, though here boxes stay upright.
 */
internal fun textLines(
    probabilities: FloatArray,
    width: Int,
    height: Int,
    pageWidth: Int,
    pageHeight: Int,
): List<TextLine> {
    val text = BooleanArray(width * height) { probabilities[it] > TEXT_THRESHOLD }
    // Grown by a pixel up and left, as PaddleOCR dilates the map with a 2 by 2 kernel
    val mask = BooleanArray(width * height) { i ->
        val x = i % width
        val y = i / width
        text[i] || (x > 0 && text[i - 1]) || (y > 0 && text[i - width]) || (x > 0 && y > 0 && text[i - width - 1])
    }
    val scaleX = pageWidth.toFloat() / width
    val scaleY = pageHeight.toFloat() / height
    return patches(mask, width, height).mapNotNull { patch ->
        val patchWidth = patch.right - patch.left + 1
        val patchHeight = patch.bottom - patch.top + 1
        if (min(patchWidth, patchHeight) < MIN_PATCH_SIDE) return@mapNotNull null
        var sum = 0.0
        for (y in patch.top..patch.bottom) for (x in patch.left..patch.right) sum += probabilities[y * width + x]
        if (sum / (patchWidth * patchHeight) < MIN_LINE_SCORE) return@mapNotNull null
        fun distance(ratio: Float) = patchWidth * patchHeight * ratio / (2 * (patchWidth + patchHeight))
        if (min(patchWidth, patchHeight) + 2 * distance(CROP_UNCLIP_RATIO) < MIN_GROWN_SIDE) return@mapNotNull null
        fun grown(ratio: Float): Box {
            val distance = distance(ratio)
            return Box(
                ((patch.left - distance) * scaleX).coerceAtLeast(0f),
                ((patch.top - distance) * scaleY).coerceAtLeast(0f),
                ((patch.right + 1 + distance) * scaleX).coerceAtMost(pageWidth.toFloat()),
                ((patch.bottom + 1 + distance) * scaleY).coerceAtMost(pageHeight.toFloat()),
            )
        }
        val crop = grown(CROP_UNCLIP_RATIO)
        if (crop.width <= MIN_LINE_SIDE || crop.height <= MIN_LINE_SIDE) return@mapNotNull null
        TextLine(crop, grown(LETTERS_UNCLIP_RATIO))
    }
}

private class Patch(var left: Int, var top: Int, var right: Int, var bottom: Int)

/** Returns the bounds of each group of touching pixels of [mask], counting diagonal neighbors. */
private fun patches(mask: BooleanArray, width: Int, height: Int): List<Patch> {
    val seen = BooleanArray(mask.size)
    val stack = IntArray(mask.size)
    val patches = mutableListOf<Patch>()
    for (start in mask.indices) {
        if (!mask[start] || seen[start]) continue
        val patch = Patch(start % width, start / width, start % width, start / width)
        var size = 0
        stack[size++] = start
        seen[start] = true
        while (size > 0) {
            val i = stack[--size]
            val x = i % width
            val y = i / width
            patch.left = min(patch.left, x)
            patch.right = max(patch.right, x)
            patch.top = min(patch.top, y)
            patch.bottom = max(patch.bottom, y)
            for (dy in -1..1) {
                val ny = y + dy
                if (ny !in 0..<height) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx !in 0..<width) continue
                    val n = ny * width + nx
                    if (mask[n] && !seen[n]) {
                        seen[n] = true
                        stack[size++] = n
                    }
                }
            }
        }
        patches += patch
    }
    return patches
}

/**
 * Returns the classes the recognizer read in its [probabilities] of [steps] by [classes], merging repeats and dropping blanks
 * (class 0), or null when it is unsure of them on average.
 */
internal fun decodeCtc(probabilities: FloatArray, steps: Int, classes: Int): Decoded? {
    val indices = mutableListOf<Int>()
    val starts = mutableListOf<Int>()
    val ends = mutableListOf<Int>()
    var probabilitySum = 0f
    var previous = -1
    for (step in 0..<steps) {
        var best = 0
        for (c in 1..<classes) if (probabilities[step * classes + c] > probabilities[step * classes + best]) best = c
        if (best != 0 && best != previous) {
            indices += best
            starts += step
            ends += step + 1
            probabilitySum += probabilities[step * classes + best]
        } else if (best != 0) {
            ends[ends.lastIndex] = step + 1
        }
        previous = best
    }
    if (indices.isEmpty() || probabilitySum / indices.size < MIN_TEXT_SCORE) return null
    // A letter runs on until the next one, or the space after a word, starts
    for (i in 0..<starts.lastIndex) ends[i] = starts[i + 1]
    return Decoded(indices.toIntArray(), starts.toIntArray(), ends.toIntArray())
}

private const val DET_MIN_SIDE = 736
private const val DET_MAX_SIDE = 2000
private const val TEXT_THRESHOLD = 0.3f
private const val MIN_LINE_SCORE = 0.5f
private const val MIN_TEXT_SCORE = 0.5f
private const val MIN_PATCH_SIDE = 3
private const val MIN_GROWN_SIDE = 5f
private const val MIN_LINE_SIDE = 3f

// PaddleOCR's own ratio, which gives the recognizer some room around the letters
private const val CROP_UNCLIP_RATIO = 1.6f

// Measured against the letters ML Kit found on the same lines
private const val LETTERS_UNCLIP_RATIO = 0.5f
