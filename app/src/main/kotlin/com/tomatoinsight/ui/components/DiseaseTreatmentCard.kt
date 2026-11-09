package com.tomatoinsight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatoinsight.api.AiRecommendationSection
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.api.isHealthyDisease
import com.tomatoinsight.data.DiseaseTreatment
import com.tomatoinsight.data.SavedRecommendation
import com.tomatoinsight.data.asAiRecommendations
import com.tomatoinsight.data.ensureGuidesForAllDetections
import com.tomatoinsight.data.treatmentsForDetectedScores
import com.tomatoinsight.ui.theme.TomatoGreen
import com.tomatoinsight.ui.theme.TomatoRed

@Composable
fun DiseaseTreatmentCard(
    result: ValidationResponse,
    savedRecommendation: SavedRecommendation? = null
) {
    // Same as the website: every score above 0% gets its own care section.
    val withAllGuides = result.ensureGuidesForAllDetections()
    if (withAllGuides.hasAiRecommendations) {
        GroqTreatmentCard(withAllGuides)
        return
    }
    val detected = treatmentsForDetectedScores(
        scores = result.allDiseaseScores,
        severityLabel = result.severityLabel,
        affectedAreaPct = result.affectedAreaPct
    )
    if (detected.isNotEmpty()) {
        OfflineTreatmentCard(result, detected)
        return
    }
    val saved = savedRecommendation?.takeIf {
        it.summary.isNotBlank() || it.actionSteps.isNotEmpty() || it.guides.isNotEmpty()
    }
    if (saved != null) {
        val fromSaved = saved.asAiRecommendations()
        if (fromSaved != null) {
            GroqTreatmentCard(
                result.copy(
                    aiRecommendations = fromSaved,
                    recommendationSource = "rules"
                )
            )
        } else {
            SavedRecommendationCard(saved)
        }
    }
}

@Composable
private fun SavedRecommendationCard(guide: SavedRecommendation) {
    val isHealthy = isHealthyDisease(guide.diseaseName)
    val accent = if (guide.isCriticalRemoval) TomatoRed else if (isHealthy) TomatoGreen else TomatoRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHealthy) Color(0xFFF1F8E9) else Color(0xFFFFF8E1)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            TreatmentCardHeader(isHealthy = isHealthy, accent = accent, sourceLabel = null)
            Spacer(modifier = Modifier.height(12.dp))
            if (guide.diseaseName.isNotBlank()) {
                Text(
                    text = guide.diseaseName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF212121)
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (guide.urgencyNote.isNotBlank()) {
                Text(
                    text = guide.urgencyNote,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (guide.summary.isNotBlank()) {
                Text(
                    text = guide.summary,
                    fontSize = 13.sp,
                    color = Color(0xFF424242),
                    lineHeight = 18.sp
                )
            }
            if (guide.actionSteps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (isHealthy) "What to do" else "Treatment steps",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF2E7D32)
                )
                Spacer(modifier = Modifier.height(8.dp))
                guide.actionSteps.forEachIndexed { index, step ->
                    TreatmentStepRow(index = index + 1, text = step)
                    if (index < guide.actionSteps.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            if (guide.preventionTips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                PreventionHeader()
                guide.preventionTips.forEach { tip ->
                    Text(
                        text = "• $tip",
                        fontSize = 13.sp,
                        color = Color(0xFF424242),
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Guidance only — verify on the plant before treating.",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E)
            )
        }
    }
}

@Composable
private fun GroqTreatmentCard(result: ValidationResponse) {
    val ai = result.aiRecommendations ?: return
    val sections = ai.sections.orEmpty()
    if (sections.isEmpty()) return

    val primaryName = ai.primaryDisease ?: sections.first().disease.orEmpty()
    val isHealthy = isHealthyDisease(primaryName) &&
        sections.none { !isHealthyDisease(it.disease.orEmpty()) && (it.confidencePercent ?: 0) >= 10 }
    val accent = if (isHealthy) TomatoGreen else TomatoRed

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHealthy) Color(0xFFF1F8E9) else Color(0xFFFFF8E1)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            TreatmentCardHeader(
                isHealthy = isHealthy,
                accent = accent,
                sourceLabel = when (result.recommendationSource) {
                    "groq" -> "Powered by Groq"
                    "rules" -> "Built-in care guide"
                    else -> "Care recommendations"
                }
            )

            if (!ai.combinedNote.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = ai.combinedNote,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF37474F),
                    lineHeight = 18.sp
                )
            }

            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFFE0E0E0))
                    Spacer(modifier = Modifier.height(16.dp))
                }
                AiRecommendationSectionBlock(section = section, accent = accent)
            }

            result.aiDisclaimer?.takeIf { it.isNotBlank() }?.let { disclaimer ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = disclaimer,
                    fontSize = 11.sp,
                    color = Color(0xFF757575),
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun AiRecommendationSectionBlock(
    section: AiRecommendationSection,
    accent: Color
) {
    val disease = section.disease.orEmpty()
    val percent = section.confidencePercent ?: 0
    val sectionHealthy = isHealthyDisease(disease)
    val sectionAccent = if (sectionHealthy) TomatoGreen else accent

    Text(
        text = buildString {
            append(disease.ifBlank { "Detected condition" })
            append(" ($percent%)")
        },
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        color = Color(0xFF212121)
    )
    section.urgency?.takeIf { it.isNotBlank() }?.let { urgency ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = urgency,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = sectionAccent
        )
    }
    section.summary?.takeIf { it.isNotBlank() }?.let { summary ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = summary,
            fontSize = 13.sp,
            color = Color(0xFF424242),
            lineHeight = 18.sp
        )
    }

    val steps = section.steps.orEmpty().filter { it.isNotBlank() }
    if (steps.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (sectionHealthy) "What to do" else "Treatment steps",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color(0xFF2E7D32)
        )
        Spacer(modifier = Modifier.height(8.dp))
        steps.forEachIndexed { stepIndex, step ->
            TreatmentStepRow(index = stepIndex + 1, text = step)
            if (stepIndex < steps.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    val prevention = section.prevention.orEmpty().filter { it.isNotBlank() }
    if (prevention.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        PreventionHeader()
        prevention.forEach { tip ->
            Text(
                text = "• $tip",
                fontSize = 13.sp,
                color = Color(0xFF424242),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun OfflineTreatmentCard(
    result: ValidationResponse,
    treatments: List<DiseaseTreatment>
) {
    if (treatments.isEmpty()) return
    val onlyHealthy = treatments.all { isHealthyDisease(it.diseaseName) }
    val accent = if (onlyHealthy) TomatoGreen else TomatoRed

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (onlyHealthy) Color(0xFFF1F8E9) else Color(0xFFFFF8E1)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            TreatmentCardHeader(
                isHealthy = onlyHealthy,
                accent = accent,
                sourceLabel = "Built-in care guide"
            )

            result.aiRecommendationError?.takeIf { it.isNotBlank() }?.let { error ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Showing built-in tips (AI unavailable).",
                    fontSize = 12.sp,
                    color = Color(0xFF757575)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    fontSize = 11.sp,
                    color = Color(0xFF9E9E9E),
                    lineHeight = 15.sp
                )
            }

            if (treatments.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Care steps for every detection: " +
                        treatments.joinToString(" · ") { guide ->
                            val score = result.allDiseaseScores
                                .firstOrNull { it.name.equals(guide.diseaseName, ignoreCase = true) }
                                ?.percent
                            if (score != null && score > 0) {
                                "${guide.diseaseName} ($score%)"
                            } else {
                                guide.diseaseName
                            }
                        },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF37474F),
                    lineHeight = 18.sp
                )
            }

            treatments.forEachIndexed { index, treatment ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFFE0E0E0))
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                val sectionHealthy = isHealthyDisease(treatment.diseaseName)
                val sectionAccent = if (sectionHealthy) TomatoGreen else accent
                val score = result.allDiseaseScores
                    .firstOrNull { it.name.equals(treatment.diseaseName, ignoreCase = true) }
                    ?.percent
                    ?: 0

                Text(
                    text = buildString {
                        append(treatment.diseaseName)
                        if (score > 0) append(" ($score%)")
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF212121)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = treatment.urgencyNote,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = sectionAccent
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = treatment.summary,
                    fontSize = 13.sp,
                    color = Color(0xFF424242),
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (sectionHealthy) "What to do" else "Treatment steps",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF2E7D32)
                )
                Spacer(modifier = Modifier.height(8.dp))

                treatment.actionSteps.forEachIndexed { stepIndex, step ->
                    TreatmentStepRow(index = stepIndex + 1, text = step)
                    if (stepIndex < treatment.actionSteps.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (treatment.preventionTips.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    PreventionHeader()
                    treatment.preventionTips.forEach { tip ->
                        Text(
                            text = "• $tip",
                            fontSize = 13.sp,
                            color = Color(0xFF424242),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Guidance only — verify on the plant before treating.",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E)
            )
        }
    }
}

@Composable
private fun TreatmentCardHeader(
    isHealthy: Boolean,
    accent: Color,
    sourceLabel: String?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accent.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Healing,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = if (isHealthy) "CARE TIPS" else "TREATMENT RECOMMENDATIONS",
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            if (!sourceLabel.isNullOrBlank()) {
                Text(
                    text = sourceLabel,
                    fontSize = 11.sp,
                    color = Color(0xFF757575)
                )
            }
        }
    }
}

@Composable
private fun PreventionHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = Color(0xFFF9A825),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Prevention",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color(0xFFF57F17)
        )
    }
}

@Composable
private fun TreatmentStepRow(index: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(TomatoGreen.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TomatoGreen
            )
        }
        Text(
            text = text,
            fontSize = 13.sp,
            color = Color(0xFF424242),
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
