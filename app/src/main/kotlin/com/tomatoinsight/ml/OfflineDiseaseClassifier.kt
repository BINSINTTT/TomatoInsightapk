package com.tomatoinsight.ml

import android.content.Context
import com.tomatoinsight.api.DiseasePrediction
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.data.ensureGuidesForAllDetections
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * On-device disease check. Runs only after the leaf validator accepts the photo.
 *
 * tomatoinsightdiseaseclassification.tflite is an Ultralytics classifier:
 * input float32 [1, 3, 640, 640], output float32 [1, 4] after softmax.
 *
 * Class indexes follow Ultralytics' alphabetical training-folder order.
 * Change [CLASS_NAMES] if a known disease scores on the wrong row.
 */
object OfflineDiseaseClassifier {

    private const val MODEL_ASSET = "models/tomatoinsightdiseaseclassification.tflite"
    private const val IMAGE_SIZE = 640

    private val CLASS_NAMES = listOf(
        "Early blight",
        "Healthy",
        "Spotted wilt",
        "Yellow leaf curl virus"
    )

    @Volatile
    private var interpreter: Interpreter? = null

    fun classify(context: Context, bitmap: android.graphics.Bitmap, leaf: ValidationResponse): ValidationResponse {
        val scores = synchronized(this) {
            val runner = interpreter ?: createInterpreter(context).also { interpreter = it }
            val classCount = runner.getOutputTensor(0).shape().last().coerceAtLeast(1)
            val output = Array(1) { FloatArray(classCount) }
            val imgsz = runner.inputImageSize(IMAGE_SIZE)
            runner.run(bitmapToNchw(bitmap, imgsz), output)
            output[0]
        }
        val predictions = scores.mapIndexed { index, score ->
            DiseasePrediction(
                className = CLASS_NAMES.getOrElse(index) { "Class $index" },
                confidence = score.coerceIn(0f, 1f).toDouble()
            )
        }
        val top = predictions.maxByOrNull { it.confidence ?: 0.0 }
        val keptOnlineCare = leaf.aiRecommendations != null
        return leaf.copy(
            predictions = predictions,
            topDisease = top?.displayName,
            topDiseaseConfidence = top?.confidence,
            diseaseClasses = predictions.mapNotNull { it.displayName },
            // Keep website/offline stage from the leaf check; disease scores always come from TFLite.
            inferenceMode = "tflite",
            modelPath = MODEL_ASSET,
            message = if (keptOnlineCare) {
                leaf.message ?: "Tomato leaf detected."
            } else {
                "Classified on this phone. Care tips below cover every detected score; online AI can refine them."
            },
            aiRecommendations = leaf.aiRecommendations,
            aiRecommendationError = leaf.aiRecommendationError,
            recommendationSource = leaf.recommendationSource ?: "rules"
        ).ensureGuidesForAllDetections()
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
}
