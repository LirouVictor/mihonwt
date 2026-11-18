// Adapted from https://huggingface.co/genshiai-daichi/baberu-ocr (onnx_infer.py, its decoding loop)
// Copyright genshiai-daichi, Apache License 2.0

package eu.kanade.translation.recognizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.TranslationBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Baberu OCR, under the Apache License 2.0: a model trained on manga and comic lettering that reads the whole text
 * of one bubble from its image. It gives no positions, so it only rereads the bubbles another OCR found. Its models,
 * about 158 MB, are downloaded the first time from genshiai-daichi/baberu-ocr at revision [REVISION], except its vision
 * encoder, which this repository's Models workflow makes int8 from the float16 one, as the int4 one runs half as fast.
 */
class BaberuOcr private constructor(
    private val vision: OrtSession,
    private val prefill: OrtSession,
    private val step: OrtSession,
    private val characters: List<String>,
) : Closeable {

    // Runs of one letter or digit are capped lower than runs of symbols like "~", which stretch sounds
    private val contentIds = characters.indices
        .filter { characters[it].singleOrNull()?.let(::isContent) == true }
        .map { it + FIRST_CHARACTER_ID }
        .toSet()

    /** Returns the text in [image], a crop of one bubble's lettering. */
    fun read(image: Bitmap): String {
        val env = OrtEnvironment.getEnvironment()
        val pixels = pixelValues(image)
        val tokens = mutableListOf<Int>()
        val seen = mutableSetOf(BOS)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
            .use { input ->
                vision.run(mapOf(PIXELS to input)).use { visionResult ->
                    val embeds = visionResult.get(EMBEDS).get() as OnnxTensor
                    var position = embeds.info.shape[1] + 1
                    var result = longTensor(env, BOS.toLong()).use { bos ->
                        prefill.run(mapOf(EMBEDS to embeds, INPUT_IDS to bos))
                    }
                    try {
                        while (tokens.size < MAX_TOKENS) {
                            val next = nextToken(lastLogits(result), seen, tokens)
                            if (next == EOS) break
                            tokens += next
                            seen += next
                            if (tokens.size >= MAX_TOKENS) break
                            val inputs = (0..<LAYERS).flatMap { layer ->
                                listOf("k", "v").map { kind ->
                                    "past_$kind$layer" to result.get("present_$kind$layer").get() as OnnxTensor
                                }
                            }.toMap()
                            val stepResult = longTensor(env, next.toLong()).use { id ->
                                longTensor(env, position).use { pos ->
                                    step.run(inputs + mapOf(INPUT_IDS to id, POSITION_IDS to pos))
                                }
                            }
                            result.close()
                            result = stepResult
                            position++
                        }
                    } finally {
                        result.close()
                    }
                }
            }
        val text = tokens.joinToString("") { characters.getOrElse(it - FIRST_CHARACTER_ID) { "" } }
        // It sometimes answers in full-width letters, as Japanese text sets Latin
        return Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
    }

    private fun nextToken(logits: FloatArray, seen: Set<Int>, tokens: List<Int>): Int {
        seen.forEach { id ->
            val score = logits[id]
            logits[id] = if (score < 0) score * REPETITION_PENALTY else score / REPETITION_PENALTY
        }
        val last = tokens.lastOrNull()
        if (last != null && last >= FIRST_CHARACTER_ID) {
            val cap = if (last in contentIds) MAX_CONTENT_RUN else MAX_SYMBOL_RUN
            if (tokens.takeLastWhile { it == last }.size >= cap) logits[last] = Float.NEGATIVE_INFINITY
        }
        return logits.indices.maxBy { logits[it] }
    }

    private fun lastLogits(result: OrtSession.Result): FloatArray {
        val buffer = (result.get(LOGITS).get() as OnnxTensor).floatBuffer
        val vocabulary = characters.size + FIRST_CHARACTER_ID
        // Prefill gives logits for every position; the next letter follows the last
        buffer.position(buffer.capacity() - vocabulary)
        return FloatArray(vocabulary).also { buffer.get(it) }
    }

    override fun close() {
        vision.close()
        prefill.close()
        step.close()
    }

    companion object {
        const val REVISION = "b9868433dbda3ed9ba1357d008599ff4ada873d5"

        private const val SIZE = 224
        private const val PIXELS = "pixel_values"
        private const val EMBEDS = "vision_embeds"
        private const val INPUT_IDS = "input_ids"
        private const val POSITION_IDS = "position_ids"
        private const val LOGITS = "logits"
        private const val LAYERS = 6
        private const val BOS = 1
        private const val EOS = 2

        // Ids below are padding, start, end and unknown
        private const val FIRST_CHARACTER_ID = 4
        private const val MAX_TOKENS = 256
        private const val REPETITION_PENALTY = 1.2f
        private const val MAX_CONTENT_RUN = 12
        private const val MAX_SYMBOL_RUN = 16

        // DINOv2's normalization, by channel
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        private const val VISION = "onnx/vision_int8.onnx"
        private const val VISION_URL =
            "https://github.com/LirouVictor/mihonwt/releases/download/models-baberu-b9868433/vision_int8.onnx"

        private val FILES = mapOf(
            VISION to 87_667_320L,
            "onnx/decoder_prefill_int8.onnx" to 35_133_596L,
            "onnx/decoder_step_int8.onnx" to 34_715_466L,
            "tokenizer/vocab.json" to 130_761L,
        )

        /** Whether its models are on the device, so loading it won't download them. */
        fun isDownloaded(context: Context) = FILES.all { (path, size) -> file(context, path).length() == size }

        /** Loads the model, first downloading its files if they aren't on the device yet. */
        suspend fun load(context: Context): BaberuOcr = withContext(Dispatchers.IO) {
            // Downloaded by the version that read with it
            file(context, "onnx/vision_int4.onnx").delete()
            FILES.forEach { (path, size) ->
                val file = file(context, path)
                if (file.length() != size) download(path, size, file)
            }
            val environment = OrtEnvironment.getEnvironment()

            // Without XNNPACK, as its decoder's inputs grow with every letter, like the PP-OCRv6 lines that crashed it
            fun session(path: String) = environment.createSession(
                file(context, path).path,
                ortSessionOptions(xnnpack = false).apply { setIntraOpNumThreads(fastThreads()) },
            )
            val characters = Json.decodeFromString<List<String>>(file(context, "tokenizer/vocab.json").readText())
            BaberuOcr(
                session(VISION),
                session("onnx/decoder_prefill_int8.onnx"),
                session("onnx/decoder_step_int8.onnx"),
                characters,
            )
        }

        private fun file(context: Context, path: String) =
            File(context.noBackupFilesDir, "baberu-${REVISION.take(8)}/$path")

        private suspend fun download(path: String, size: Long, file: File) {
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".part")
            val url = if (path ==
                VISION
            ) {
                VISION_URL
            } else {
                "https://huggingface.co/genshiai-daichi/baberu-ocr/resolve/$REVISION/$path"
            }
            OkHttpClient().newCall(Request.Builder().url(url).build()).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                partial.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            if (partial.length() != size) {
                partial.delete()
                throw IOException("incomplete download")
            }
            if (!partial.renameTo(file)) throw IOException("could not save $path")
        }

        private fun pixelValues(image: Bitmap): FloatArray {
            val scaled = image.scale(SIZE, SIZE)
            val pixels = IntArray(SIZE * SIZE)
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            if (scaled !== image) scaled.recycle()
            val plane = SIZE * SIZE
            return FloatArray(3 * plane).also { values ->
                for (i in 0..<plane) {
                    val color = pixels[i]
                    for (channel in 0..2) {
                        val byte = color shr (16 - 8 * channel) and 0xFF
                        values[channel * plane + i] = (byte / 255f - MEAN[channel]) / STD[channel]
                    }
                }
            }
        }

        private fun longTensor(env: OrtEnvironment, value: Long) =
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value)), longArrayOf(1, 1))

        private fun isContent(char: Char) = char !in "ーｰ〜~" && when (Character.getType(char).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            -> true
            else -> false
        }
    }
}

/**
 * Returns the box of [boxes] to reread with Baberu for each of [blocks], by index: the one box the block's middle is
 * in, when no other block's middle is. Long one-line captions are left out, as Baberu reads them squashed into a
 * square and makes up words.
 */
internal fun boxesToReread(blocks: List<TranslationBlock>, boxes: List<TextBox>): Map<Int, TextBox> {
    val middles = blocks.map { (it.x + it.width / 2) to (it.y + it.height / 2) }
    fun TextBox.holds(middle: Pair<Float, Float>) = middle.first in left..right && middle.second in top..bottom
    return blocks.indices.mapNotNull { i ->
        val box = boxes.singleOrNull { it.holds(middles[i]) } ?: return@mapNotNull null
        if (box.right - box.left > (box.bottom - box.top) * MAX_BOX_ASPECT) return@mapNotNull null
        if (middles.count { box.holds(it) } != 1) return@mapNotNull null
        i to box
    }.toMap()
}

// Wider boxes were mostly misread on test chapters, narrower ones read better than ML Kit
private const val MAX_BOX_ASPECT = 3.5f
