package com.tomatoinsight.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tomatoinsight.data.ensureGuidesForAllDetections
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Talks to the live website API. `/validate` already runs the same Ultralytics
 * leaf model, disease model, and care text as the website.
 */
object WebsiteScanClient {
    private const val LEAF_MIN_CONFIDENCE = 0.50
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun scan(imageBytes: ByteArray): ValidationResponse {
        val payload = postFile("validate") { imageBytes.toLeafPart() }
        parseFlaskValidation(payload)?.let { return it }

        val leafHits = extractHits(payload)
        val bestLeaf = leafHits.maxByOrNull { it.second }
        val leafName = bestLeaf?.first
        val leafConfidence = bestLeaf?.second ?: 0.0
        val isTomato = leafName != null &&
            isTomatoLeafClass(leafName) &&
            leafConfidence >= LEAF_MIN_CONFIDENCE

        if (!isTomato) {
            val shown = "%.1f".format(leafConfidence * 100)
            return ValidationResponse(
                result = "NotTomato",
                confidence = leafConfidence,
                minConfidence = LEAF_MIN_CONFIDENCE,
                detectedClass = leafName,
                stage = "website",
                message = if (leafName.isNullOrBlank()) {
                    "No tomato leaf was detected in this image."
                } else {
                    "This photo was labeled “$leafName” at $shown%."
                },
                predictions = emptyList()
            )
        }

        return tomatoResponse(
            leafName = leafName,
            leafConfidence = leafConfidence,
            minConfidence = LEAF_MIN_CONFIDENCE,
            stage = "website",
            message = "Tomato leaf detected.",
            predictions = diseasePredictionsFrom(payload, imageBytes),
            recommendation = recommendationFrom(payload)
        )
    }

    private fun parseFlaskValidation(payload: JsonObject): ValidationResponse? {
        val result = payload.text("result") ?: return null
        if (result.equals("Error", ignoreCase = true)) {
            throw IOException(payload.text("message") ?: "Validation error.")
        }
        if (!result.equals("TomatoLeaf", ignoreCase = true) &&
            !result.equals("NotTomato", ignoreCase = true)
        ) {
            return null
        }
        val parsed = gson.fromJson(payload, ValidationResponse::class.java)
        val minConfidence = parsed.minConfidence ?: LEAF_MIN_CONFIDENCE
        return parsed.copy(minConfidence = minConfidence).ensureGuidesForAllDetections()
    }

    private suspend fun diseasePredictionsFrom(
        payload: JsonObject,
        imageBytes: ByteArray
    ): List<DiseasePrediction> {
        val fromValidate = extractHits(payload, includePredictions = true)
        if (fromValidate.any { barKeyOrNull(it.first) != null }) {
            return hitsToPredictions(fromValidate)
        }
        return runCatching {
            hitsToPredictions(extractHits(postFile("classify") { imageBytes.toLeafPart() }))
        }.getOrDefault(emptyList())
    }

    private suspend fun recommendationFrom(payload: JsonObject): CareResult? {
        payload.getAsJsonObject("ai_recommendations")?.let {
            return CareResult(
                recommendations = gson.fromJson(it, AiRecommendations::class.java),
                source = payload.text("recommendation_source"),
                model = payload.text("groq_model"),
                error = payload.text("ai_recommendation_error")
            )
        }
        return null
    }

    private suspend fun tomatoResponse(
        leafName: String?,
        leafConfidence: Double,
        minConfidence: Double,
        stage: String,
        message: String,
        predictions: List<DiseasePrediction>,
        recommendation: CareResult?
    ): ValidationResponse {
        val scores = barScores(predictions.map { (it.displayName ?: "") to (it.confidence ?: 0.0) })
        val mapped = if (predictions.isNotEmpty()) {
            predictions
        } else {
            DISEASE_DISPLAY_ORDER.map { name ->
                DiseasePrediction(
                    className = name,
                    confidence = (scores[barKey(name)] ?: 0.0) / 100.0
                )
            }
        }
        val top = mapped.maxByOrNull { it.confidence ?: 0.0 }
        val care = recommendation ?: runCatching { requestRecommendation(scores) }.getOrNull()
        return ValidationResponse(
            result = "TomatoLeaf",
            confidence = leafConfidence,
            minConfidence = minConfidence,
            detectedClass = leafName,
            stage = stage,
            message = message,
            predictions = mapped,
            topDisease = top?.displayName,
            topDiseaseConfidence = top?.confidence,
            diseaseClasses = DISEASE_DISPLAY_ORDER,
            aiRecommendations = care?.recommendations,
            aiRecommendationError = care?.error,
            recommendationSource = care?.source,
            groqModel = care?.model
        ).ensureGuidesForAllDetections()
    }

    private suspend fun postFile(
        route: String,
        part: () -> MultipartBody.Part
    ): JsonObject {
        val response = when (route) {
            "validate" -> ValidationRetrofitClient.instance.validateLeaf(part())
            else -> ValidationRetrofitClient.instance.classifyLeaf(part())
        }
        return readObject(response, route)
    }

    /** Uses the optional fourth model to refine only Early Blight vs Spotted Wilt. */
    suspend fun withSpecialistRefinement(
        imageBytes: ByteArray,
        response: ValidationResponse
    ): ValidationResponse {
        if (!response.confirmsTomatoLeaf()) return response
        val currentScores = response.allDiseaseScores.associate { score ->
            barKey(score.name) to score.percent.toDouble()
        }
        val leading = currentScores.maxByOrNull { it.value }?.key
        if (leading !in setOf("early_blight", "spotted_wilt")) return response

        return runCatching {
            val scoreBody = gson.toJson(currentScores).toRequestBody(jsonType)
            val http = ValidationRetrofitClient.instance.refineDisease(
                imageBytes.toLeafPart(),
                scoreBody
            )
            val payload = readObject(http, "refine-disease")
            if (payload.bool("applied") != true) return@runCatching response
            val refinedObject = payload.getAsJsonObject("scores") ?: return@runCatching response
            val refinedScores = DISEASE_DISPLAY_ORDER.associateWith { name ->
                refinedObject.get(barKey(name))?.asDouble?.coerceIn(0.0, 100.0) ?: 0.0
            }
            val predictions = DISEASE_DISPLAY_ORDER.map { name ->
                DiseasePrediction(className = name, confidence = refinedScores.getValue(name) / 100.0)
            }
            val top = predictions.maxByOrNull { it.confidence ?: 0.0 }
            val care = requestRecommendation(
                refinedScores.mapKeys { (name, _) -> barKey(name) }
            )
            response.copy(
                predictions = predictions,
                topDisease = top?.displayName,
                topDiseaseConfidence = top?.confidence,
                diseaseClasses = DISEASE_DISPLAY_ORDER,
                aiRecommendations = care.recommendations,
                aiRecommendationError = care.error,
                recommendationSource = care.source,
                groqModel = care.model,
                message = "Early Blight and Spotted Wilt were checked by the specialist model."
            ).ensureGuidesForAllDetections()
        }.getOrElse { response }
    }

    /**
     * Calls Render segmentation `/assess-severity` using the refined top disease class.
     * Does not use AI confidence. Falls back to Unable to Assess / Healthy 0%.
     */
    suspend fun withSegmentationSeverity(
        imageBytes: ByteArray,
        response: ValidationResponse
    ): ValidationResponse {
        if (!response.confirmsTomatoLeaf()) return response
        val top = response.allDiseaseScores.maxByOrNull { it.percent }?.takeIf { it.percent > 0 }
            ?: return response.copy(
                severityLabel = "Unable to Assess",
                affectedAreaPct = null,
                severityAssessable = false,
                severityReason = "no_top_disease"
            )
        val barKey = barKeyOrNull(top.name) ?: "early_blight"
        if (isHealthyDisease(top.name) || barKey == "healthy") {
            return runCatching { mergeSeverity(response, postSeverity(imageBytes, "healthy")) }
                .getOrElse {
                    response.copy(
                        severityLabel = "Healthy",
                        affectedAreaPct = 0.0,
                        severityAssessable = true,
                        severityReason = "classified_healthy_local_fallback"
                    )
                }
        }
        return runCatching { mergeSeverity(response, postSeverity(imageBytes, barKey)) }
            .getOrElse {
                response.copy(
                    severityLabel = "Unable to Assess",
                    affectedAreaPct = null,
                    severityAssessable = false,
                    severityReason = "severity_request_failed"
                )
            }
    }

    private data class SeverityFields(
        val severityLabel: String,
        val affectedAreaPct: Double?,
        val severityAssessable: Boolean,
        val severityReason: String?
    )

    private fun mergeSeverity(base: ValidationResponse, severity: SeverityFields): ValidationResponse =
        base.copy(
            severityLabel = severity.severityLabel,
            affectedAreaPct = severity.affectedAreaPct,
            severityAssessable = severity.severityAssessable,
            severityReason = severity.severityReason
        )

    private suspend fun postSeverity(imageBytes: ByteArray, diseaseKey: String): SeverityFields {
        val diseaseBody = diseaseKey.toRequestBody("text/plain".toMediaType())
        val http = ValidationRetrofitClient.instance.assessSeverity(
            imageBytes.toLeafPart(),
            diseaseBody,
            diseaseBody
        )
        val payload = readObject(http, "assess-severity")
        val label = payload.text("severity") ?: "Unable to Assess"
        val measurable = payload.bool("measurable") == true
        val area = payload.number("affected_area_pct")
        val isHealthy = diseaseKey == "healthy" || label.equals("Healthy", ignoreCase = true)
        val areaPct = when {
            isHealthy -> area?.coerceIn(0.0, 100.0) ?: 0.0
            measurable && area != null -> area.coerceIn(0.0, 100.0)
            else -> null
        }
        return SeverityFields(
            severityLabel = if (isHealthy) "Healthy" else label,
            affectedAreaPct = areaPct,
            severityAssessable = if (isHealthy) {
                true
            } else {
                measurable && !label.equals("Unable to Assess", ignoreCase = true)
            },
            severityReason = payload.text("reason")
        )
    }

    private suspend fun requestRecommendation(
        scores: Map<String, Double>,
        severityPct: Double? = null,
        severityLabel: String? = null
    ): CareResult {
        val requestMap = mutableMapOf<String, Any>("scores" to scores)
        if (severityPct != null && severityPct.isFinite() && severityPct >= 0) {
            requestMap["severity_pct"] = severityPct.coerceIn(0.0, 100.0)
        }
        if (!severityLabel.isNullOrBlank()) {
            requestMap["severity_label"] = severityLabel
        }
        val body = gson.toJson(requestMap).toRequestBody(jsonType)
        val response = ValidationRetrofitClient.instance.recommendCare(body)
        if (!response.isSuccessful) {
            val message = errorMessage(response.body(), response.errorBody()?.string(), response.code())
            return CareResult(error = message)
        }
        val payload = response.body()?.asObject()
            ?: return CareResult(error = "The website recommendation response was empty.")
        val detected = payload.getAsJsonArray("detected")
        val guideObjects = mutableListOf<JsonObject>()
        payload.getAsJsonArray("guides")?.forEach { item ->
            item?.asObject()?.let { guideObjects += it }
        }
        if (guideObjects.isEmpty()) {
            payload.getAsJsonObject("guide")?.let { guideObjects += it }
        }
        if (guideObjects.isEmpty()) {
            return CareResult(error = payload.text("message") ?: "No care recommendation was returned.")
        }
        val sections = guideObjects.mapIndexed { index, guide ->
            val matchPct = detected
                ?.takeIf { index in 0 until it.size() }
                ?.get(index)
                ?.asObject()
                ?.get("pct")
                ?.asDouble
                ?.let { value -> (value + 0.5).toInt().coerceIn(0, 100) }
            AiRecommendationSection(
                disease = guide.text("diseaseName"),
                confidencePercent = matchPct,
                urgency = guide.text("urgencyNote"),
                summary = guide.text("summary"),
                steps = guide.stringList("actionSteps"),
                prevention = guide.stringList("preventionTips"),
                isCriticalRemoval = guide.bool("isCriticalRemoval")
            )
        }
        val primary = sections.first()
        val combined = if (sections.size > 1) {
            "Care steps for every detection: " + sections.joinToString(" · ") { section ->
                val name = section.disease.orEmpty().ifBlank { "Condition" }
                val pct = section.confidencePercent
                if (pct != null && pct > 0) "$name ($pct%)" else name
            }
        } else {
            primary.summary
        }
        val groqUsed = payload.bool("groq_used") == true
        return CareResult(
            recommendations = AiRecommendations(
                primaryDisease = primary.disease,
                combinedNote = combined,
                sections = sections
            ),
            source = if (groqUsed) "groq" else "rules",
            model = payload.text("model")
        )
    }

    private fun readObject(response: retrofit2.Response<JsonElement>, route: String): JsonObject {
        if (!response.isSuccessful) {
            throw IOException(errorMessage(response.body(), response.errorBody()?.string(), response.code(), route))
        }
        return response.body()?.asObject()
            ?: throw IOException("The website $route response was empty.")
    }

    private fun errorMessage(
        body: JsonElement?,
        raw: String?,
        code: Int,
        route: String? = null
    ): String {
        val payload = body?.asObject()
        val message = payload?.text("message") ?: raw?.take(300)
        val where = route?.let { " /$it" }.orEmpty()
        return "Website$where HTTP $code${message?.let { ": $it" } ?: ""}"
    }

    private fun ByteArray.toLeafPart(): MultipartBody.Part {
        val body = toRequestBody("image/jpeg".toMediaType())
        return MultipartBody.Part.createFormData("file", "leaf_scan.jpg", body)
    }

    private fun extractHits(
        payload: JsonObject,
        includePredictions: Boolean = false
    ): List<Pair<String, Double>> {
        val hits = mutableListOf<Pair<String, Double>>()
        fun add(item: JsonObject) {
            val name = item.text("name") ?: item.text("class_name") ?: item.text("class") ?: item.text("label")
            if (name.isNullOrBlank()) return
            val confidence = item.number("confidence") ?: item.number("score") ?: 0.0
            hits += name to if (confidence > 1.0) confidence / 100.0 else confidence
        }
        payload.array("images").forEach { image ->
            image.asObject()?.array("results")?.forEach { it.asObject()?.let(::add) }
        }
        payload.array("results").forEach { it.asObject()?.let(::add) }
        if (includePredictions) {
            payload.array("predictions").forEach { it.asObject()?.let(::add) }
        }
        return hits
    }

    private fun hitsToPredictions(hits: List<Pair<String, Double>>): List<DiseasePrediction> {
        val scores = barScores(hits)
        return DISEASE_DISPLAY_ORDER.map { name ->
            DiseasePrediction(
                className = name,
                confidence = (scores[barKey(name)] ?: 0.0) / 100.0
            )
        }
    }

    private fun barScores(hits: List<Pair<String, Double>>): Map<String, Double> {
        val byKey = hits.associate { normalizeDiseaseKey(it.first) to it.second }
        fun best(vararg names: String): Double {
            var value = 0.0
            names.forEach { label ->
                val key = normalizeDiseaseKey(label)
                byKey[key]?.let { value = maxOf(value, it) }
                byKey.forEach { (candidate, confidence) ->
                    if (candidate.contains(key) || key.contains(candidate)) {
                        value = maxOf(value, confidence)
                    }
                }
            }
            return (kotlin.math.round(value * 1000.0) / 10.0).coerceIn(0.0, 100.0)
        }
        return mapOf(
            "healthy" to best("healthy"),
            "tlcv" to best("yellowleafcurlvirus", "yellow leaf curl virus", "tlcv", "yellow leaf curl"),
            "early_blight" to best("earlyblightvirus", "early blight", "earlyblight"),
            "spotted_wilt" to best("spottedwiltvirus", "spotted wilt", "spottedwilt", "tswv")
        )
    }

    private fun barKey(displayName: String): String = barKeyOrNull(displayName) ?: "spotted_wilt"

    private fun barKeyOrNull(displayName: String): String? {
        val key = normalizeDiseaseKey(displayName)
        return when {
            key.contains("healthy") -> "healthy"
            key.contains("yellowleafcurl") || key == "tlcv" -> "tlcv"
            key.contains("earlyblight") -> "early_blight"
            key.contains("spottedwilt") || key == "tswv" -> "spotted_wilt"
            else -> null
        }
    }

    private fun isTomatoLeafClass(name: String): Boolean {
        val key = normalizeDiseaseKey(name)
        if (
            key.isBlank() ||
            key.contains("nontomato") ||
            key.contains("nottomato") ||
            key.contains("nonleaf") ||
            key.contains("notleaf")
        ) {
            return false
        }
        return key == "tomatoleaf" || (key.contains("tomato") && key.contains("leaf"))
    }

    private fun JsonElement.asObject(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.text(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return value.asString.trim().ifBlank { null }
    }

    private fun JsonObject.number(key: String): Double? {
        val value = get(key) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asDouble }.getOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val value = get(key) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return null
        return value.asBoolean
    }

    private fun JsonObject.array(key: String): List<JsonElement> {
        val value = get(key) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.toList()
    }

    private fun JsonObject.stringList(key: String): List<String> =
        array(key).mapNotNull { item ->
            if (!item.isJsonPrimitive) null else item.asString.trim().ifBlank { null }
        }

    private data class CareResult(
        val recommendations: AiRecommendations? = null,
        val source: String? = null,
        val model: String? = null,
        val error: String? = null
    )
}
