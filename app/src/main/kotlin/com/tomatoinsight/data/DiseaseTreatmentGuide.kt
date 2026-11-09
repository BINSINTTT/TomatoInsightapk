package com.tomatoinsight.data

import com.tomatoinsight.api.AiRecommendationSection
import com.tomatoinsight.api.AiRecommendations
import com.tomatoinsight.api.DISEASE_DISPLAY_ORDER
import com.tomatoinsight.api.DiseaseScore
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.api.diseaseSeverityLabel
import com.tomatoinsight.api.isHealthyDisease
import com.tomatoinsight.api.normalizeDiseaseKey

data class DiseaseTreatment(
    val diseaseName: String,
    val urgencyNote: String,
    val summary: String,
    val actionSteps: List<String>,
    val preventionTips: List<String> = emptyList()
)

/** Built-in care text for every score above 0%, highest first. */
fun treatmentsForDetectedScores(
    scores: List<DiseaseScore>,
    severityLabel: String? = null,
    affectedAreaPct: Double? = null
): List<DiseaseTreatment> =
    scores
        .filter { it.percent > 0 }
        .sortedByDescending { it.percent }
        .mapIndexed { index, score ->
            treatmentForTopDisease(
                diseaseName = score.name,
                confidencePercent = score.percent,
                severityLabel = if (index == 0) severityLabel else null,
                affectedAreaPct = if (index == 0) affectedAreaPct else null
            )
        }

fun treatmentForTopDisease(
    diseaseName: String,
    confidencePercent: Int,
    severityLabel: String? = null,
    affectedAreaPct: Double? = null
): DiseaseTreatment {
    val severity = diseaseSeverityLabel(
        name = diseaseName,
        affectedAreaPct = affectedAreaPct,
        explicitLabel = severityLabel
    )
    val key = normalizeDiseaseKey(diseaseName)
    val extentPct = affectedAreaPct?.let { kotlin.math.round(it).toInt().coerceIn(0, 100) }

    val base = when {
        key.contains("earlyblight") || key == "eb" -> earlyBlightTreatment()
        key.contains("spottedwilt") || key.contains("tswv") -> spottedWiltTreatment()
        key.contains("yellowleafcurl") || key.contains("tlcv") -> yellowLeafCurlTreatment()
        isHealthyDisease(diseaseName) -> healthyPlantCare()
        else -> genericDiseaseTreatment(diseaseName)
    }

    val critical =
        !isHealthyDisease(diseaseName) &&
            severity != "Unable to Assess" &&
            (extentPct ?: -1) >= 100
    val urgency = when {
        isHealthyDisease(diseaseName) && severity != "Unable to Assess" ->
            "Keep up regular monitoring to catch problems early."
        severity == "Unable to Assess" ->
            "Severity could not be measured from this image. Confirm symptoms on the plant before treating; follow disease-specific steps cautiously."
        critical ->
            "Critical (severity 100%): remove this leaf immediately and destroy it away from the farm. Do not compost."
        severity == "Severe" ->
            "Urgent (estimated extent ${extentPct ?: "—"}%): start treatment within 24–48 hours."
        severity == "Moderate" ->
            "Moderate (estimated extent ${extentPct ?: "—"}%): treat this week before it spreads."
        severity == "Mild" ->
            "Mild (estimated extent ${extentPct ?: "—"}%): monitor closely and treat if symptoms worsen."
        else ->
            "Confirm symptoms on the plant before treating."
    }
    val steps = if (critical) {
        listOf(
            "Remove this leaf immediately; bag it and dispose of it away from healthy plants (do not compost)."
        ) + base.actionSteps
    } else {
        base.actionSteps
    }

    return base.copy(
        diseaseName = diseaseName,
        urgencyNote = urgency,
        actionSteps = steps
    )
}

private fun earlyBlightTreatment() = DiseaseTreatment(
    diseaseName = "Early blight",
    urgencyNote = "",
    summary = "Early blight (Alternaria) causes brown spots with rings on lower leaves and can defoliate plants.",
    actionSteps = listOf(
        "Remove and destroy infected leaves; do not compost them.",
        "Improve airflow by spacing plants and pruning lower foliage.",
        "Avoid overhead watering; water at the base in the morning.",
        "Apply a copper-based or chlorothalonil fungicide per label directions.",
        "Rotate tomatoes to a new area next season; do not replant in the same spot."
    ),
    preventionTips = listOf(
        "Mulch around plants to reduce soil splash onto leaves.",
        "Inspect lower leaves weekly during humid weather."
    )
)

private fun spottedWiltTreatment() = DiseaseTreatment(
    diseaseName = "Spotted wilt",
    urgencyNote = "",
    summary = "Tomato spotted wilt virus (TSWV) is spread by thrips and causes bronze spots, rings, and stunted growth.",
    actionSteps = listOf(
        "Remove and destroy infected plants immediately to limit spread.",
        "Control thrips with insecticidal soap or approved products; focus on undersides of leaves.",
        "Use reflective mulch early in the season to repel thrips.",
        "Remove weeds near the farm that host thrips and the virus.",
        "Do not replant tomatoes in the same bed without thrips management."    
    ),
    preventionTips = listOf(
        "Use TSWV-resistant tomato varieties when available.",
        "Install fine mesh row covers until plants flower, if practical."
    )
)

private fun yellowLeafCurlTreatment() = DiseaseTreatment(
    diseaseName = "Yellow leaf curl virus",
    urgencyNote = "",
    summary = "Tomato yellow leaf curl virus (TYLCV) causes upward curling, yellowing, and reduced fruit set; whiteflies spread it.",
    actionSteps = listOf(
        "Remove severely infected plants to protect nearby healthy ones.",
        "Control whiteflies with yellow sticky traps and approved insecticides.",
        "Spray undersides of leaves where whiteflies feed; repeat as directed.",
        "Remove solanaceous weeds (tomato relatives) around the field.",
        "Avoid bringing new plants from unknown sources into the farm."
    ),
    preventionTips = listOf(
        "Plant TYLCV-tolerant varieties where the virus is common.",
        "Inspect new growth twice weekly for curling and whitefly activity."
    )
)

private fun healthyPlantCare() = DiseaseTreatment(
    diseaseName = "Healthy",
    urgencyNote = "",
    summary = "This leaf looks healthy. Continue good practices to keep plants productive.",
    actionSteps = listOf(
        "Maintain even watering; avoid long dry periods followed by heavy soaking.",
        "Feed with balanced fertilizer according to plant stage.",
        "Stake or trellis plants to keep foliage off the soil.",
        "Scan other tiles regularly so problems are caught early."
    ),
    preventionTips = listOf(
        "Remove yellowing lower leaves to improve airflow.",
        "Sanitize tools if you work on a diseased plant elsewhere."
    )
)

private fun genericDiseaseTreatment(name: String) = DiseaseTreatment(
    diseaseName = name,
    urgencyNote = "",
    summary = "A plant disease was detected. Isolate the affected plant and confirm symptoms before treating.",
    actionSteps = listOf(
        "Photograph symptoms and note which leaves are affected.",
        "Remove heavily damaged tissue and dispose of it away from the farm.",
        "Avoid working wet plants to reduce spread.",
        "Consult a local agricultural extension office for a confirmed diagnosis."
    )
)

/** One care guide for a single detected class (matches website `guides[]`). */
data class SavedGuide(
    val diseaseName: String = "",
    val confidencePercent: Int = 0,
    val urgencyNote: String = "",
    val summary: String = "",
    val actionSteps: List<String> = emptyList(),
    val preventionTips: List<String> = emptyList(),
    val isCriticalRemoval: Boolean = false
)

/** Care text stored on users/{uid}.plantNames.{tile}.recommendation. */
data class SavedRecommendation(
    val diseaseName: String = "",
    val urgencyNote: String = "",
    val summary: String = "",
    val actionSteps: List<String> = emptyList(),
    val preventionTips: List<String> = emptyList(),
    val isCriticalRemoval: Boolean = false,
    /** Every detection above 0%, same shape as the website recommendation guides. */
    val guides: List<SavedGuide> = emptyList()
) {
    fun toFirestore(
        topBarKey: String = "",
        topPct: Double? = null,
        weekNumber: Int? = null,
        source: String = "",
        savedAt: String = "",
        scores: Map<String, Any> = emptyMap()
    ): Map<String, Any> {
        val record = linkedMapOf<String, Any>(
            "urgencyNote" to urgencyNote.take(400),
            "summary" to summary.take(800),
            "actionSteps" to actionSteps.take(12),
            "isCriticalRemoval" to isCriticalRemoval
        )
        if (barKeyForDiseaseName(diseaseName) != BAR_HEALTHY && diseaseName.isNotBlank()) {
            record["diseaseName"] = diseaseName.take(80)
        }
        if (topBarKey.isNotBlank()) record["topBarKey"] = topBarKey
        if (topPct != null && topPct > 0.0) record["topPct"] = topPct
        if (weekNumber != null) record["weekNumber"] = weekNumber
        if (preventionTips.isNotEmpty()) record["preventionTips"] = preventionTips.take(8)
        if (source == "groq" || source == "rules") record["source"] = source
        if (savedAt.isNotBlank()) record["savedAt"] = savedAt
        if (scores.isNotEmpty()) record["scores"] = scores
        if (guides.isNotEmpty()) {
            record["guides"] = guides.map { guide ->
                linkedMapOf<String, Any>(
                    "diseaseName" to guide.diseaseName.take(80),
                    "confidencePercent" to guide.confidencePercent.coerceIn(0, 100),
                    "urgencyNote" to guide.urgencyNote.take(400),
                    "summary" to guide.summary.take(800),
                    "actionSteps" to guide.actionSteps.take(12),
                    "preventionTips" to guide.preventionTips.take(8),
                    "isCriticalRemoval" to guide.isCriticalRemoval
                )
            }
        }
        return record
    }
}

/**
 * Same rule as the website: every classification bar above 0% gets its own care guide.
 * Keeps Groq/server wording when present; fills missing detections with built-in rules.
 */
fun ValidationResponse.ensureGuidesForAllDetections(): ValidationResponse {
    val detected = allDiseaseScores
        .filter { it.percent > 0 }
        .sortedWith(
            compareByDescending<DiseaseScore> { it.percent }
                .thenBy { score ->
                    DISEASE_DISPLAY_ORDER.indexOfFirst {
                        normalizeDiseaseKey(it) == normalizeDiseaseKey(score.name)
                    }.let { if (it < 0) Int.MAX_VALUE else it }
                }
        )
    if (detected.isEmpty()) return this

    fun sectionMatches(section: AiRecommendationSection, score: DiseaseScore): Boolean {
        val a = normalizeDiseaseKey(section.disease.orEmpty())
        val b = normalizeDiseaseKey(score.name)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    val existing = aiRecommendations?.sections.orEmpty()
    val sections = detected.mapIndexed { index, score ->
        val fromServer = existing.firstOrNull { sectionMatches(it, score) }
        if (fromServer != null) {
            fromServer.copy(
                disease = fromServer.disease?.ifBlank { score.name } ?: score.name,
                confidencePercent = score.percent
            )
        } else {
            treatmentForTopDisease(
                diseaseName = score.name,
                confidencePercent = score.percent,
                severityLabel = if (index == 0) severityLabel else null,
                affectedAreaPct = if (index == 0) affectedAreaPct else null
            ).toAiSection(score.percent)
        }
    }
    val combined = if (sections.size > 1) {
        "Care steps for every detection: " + sections.joinToString(" · ") { section ->
            val name = section.disease.orEmpty().ifBlank { "Condition" }
            val pct = section.confidencePercent
            if (pct != null && pct > 0) "$name ($pct%)" else name
        }
    } else {
        sections.firstOrNull()?.summary ?: aiRecommendations?.combinedNote
    }
    return copy(
        aiRecommendations = AiRecommendations(
            primaryDisease = sections.firstOrNull()?.disease ?: aiRecommendations?.primaryDisease,
            combinedNote = combined,
            sections = sections
        ),
        recommendationSource = recommendationSource ?: "rules"
    )
}

private fun DiseaseTreatment.toAiSection(percent: Int): AiRecommendationSection =
    AiRecommendationSection(
        disease = diseaseName,
        confidencePercent = percent,
        urgency = urgencyNote,
        summary = summary,
        steps = actionSteps,
        prevention = preventionTips,
        isCriticalRemoval = !isHealthyDisease(diseaseName) && percent >= 100
    )

fun List<DiseaseScore>.recommendationScoresKey(): String =
    MONITORING_BAR_KEYS.joinToString("|") { key ->
        val percent = firstOrNull { barKeyForDiseaseName(it.name) == key }?.percent ?: 0
        "$key:$percent"
    }

/** Care guide returned by the server for this scan, in the shape stored on plantNames. */
fun ValidationResponse.asSavedRecommendation(): SavedRecommendation? {
    val filled = ensureGuidesForAllDetections()
    val top = filled.allDiseaseScores.maxByOrNull { it.percent } ?: return null
    if (top.percent <= 0) return null
    val sections = filled.aiRecommendations?.sections.orEmpty().filter { section ->
        !section.summary.isNullOrBlank() || !section.steps.isNullOrEmpty()
    }
    if (sections.isEmpty()) return null
    val section = sections.firstOrNull { item ->
        val disease = item.disease.orEmpty()
        disease.isNotBlank() && (
            disease.equals(top.name, ignoreCase = true) ||
                normalizeDiseaseKey(disease) == normalizeDiseaseKey(top.name)
            )
    } ?: sections.first()
    val guides = sections.map { item ->
        SavedGuide(
            diseaseName = item.disease?.trim().orEmpty().ifBlank { top.name },
            confidencePercent = item.confidencePercent ?: 0,
            urgencyNote = item.urgency?.trim().orEmpty(),
            summary = item.summary?.trim().orEmpty(),
            actionSteps = item.steps.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.take(12),
            preventionTips = item.prevention.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.take(8),
            isCriticalRemoval = item.isCriticalRemoval
                ?: (!isHealthyDisease(item.disease.orEmpty()) && (item.confidencePercent ?: 0) >= 100)
        )
    }
    val critical = section.isCriticalRemoval
        ?: (!isHealthyDisease(top.name) && top.percent >= 100)
    val summary = when {
        sections.size > 1 -> {
            filled.aiRecommendations?.combinedNote?.trim()?.ifBlank { null }
                ?: sections.joinToString(" · ") { item ->
                    val label = item.disease?.trim().orEmpty().ifBlank { "Condition" }
                    val pct = item.confidencePercent?.takeIf { it > 0 }?.let { " ($it%)" }.orEmpty()
                    "$label$pct"
                }
        }
        else -> listOfNotNull(
            filled.aiRecommendations?.combinedNote?.trim()?.ifBlank { null },
            section.summary?.trim()
        ).distinct().joinToString("\n\n")
    }
    return SavedRecommendation(
        diseaseName = section.disease?.trim().orEmpty().ifBlank { top.name },
        urgencyNote = section.urgency?.trim().orEmpty(),
        summary = summary,
        actionSteps = section.steps.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.take(12),
        preventionTips = section.prevention.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.take(8),
        isCriticalRemoval = critical,
        guides = guides
    )
}

/** Rebuild multi-detection AI sections from a saved website-style recommendation. */
fun SavedRecommendation.asAiRecommendations(): AiRecommendations? {
    val sections = when {
        guides.isNotEmpty() -> guides.map { guide ->
            AiRecommendationSection(
                disease = guide.diseaseName,
                confidencePercent = guide.confidencePercent,
                urgency = guide.urgencyNote,
                summary = guide.summary,
                steps = guide.actionSteps,
                prevention = guide.preventionTips,
                isCriticalRemoval = guide.isCriticalRemoval
            )
        }
        summary.isNotBlank() || actionSteps.isNotEmpty() -> listOf(
            AiRecommendationSection(
                disease = diseaseName,
                confidencePercent = null,
                urgency = urgencyNote,
                summary = summary,
                steps = actionSteps,
                prevention = preventionTips,
                isCriticalRemoval = isCriticalRemoval
            )
        )
        else -> emptyList()
    }
    if (sections.isEmpty()) return null
    return AiRecommendations(
        primaryDisease = sections.first().disease ?: diseaseName,
        combinedNote = if (sections.size > 1) {
            "Care steps for every detection: " + sections.joinToString(" · ") { section ->
                val name = section.disease.orEmpty().ifBlank { "Condition" }
                val pct = section.confidencePercent
                if (pct != null && pct > 0) "$name ($pct%)" else name
            }
        } else {
            summary.ifBlank { sections.first().summary }
        },
        sections = sections
    )
}
