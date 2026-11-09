package com.tomatoinsight.api

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class DiseasePrediction(
    @SerializedName("class")
    val className: String? = null,
    @SerializedName("class_name")
    val altClassName: String? = null,
    val label: String? = null,
    val confidence: Double? = null,
    val score: Double? = null
) {
    val displayName: String?
        get() = className ?: altClassName ?: label

    val confidencePercent: Int?
        get() {
            val value = confidence ?: score ?: return null
            val percent = if (value <= 1.0) value * 100.0 else value
            return percent.toInt().coerceIn(0, 100)
        }
}

data class AiRecommendationSection(
    val disease: String? = null,
    @SerializedName("confidence_percent")
    val confidencePercent: Int? = null,
    val urgency: String? = null,
    val summary: String? = null,
    val steps: List<String>? = null,
    val prevention: List<String>? = null,
    @SerializedName("is_critical_removal")
    val isCriticalRemoval: Boolean? = null
)

data class AiRecommendations(
    @SerializedName("primary_disease")
    val primaryDisease: String? = null,
    @SerializedName("combined_note")
    val combinedNote: String? = null,
    val sections: List<AiRecommendationSection>? = null
)

data class ValidationResponse(
    val result: String? = null,
    val confidence: Double? = null,
    @SerializedName("detected_class")
    val detectedClass: String? = null,
    val message: String? = null,
    val stage: String? = null,
    val predictions: List<DiseasePrediction>? = emptyList(),
    @SerializedName("detection_count")
    val detectionCount: Int? = null,
    @SerializedName("inference_mode")
    val inferenceMode: String? = null,
    @SerializedName("model_path")
    val modelPath: String? = null,
    @SerializedName("min_confidence")
    val minConfidence: Double? = null,
    @SerializedName("top_disease")
    val topDisease: String? = null,
    @SerializedName("top_disease_confidence")
    val topDiseaseConfidence: Double? = null,
    @SerializedName("disease_classes")
    val diseaseClasses: List<String>? = null,
    @SerializedName("disease_error")
    val diseaseError: String? = null,
    @SerializedName("disease_raw")
    val diseaseRaw: JsonElement? = null,
    @SerializedName("ai_recommendations")
    val aiRecommendations: AiRecommendations? = null,
    @SerializedName("ai_recommendation_error")
    val aiRecommendationError: String? = null,
    @SerializedName("ai_disclaimer")
    val aiDisclaimer: String? = null,
    @SerializedName("recommendation_source")
    val recommendationSource: String? = null,
    @SerializedName("groq_model")
    val groqModel: String? = null,
    /** OpenCV severity label — never derived from AI confidence. */
    @SerializedName("severity_label")
    val severityLabel: String? = null,
    /** Estimated affected leaf area % when measurable; 0 for Healthy. */
    @SerializedName("affected_area_pct")
    val affectedAreaPct: Double? = null,
    @SerializedName("severity_assessable")
    val severityAssessable: Boolean? = null,
    @SerializedName("severity_reason")
    val severityReason: String? = null
) {
    val hasAiRecommendations: Boolean
        get() = !aiRecommendations?.sections.isNullOrEmpty()

    val isTomatoLeaf: Boolean get() = result.equals("TomatoLeaf", ignoreCase = true)
    val isNotTomato: Boolean get() = result.equals("NotTomato", ignoreCase = true)

    /**
     * True when this photo was accepted as a tomato leaf.
     * A live scan must meet the Ultralytics confidence floor from the server.
     * A result already saved on the website or this phone has disease
     * scores and no separate leaf-detector value, so those scores are shown.
     */
    fun confirmsTomatoLeaf(): Boolean {
        if (!isTomatoLeaf) return false
        if (stage == "saved" || (confidence == null && hasDiseaseResult)) return true
        val floor = minConfidence ?: 0.50
        return (confidence ?: 0.0) >= floor
    }
    val isError: Boolean get() = result.equals("Error", ignoreCase = true)
    val hasDiseaseResult: Boolean get() = !topDisease.isNullOrBlank() || !predictions.isNullOrEmpty()

    val confidencePercent: Int?
        get() = confidence?.let { (it * 100).toInt() }

    val topDiseaseConfidencePercent: Int?
        get() = topDiseaseConfidence?.let { (it * 100).toInt() }

    val primaryDisease: DiseasePrediction?
        get() = predictions?.maxByOrNull { it.confidence ?: it.score ?: 0.0 }

    /** Highest-confidence disease class name from Ultralytics. */
    val topDiseaseName: String?
        get() = topDisease?.takeIf { it.isNotBlank() } ?: primaryDisease?.displayName

    /** Highest-confidence disease percentage (0–100). */
    val topDiseasePercent: Int?
        get() = topDiseaseConfidencePercent ?: primaryDisease?.confidencePercent

    val sortedPredictions: List<DiseasePrediction>
        get() = predictions
            ?.filter { !it.displayName.isNullOrBlank() && (it.confidence ?: it.score) != null }
            ?.sortedByDescending { it.confidence ?: it.score ?: 0.0 }
            ?: emptyList()

    val topTwoPredictions: List<DiseasePrediction>
        get() = sortedPredictions.take(2)

    val diseaseRawPretty: String?
        get() = diseaseRaw?.let {
            GsonBuilder().setPrettyPrinting().create().toJson(it)
        }

    /** Disease rows in a fixed order. Percentages change; positions do not. */
    val allDiseaseScores: List<DiseaseScore>
        get() {
            val scoreByKey = predictions.orEmpty().mapNotNull { prediction ->
                val name = prediction.displayName ?: return@mapNotNull null
                normalizeDiseaseKey(name) to prediction
            }.toMap()

            return DISEASE_DISPLAY_ORDER.map { className ->
                val key = normalizeDiseaseKey(className)
                val match = scoreByKey[key]
                    ?: scoreByKey.entries.firstOrNull { (candidateKey, _) ->
                        candidateKey.contains(key) || key.contains(candidateKey)
                    }?.value
                    ?: predictions?.firstOrNull { prediction ->
                        prediction.displayName?.equals(className, ignoreCase = true) == true
                    }

                DiseaseScore(
                    name = className,
                    abbreviation = diseaseAbbreviation(className),
                    percent = match?.confidencePercent ?: 0
                )
            }
        }

    /** Highest percentage first. Used to name the leading result, not to lay out the rows. */
    val allDiseaseScoresSorted: List<DiseaseScore>
        get() = allDiseaseScores.sortedByDescending { it.percent }
}

fun normalizeDiseaseKey(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]"), "")

data class DiseaseScore(
    val name: String,
    val abbreviation: String,
    val percent: Int
)

val DISEASE_DISPLAY_ORDER = listOf(
    "Healthy",
    "Yellow leaf curl virus",
    "Early blight",
    "Spotted wilt"
)

val DEFAULT_DISEASE_CLASSES = DISEASE_DISPLAY_ORDER

fun diseaseDisplayLabel(name: String): String = when {
    name.equals("Healthy", ignoreCase = true) -> "Healthy"
    name.contains("yellow leaf curl", ignoreCase = true) || name.equals("TLCV", ignoreCase = true) -> "TLCV"
    name.contains("early blight", ignoreCase = true) -> "Early Blight"
    name.contains("spotted wilt", ignoreCase = true) -> "Spotted Wilt"
    else -> name
}

fun List<DiseaseScore>.inDiseaseDisplayOrder(): List<DiseaseScore> {
    val byKey = associateBy { normalizeDiseaseKey(it.name) }
    return DISEASE_DISPLAY_ORDER.map { name ->
        val existing = byKey[normalizeDiseaseKey(name)]
        DiseaseScore(
            name = name,
            abbreviation = diseaseAbbreviation(name),
            percent = existing?.percent ?: 0
        )
    }
}

fun diseaseAbbreviation(name: String): String = when {
    name.equals("Healthy", ignoreCase = true) -> "Healthy"
    name.contains("yellow leaf curl", ignoreCase = true) -> "TLCV"
    name.contains("spotted wilt", ignoreCase = true) -> "TSWV"
    name.contains("early blight", ignoreCase = true) -> "EB"
    else -> name.split(" ").take(2).joinToString("") { it.first().uppercaseChar().toString() }
}

/**
 * Prefer an explicit OpenCV severity label. Never treat AI confidence as severity.
 * When [affectedAreaPct] is measurable, map provisional Mild/Moderate/Severe bands.
 */
fun diseaseSeverityLabel(
    name: String,
    affectedAreaPct: Double? = null,
    explicitLabel: String? = null
): String {
    if (!explicitLabel.isNullOrBlank()) return explicitLabel
    if (name.equals("Healthy", ignoreCase = true)) return "Healthy"
    val pct = affectedAreaPct
    if (pct == null || !pct.isFinite()) return "Unable to Assess"
    return when {
        pct >= 67.0 -> "Severe"
        pct >= 34.0 -> "Moderate"
        pct > 0.0 -> "Mild"
        else -> "Unable to Assess"
    }
}

/** @deprecated Prefer [diseaseSeverityLabel] with OpenCV extent, not confidence. */
fun diseaseSeverityLabel(name: String, confidencePercent: Int): String =
    diseaseSeverityLabel(name, affectedAreaPct = null, explicitLabel = null)

fun isHealthyDisease(name: String): Boolean =
    name.equals("Healthy", ignoreCase = true)

data class HealthResponse(
    val status: String? = null,
    val stage: String? = null,
    @SerializedName("workflow_url")
    val workflowUrl: String? = null,
    @SerializedName("api_key_configured")
    val apiKeyConfigured: Boolean? = null
)

interface ValidationApiService {
    @GET("health")
    suspend fun checkHealth(): HealthResponse

    @Multipart
    @POST("validate")
    suspend fun validateLeaf(
        @Part file: MultipartBody.Part
    ): Response<JsonElement>

    @Multipart
    @POST("classify")
    suspend fun classifyLeaf(
        @Part file: MultipartBody.Part
    ): Response<JsonElement>

    @POST("recommend")
    suspend fun recommendCare(
        @Body body: RequestBody
    ): Response<JsonElement>

    @Multipart
    @POST("assess-severity")
    suspend fun assessSeverity(
        @Part file: MultipartBody.Part,
        @Part("disease") disease: RequestBody,
        @Part("bar_key") barKey: RequestBody
    ): Response<JsonElement>
}
