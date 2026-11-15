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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Finds the text of speech bubbles and captions on a page with ogkalu's comic text and bubble detector, an RT-DETR-v2
 * model trained on manga, webtoons, manhua and western comics, under the Apache License 2.0. The app carries its
 * detector-v4-s_int8.onnx (revision 16e8a622f91fabc6b5b65c96d32d1183f8843546) turned back into float weights, which
 * XNNPACK can run and the integer operators of the original couldn't use.
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
        val image = FloatArray(3 * plane)
        for (i in 0..<plane) {
            val color = pixels[i]
            image[i] = (color shr 16 and 0xFF) * BYTE_TO_UNIT
            image[plane + i] = (color shr 8 and 0xFF) * BYTE_TO_UNIT
            image[2 * plane + i] = (color and 0xFF) * BYTE_TO_UNIT
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
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(image),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        ).use {
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
        private const val BYTE_TO_UNIT = 1 / 255f
        private const val IMAGES_INPUT = "images"
        private const val SIZES_INPUT = "orig_target_sizes"

        private const val MODEL_ASSET = "detector-v4-s_fp32.onnx"

        // Downloaded by versions before the model came with the app
        private const val OLD_MODEL_FILE = "bubble_detector_v4_s_int8.onnx"

        // Phones pair a few fast cores with slower ones, which would hold the fast ones back
        private const val XNNPACK_MAX_THREADS = 4

        /** Loads the detector from the model that comes with the app. */
        suspend fun load(context: Context): BubbleDetector = withContext(Dispatchers.IO) {
            File(context.noBackupFilesDir, OLD_MODEL_FILE).delete()
            val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            // XNNPACK runs the model's convolutions with ARM's vector instructions; the rest stays on the default CPU
            // provider, which also takes over when XNNPACK can't load
            val session = try {
                environment.createSession(model, sessionOptions(xnnpack = true))
            } catch (e: OrtException) {
                logcat(LogPriority.WARN, e)
                environment.createSession(model, sessionOptions(xnnpack = false))
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
    }
}
