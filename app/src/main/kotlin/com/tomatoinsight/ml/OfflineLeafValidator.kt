package com.tomatoinsight.ml

import android.content.Context
import android.graphics.Bitmap
import com.tomatoinsight.api.ValidationResponse
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * On-device tomato-leaf check.
 *
 * capturevalidation.tflite is an Ultralytics classifier:
 * input float32 [1, 3, 640, 640] (RGB, 0–1, channels first),
 * output float32 [1, 2] or [1, 3] after softmax.
 *
 * The file does not store class names. Indexes follow Ultralytics' sorted
 * training-folder order. Change [CLASS_NAMES] if a known tomato photo scores
 * highest on the wrong row.
 */
object OfflineLeafValidator {

    private const val MODEL_ASSET = "models/capturevalidation.tflite"
    private const val IMAGE_SIZE = 640
    private const val MIN_CONFIDENCE = 0.50f

    /** Ultralytics sorts training folders alphabetically: NonTomatoLeaf, TomatoLeaf. */
    private val TWO_CLASS_NAMES = listOf("NonTomatoLeaf", "TomatoLeaf")
    private val THREE_CLASS_NAMES = listOf("NonTomato", "Other", "TomatoLeaf")

    @Volatile
    private var interpreter: Interpreter? = null

    fun validate(context: Context, bitmap: Bitmap): ValidationResponse {
        val scores = synchronized(this) {
            val runner = interpreter ?: createInterpreter(context).also { interpreter = it }
            val classCount = runner.getOutputTensor(0).shape().last().coerceAtLeast(1)
            val output = Array(1) { FloatArray(classCount) }
            val imgsz = runner.inputImageSize(IMAGE_SIZE)
            runner.run(bitmapToNchw(bitmap, imgsz), output)
            output[0]
        }
        return toResponse(scores)
    }

    private fun classNamesFor(count: Int): List<String> = when (count) {
        2 -> TWO_CLASS_NAMES
        3 -> THREE_CLASS_NAMES
        else -> List(count) { index -> "Class $index" }
    }

    private fun createInterpreter(context: Context): Interpreter {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                val mapped = stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
                return Interpreter(mapped, Interpreter.Options().setNumThreads(4))
            }
        }
    }

    private fun toResponse(scores: FloatArray): ValidationResponse {
        val names = classNamesFor(scores.size)
        val rows = scores.mapIndexed { index, score ->
            val name = names.getOrElse(index) { "Class $index" }
            name to score.coerceIn(0f, 1f)
        }
        val tomato = rows.firstOrNull { (name, _) -> isTomatoClass(name) }
        val top = rows.maxByOrNull { it.second }
        val accepted = tomato != null &&
            top?.first == tomato.first &&
            tomato.second >= MIN_CONFIDENCE
        val breakdown = rows.mapIndexed { index, (name, score) ->
            "$index ${displayName(name)} — ${(score * 100).roundToInt()}%"
        }.joinToString("\n")
        val message = if (accepted) {
            "Tomato leaf detected on this phone (${(tomato!!.second * 100).roundToInt()}%). " +
                "Disease classification and care recommendations run when the internet is back.\n\n$breakdown"
        } else if (tomato != null && top?.first == tomato.first) {
            "This looks like a tomato leaf, but the score was ${(tomato.second * 100).roundToInt()}%, " +
                "below the 50% cutoff.\n\n$breakdown"
        } else {
            "This photo was not accepted as a tomato leaf.\n\n$breakdown"
        }
        return ValidationResponse(
            result = if (accepted) "TomatoLeaf" else "NotTomato",
            confidence = (if (accepted) tomato!!.second else top?.second ?: 0f).toDouble(),
            detectedClass = (if (accepted) tomato!!.first else top?.first),
            message = message,
            stage = "offline",
            inferenceMode = "tflite",
            modelPath = MODEL_ASSET,
            minConfidence = MIN_CONFIDENCE.toDouble()
        )
    }

    private fun isTomatoClass(name: String): Boolean {
        val key = name.lowercase()
        return key.contains("tomato") && !key.contains("not") && !key.contains("non")
    }

    private fun displayName(name: String): String = when (name) {
        "TomatoLeaf" -> "Tomato leaf"
        "NonTomato" -> "Not a tomato leaf"
        "Other" -> "Other"
        else -> name
    }
}
