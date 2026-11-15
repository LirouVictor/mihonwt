package eu.kanade.translation.recognizer

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Finds the text of speech bubbles and captions on a page with ogkalu's comic text and bubble detector, an RT-DETR-v2
 * model trained on manga, webtoons, manhua and western comics, under the Apache License 2.0.
 */
class BubbleDetector private constructor(private val session: OrtSession) : Closeable {

    /** Returns the boxes around the text of bubbles and captions on [page], at its own coordinates. */
    fun detect(page: Bitmap): List<TextBox> {
        val env = OrtEnvironment.getEnvironment()
        // The model takes the page stretched to a square and gives boxes back at the size it is told
        val input = page.scale(INPUT_SIZE, INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        input.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (input !== page) input.recycle()
        val plane = INPUT_SIZE * INPUT_SIZE
        val image = FloatBuffer.allocate(3 * plane)
        pixels.forEachIndexed { i, color ->
            image.put(i, (color shr 16 and 0xFF) / 255f)
            image.put(plane + i, (color shr 8 and 0xFF) / 255f)
            image.put(2 * plane + i, (color and 0xFF) / 255f)
        }

        val sizeShape = longArrayOf(1, 2)
        val sizeType = (session.inputInfo.getValue(SIZES_INPUT).info as TensorInfo).type
        val sizes = if (sizeType == OnnxJavaType.INT64) {
            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(page.width.toLong(), page.height.toLong())),
                sizeShape,
            )
        } else {
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(page.width, page.height)), sizeShape)
        }
        return OnnxTensor.createTensor(env, image, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())).use {
            sizes.use { _ ->
                session.run(mapOf(IMAGES_INPUT to it, SIZES_INPUT to sizes)).use { result ->
                    val labels = when (val value = result.get("labels").get().value) {
                        is Array<*> -> when (val first = value.first()) {
                            is LongArray -> first
                            is IntArray -> LongArray(first.size) { i -> first[i].toLong() }
                            else -> throw IOException("Unexpected detector labels")
                        }
                        else -> throw IOException("Unexpected detector labels")
                    }

                    @Suppress("UNCHECKED_CAST")
                    val boxes = (result.get("boxes").get().value as Array<Array<FloatArray>>).first()
                    val scores = (result.get("scores").get().value as Array<FloatArray>).first()
                    textBoxes(labels, boxes, scores)
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val INPUT_SIZE = 640
        private const val IMAGES_INPUT = "images"
        private const val SIZES_INPUT = "orig_target_sizes"

        // Pinned to a revision, so the file checked by its size is the one the code was written for
        private const val MODEL_URL = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/" +
            "16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx"
        private const val MODEL_SIZE = 11_120_765L
        private const val MODEL_FILE = "bubble_detector_v4_s_int8.onnx"

        // Phones pair a few fast cores with slower ones, which would hold the fast ones back
        private const val XNNPACK_MAX_THREADS = 4

        /** Loads the detector, first downloading its model of about 11 MB if it isn't on the device yet. */
        suspend fun load(context: Context): BubbleDetector = withContext(Dispatchers.IO) {
            val model = File(context.noBackupFilesDir, MODEL_FILE)
            if (model.length() != MODEL_SIZE) download(model)
            val environment = OrtEnvironment.getEnvironment()
            // XNNPACK runs the model's convolutions with ARM's vector instructions; the rest stays on the default CPU
            // provider, which also takes over when XNNPACK can't load
            val session = try {
                environment.createSession(model.path, sessionOptions(xnnpack = true))
            } catch (e: OrtException) {
                logcat(LogPriority.WARN, e)
                environment.createSession(model.path, sessionOptions(xnnpack = false))
            }
            BubbleDetector(session)
        }

        private fun sessionOptions(xnnpack: Boolean) = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (xnnpack) {
                val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(XNNPACK_MAX_THREADS)
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }
        }

        private suspend fun download(model: File) {
            val partial = File(model.path + ".part")
            OkHttpClient().newCall(Request.Builder().url(MODEL_URL).build()).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                partial.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            if (partial.length() != MODEL_SIZE) {
                partial.delete()
                throw IOException("incomplete download")
            }
            if (!partial.renameTo(model)) throw IOException("could not save the model")
        }
    }
}
