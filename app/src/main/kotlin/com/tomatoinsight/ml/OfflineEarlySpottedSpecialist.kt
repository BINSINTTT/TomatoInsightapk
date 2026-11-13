package com.tomatoinsight.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tomatoinsight.api.DISEASE_DISPLAY_ORDER
import com.tomatoinsight.api.DiseasePrediction
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.api.normalizeDiseaseKey
import com.tomatoinsight.data.ensureGuidesForAllDetections
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

/** Optional offline fourth model: leaf, early_blight_symptom, spotted_wilt_symptom. */
object OfflineEarlySpottedSpecialist {
    private const val MODEL_ASSET = "models/early_spotted_specialist.tflite"
    private const val IMAGE_SIZE = 640
    private const val MIN_CONFIDENCE = 0.50
    private const val MIN_MARGIN = 0.10
    private const val SPECIALIST_WEIGHT = 0.65

    @Volatile private var interpreter: Interpreter? = null

    fun isAvailable(context: Context): Boolean = runCatching {
        context.assets.openFd(MODEL_ASSET).close()
        true
    }.getOrDefault(false)

    fun refine(context: Context, bitmap: Bitmap, response: ValidationResponse): ValidationResponse {
        val scores = response.allDiseaseScores.associate { normalizeDiseaseKey(it.name) to it.percent.toDouble() }
        val earlyKey = normalizeDiseaseKey("Early blight")
        val spottedKey = normalizeDiseaseKey("Spotted wilt")
        val leading = scores.maxByOrNull { it.value }?.key
        if (leading !in setOf(earlyKey, spottedKey) || !isAvailable(context)) return response

        return runCatching {
            val evidence = synchronized(this) {
                val runner = interpreter ?: createInterpreter(context).also { interpreter = it }
                inferPair(runner, bitmap)
            }
            val totalEvidence = evidence.first + evidence.second
            if (totalEvidence <= 0.0) return@runCatching response
            val earlyRelative = evidence.first / totalEvidence
            val spottedRelative = evidence.second / totalEvidence
            if (maxOf(evidence.first, evidence.second) < MIN_CONFIDENCE ||
                abs(earlyRelative - spottedRelative) < MIN_MARGIN
            ) return@runCatching response

            val earlyMain = scores[earlyKey] ?: 0.0
            val spottedMain = scores[spottedKey] ?: 0.0
            val pairTotal = (earlyMain + spottedMain).takeIf { it > 0 } ?: 100.0
            val fusedEarly = pairTotal * (
                (1.0 - SPECIALIST_WEIGHT) * earlyMain / pairTotal +
                    SPECIALIST_WEIGHT * earlyRelative
                )
            val fusedSpotted = pairTotal * (
                (1.0 - SPECIALIST_WEIGHT) * spottedMain / pairTotal +
                    SPECIALIST_WEIGHT * spottedRelative
                )
            val refined = scores.toMutableMap().apply {
                this[earlyKey] = fusedEarly
                this[spottedKey] = fusedSpotted
            }
            val predictions = DISEASE_DISPLAY_ORDER.map { name ->
                DiseasePrediction(
                    className = name,
                    confidence = ((refined[normalizeDiseaseKey(name)] ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                )
            }
            val top = predictions.maxByOrNull { it.confidence ?: 0.0 }
            response.copy(
                predictions = predictions,
                topDisease = top?.displayName,
                topDiseaseConfidence = top?.confidence,
                modelPath = listOfNotNull(response.modelPath, MODEL_ASSET).joinToString(" + "),
                message = "Early Blight and Spotted Wilt were checked on this phone."
            ).ensureGuidesForAllDetections()
        }.onFailure {
            Log.w("TomatoInsight", "Offline specialist model could not refine this scan", it)
        }.getOrDefault(response)
    }

    /** Returns maximum Early Blight and Spotted Wilt candidate confidences. */
    private fun inferPair(runner: Interpreter, bitmap: Bitmap): Pair<Double, Double> {
        val outputBuffers = mutableMapOf<Int, Any>()
        for (index in 0 until runner.outputTensorCount) {
            val tensor = runner.getOutputTensor(index)
            require(tensor.dataType() == DataType.FLOAT32) {
                "The offline specialist must be exported as float32 TFLite."
            }
            outputBuffers[index] = ByteBuffer.allocateDirect(tensor.numBytes())
                .order(ByteOrder.nativeOrder())
        }
        runner.runForMultipleInputsOutputs(arrayOf(runner.bitmapInput(bitmap, IMAGE_SIZE)), outputBuffers)
        val detectionIndex = (0 until runner.outputTensorCount).firstOrNull { index ->
            val shape = runner.getOutputTensor(index).shape()
            shape.size == 3 && minOf(shape[1], shape[2]) >= 7
        } ?: error("No YOLO segmentation detection output was found.")
        val shape = runner.getOutputTensor(detectionIndex).shape()
        val attributesFirst = shape[1] < shape[2]
        val attributes = if (attributesFirst) shape[1] else shape[2]
        val candidates = if (attributesFirst) shape[2] else shape[1]
        require(attributes >= 7) { "Expected at least 7 YOLO output attributes." }
        val buffer = outputBuffers.getValue(detectionIndex) as ByteBuffer
        buffer.rewind()
        val values = FloatArray(buffer.remaining() / 4)
        buffer.asFloatBuffer().get(values)
        fun value(candidate: Int, attribute: Int): Float = if (attributesFirst) {
            values[attribute * candidates + candidate]
        } else {
            values[candidate * attributes + attribute]
        }
        var early = 0f
        var spotted = 0f
        for (candidate in 0 until candidates) {
            early = maxOf(early, value(candidate, 5))
            spotted = maxOf(spotted, value(candidate, 6))
        }
        return early.coerceIn(0f, 1f).toDouble() to spotted.coerceIn(0f, 1f).toDouble()
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
