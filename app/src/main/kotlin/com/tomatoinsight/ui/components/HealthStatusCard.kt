package com.tomatoinsight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatoinsight.api.DISEASE_DISPLAY_ORDER
import com.tomatoinsight.api.DiseaseScore
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.api.diseaseAbbreviation
import com.tomatoinsight.api.diseaseDisplayLabel
import com.tomatoinsight.api.diseaseSeverityLabel
import com.tomatoinsight.api.isHealthyDisease

private val PanelWhite = Color.White
private val CardLight = Color(0xFFF3F4F6)
private val LabelGray = Color(0xFF8A8A8E)
private val TitleInk = Color(0xFF1C1B1F)
private val BarTrack = Color(0xFFE5E7EB)
private val HealthyBarGreen = Color(0xFF7CB342)
private val DiseaseBarRed = Color(0xFFE57373)

@Composable
fun HealthStatusCard(result: ValidationResponse? = null) {
    val scores = result?.allDiseaseScores ?: DISEASE_DISPLAY_ORDER.map { name ->
        DiseaseScore(
            name = name,
            abbreviation = diseaseAbbreviation(name),
            percent = 0
        )
    }
    val filled = result != null
    val leading = if (filled) scores.maxByOrNull { it.percent }?.takeIf { it.percent > 0 } else null
    val confidence = leading?.percent
    val severity = when {
        leading == null -> "—"
        else -> diseaseSeverityLabel(leading.name, leading.percent)
    }
    val diseaseTitle = when {
        leading == null -> "Waiting for scan"
        else -> detectedDiseaseTitle(leading.name)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = PanelWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Text(
                text = "Detected Disease",
                color = LabelGray,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = diseaseTitle,
                color = TitleInk,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 30.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    label = "AI Confidence",
                    value = if (confidence != null) "$confidence%" else "—",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "Symptom Severity",
                    value = severity,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            scores.forEachIndexed { index, score ->
                DiseaseProgressRow(
                    label = diseaseDisplayLabel(score.name),
                    percent = if (filled) score.percent else null,
                    barColor = if (isHealthyDisease(score.name)) HealthyBarGreen else DiseaseBarRed
                )
                if (index < scores.lastIndex) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardLight)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = LabelGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            color = TitleInk,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DiseaseProgressRow(
    label: String,
    percent: Int?,
    barColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardLight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = TitleInk,
            maxLines = 1,
            modifier = Modifier.width(108.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(BarTrack)
        ) {
            if (percent != null && percent > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(barColor)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (percent == null) "" else "$percent%",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TitleInk,
            modifier = Modifier.width(40.dp)
        )
    }
}

private fun detectedDiseaseTitle(name: String): String = when {
    name.equals("Healthy", ignoreCase = true) -> "Healthy"
    name.contains("yellow leaf curl", ignoreCase = true) || name.equals("TLCV", ignoreCase = true) ->
        "Tomato Leaf Curl Virus (TLCV)"
    name.contains("spotted wilt", ignoreCase = true) || name.equals("TSWV", ignoreCase = true) ->
        "Tomato Spotted Wilt Virus (TSWV)"
    name.contains("early blight", ignoreCase = true) ->
        "Early Blight (EB)"
    else -> diseaseDisplayLabel(name)
}
