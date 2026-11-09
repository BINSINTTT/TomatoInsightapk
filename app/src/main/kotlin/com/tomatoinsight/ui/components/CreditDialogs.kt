package com.tomatoinsight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tomatoinsight.data.CREDIT_PACKS
import com.tomatoinsight.data.CreditPack
import com.tomatoinsight.data.formatPhp
import com.tomatoinsight.ui.theme.TomatoRed
import kotlinx.coroutines.delay

private val PlanInk = Color(0xFF1A1A1A)
private val PlanMuted = Color(0xFF6B7280)
private val PlanLine = Color(0x1F1A1A1A)
private val FeaturedStart = Color(0xFF8F1A12)
private val FeaturedEnd = Color(0xFF6D120C)

data class CreditToast(
    val id: Long,
    val title: String,
    val text: String,
    val kind: String
)

@Composable
fun CreditToastHost(
    toasts: List<CreditToast>,
    onDismiss: (Long) -> Unit
) {
    if (toasts.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        toasts.takeLast(3).forEach { toast ->
            LaunchedEffect(toast.id) {
                delay(if (toast.kind == "deduct") 6500 else 8000)
                onDismiss(toast.id)
            }
            val accent = when (toast.kind) {
                "deduct" -> Color(0xFF8D3A2F)
                "accent" -> Color(0xFFB86A12)
                else -> Color(0xFF1B5E20)
            }
            val titleOnly = toast.text.isBlank()
            Surface(
                modifier = if (titleOnly) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = if (titleOnly) 8.dp else 12.dp,
                        top = 10.dp,
                        bottom = 10.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (titleOnly) Arrangement.Center else Arrangement.Start
                ) {
                    Text(
                        text = if (toast.kind == "deduct") "–" else "✓",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(accent, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .then(if (titleOnly) Modifier else Modifier.weight(1f))
                    ) {
                        Text(toast.title, fontWeight = FontWeight.Bold, color = Color(0xFF243046), fontSize = 14.sp)
                        if (toast.text.isNotBlank()) {
                            Text(toast.text, color = Color(0xFF667085), fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { onDismiss(toast.id) }) {
                        Text("×", color = Color(0xFF667085), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun OutOfCreditDialog(
    onDismiss: () -> Unit,
    onSelectPack: (CreditPack) -> Unit,
    onViewPlans: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text("Scan credits", color = TomatoRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Out of credit", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF243046))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Each saved scan uses 1 credit. Pick a pack to open the free demo checkout (Pay ₱0).",
                    color = Color(0xFF667085),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                CREDIT_PACKS.forEach { pack ->
                    CreditPackCard(pack = pack, onSelect = { onSelectPack(pack) })
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Text(
                    "New accounts start with 10 free credits. Finish all six farm setup steps, then tap Update profile for 5 more.",
                    color = Color(0xFF667085),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onViewPlans,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TomatoRed)
                ) {
                    Text("View plans", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Not now", color = Color(0xFF243046))
                }
            }
        }
    }
}

@Composable
fun DemoCheckoutDialog(
    pack: CreditPack,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPay: () -> Unit
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    "Demo checkout",
                    color = TomatoRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    "Pay ₱0 to add credits",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Color(0xFF243046)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "${pack.name} · ${pack.credits} credits",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF243046)
                )
                Spacer(modifier = Modifier.height(10.dp))
                CheckoutLine("Listed price", formatPhp(pack.listPricePhp))
                CheckoutLine("You pay today", "₱0")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This is a free demo. No card is charged. A paid row is still saved in Firestore so you can query purchases.",
                    color = Color(0xFF667085),
                    fontSize = 13.sp
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        color = TomatoRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onPay,
                        enabled = !busy,
                        modifier = Modifier.weight(1.4f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935)
                        )
                    ) {
                        Text(if (busy) "Processing…" else "Pay ₱0 (demo)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Website-style three-tier credit plan card (white sides / red featured Grower). */
@Composable
fun CreditPackCard(
    pack: CreditPack,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val featured = pack.featured
    val ink = if (featured) Color.White else PlanInk
    val muted = if (featured) Color.White.copy(alpha = 0.78f) else PlanMuted
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (featured) {
                    Modifier.background(
                        Brush.linearGradient(listOf(FeaturedStart, FeaturedEnd)),
                        shape
                    )
                } else {
                    Modifier
                        .background(Color.White, shape)
                        .border(1.dp, PlanLine, shape)
                }
            )
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        if (featured) {
            Text(
                text = "Most Popular",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                modifier = Modifier.padding(end = if (featured) 96.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = pack.name,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    color = ink,
                    letterSpacing = (-0.3).sp
                )
                Text(
                    text = pack.audience,
                    color = muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatPhp(pack.listPricePhp),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 36.sp,
                    color = ink,
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "/pack",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = muted,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pack.features.forEach { feature ->
                    PlanFeatureRow(
                        text = feature,
                        ink = ink,
                        featured = featured
                    )
                }
            }
            Button(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (featured) Color.White else Color.White,
                    contentColor = if (featured) FeaturedEnd else PlanInk
                ),
                border = if (featured) null else androidx.compose.foundation.BorderStroke(1.5.dp, PlanLine),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (featured) 6.dp else 0.dp
                )
            ) {
                Text(
                    text = pack.ctaLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PlanFeatureRow(
    text: String,
    ink: Color,
    featured: Boolean
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (featured) Color.White else Color(0x1F6B7280)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (featured) FeaturedStart else PlanMuted,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = ink,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CheckoutLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF667085))
        Text(value, fontWeight = FontWeight.Bold, color = Color(0xFF243046))
    }
}
