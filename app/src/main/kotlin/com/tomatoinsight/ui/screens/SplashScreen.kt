package com.tomatoinsight.ui.screens

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.tomatoinsight.R
import com.tomatoinsight.ui.theme.TomatoGreen
import com.tomatoinsight.ui.theme.TomatoRed
import kotlinx.coroutines.delay

private const val SPLASH_HOLD_MS = 1_800L

/** Soft cream so brand red and green both read clearly. */
private val SplashBackground = Color(0xFFFFF8F5)

/** Route after the splash screen for the current auth and onboarding state. */
fun appRouteAfterSplash(auth: FirebaseAuth, context: Context): String =
    when {
        auth.currentUser != null -> "dashboard"
        !hasSeenOnboarding(context) -> "onboarding"
        else -> "login"
    }

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "splashLogo"
    )

    LaunchedEffect(Unit) {
        started = true
        delay(SPLASH_HOLD_MS)
        onFinished()
    }

    val title = buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = TomatoRed,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                shadow = Shadow(
                    color = Color(0x22000000),
                    offset = Offset(0f, 1f),
                    blurRadius = 2f
                )
            )
        ) {
            append("Tomato")
        }
        withStyle(
            SpanStyle(
                color = TomatoGreen,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                shadow = Shadow(
                    color = Color(0x22000000),
                    offset = Offset(0f, 1f),
                    blurRadius = 2f
                )
            )
        ) {
            append("Insight")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .alpha(logoAlpha)
        ) {
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = "TomatoInsight logo",
                modifier = Modifier.fillMaxWidth(0.62f),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = TomatoGreen,
                strokeWidth = 2.5.dp
            )
        }
    }
}
