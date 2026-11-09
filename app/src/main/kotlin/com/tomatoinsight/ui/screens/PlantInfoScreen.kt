package com.tomatoinsight.ui.screens

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatoinsight.api.ImageUploadHelper
import com.tomatoinsight.api.WebsiteScanClient
import com.tomatoinsight.ml.OfflineDiseaseClassifier
import com.tomatoinsight.ml.OfflineLeafValidator
import com.tomatoinsight.util.hasValidatedInternet
import com.tomatoinsight.util.observeValidatedInternet
import com.tomatoinsight.api.DiseaseScore
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.api.isHealthyDisease
import com.tomatoinsight.data.SavedRecommendation
import com.tomatoinsight.data.ScanSaveOutcome
import com.tomatoinsight.data.asSavedValidationResponse
import com.tomatoinsight.data.ensureGuidesForAllDetections
import com.tomatoinsight.api.ValidationRetrofitClient
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import com.tomatoinsight.ui.camera.LeafCameraScreen
import com.tomatoinsight.ui.components.CustomBottomBar
import com.tomatoinsight.ui.components.InvalidLeafValidationDialog
import com.tomatoinsight.ui.components.invalidLeafHighlightReason
import com.tomatoinsight.ui.components.DiseaseTreatmentCard
import com.tomatoinsight.ui.components.HealthStatusCard
import com.tomatoinsight.ui.theme.TomatoGreen
import com.tomatoinsight.ui.theme.TomatoGreenDark
import com.tomatoinsight.ui.theme.TomatoRed
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantInfoScreen(
    tileNumber: Int? = null,
    savedImage: Bitmap? = null,
    savedResult: ValidationResponse? = null,
    savedScores: List<DiseaseScore> = emptyList(),
    plantName: String = "",
    savedRecommendation: SavedRecommendation? = null,
    onNavigateBack: () -> Unit,
    onResultSaved: suspend (ValidationResponse, Bitmap?, String) -> ScanSaveOutcome = { _, _, _ ->
        ScanSaveOutcome.Saved
    },
    onRecommendationSaved: (ValidationResponse) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var capturedImage by remember(tileNumber) { mutableStateOf(savedImage) }
    var showSourceSelector by remember { mutableStateOf(false) }
    var showLeafCamera by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var validationResult by remember(tileNumber) { mutableStateOf(savedResult) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isSavedScan by remember(tileNumber) { mutableStateOf(savedResult != null) }
    var hasPendingSave by remember(tileNumber) { mutableStateOf(false) }
    var showIntervention by remember(tileNumber, savedScores) {
        mutableStateOf(savedScores.any { !isHealthyDisease(it.name) && it.percent > 0 })
    }
    var showNotTomatoAlert by remember { mutableStateOf(false) }
    var showOfflineRecommendationAlert by remember { mutableStateOf(false) }
    var awaitingRecommendation by remember { mutableStateOf(false) }
    var isLoadingRecommendation by remember { mutableStateOf(false) }
    var recommendationPendingSave by remember(tileNumber) { mutableStateOf(false) }
    var plantNameInput by remember(tileNumber) { mutableStateOf(plantName) }
    var isSavingInfo by remember { mutableStateOf(false) }
    val internetAvailable by produceState(initialValue = context.hasValidatedInternet(), context) {
        context.observeValidatedInternet().collect { value = it }
    }

    LaunchedEffect(plantName) {
        if (plantNameInput.isBlank() && plantName.isNotBlank()) {
            plantNameInput = plantName
        }
    }

    fun validateTomatoLeaf(bitmap: Bitmap) {
        scope.launch {
            isValidating = true
            validationResult = null
            validationError = null
            showIntervention = false
            awaitingRecommendation = false
            showOfflineRecommendationAlert = false
            try {
                val response = try {
                    withTimeout(300_000L) {
                        ImageUploadHelper.validateTomatoLeaf(context, bitmap)
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw e
                } catch (e: HttpException) {
                    throw e
                } catch (e: Exception) {
                    if (!shouldUseOnDeviceLeafCheck(e, context)) throw e
                    withContext(Dispatchers.IO) {
                        val prepared = ImageUploadHelper.bitmapForOnDeviceScan(bitmap)
                        val leaf = OfflineLeafValidator.validate(context, prepared)
                        if (leaf.confirmsTomatoLeaf()) {
                            OfflineDiseaseClassifier.classify(context, prepared, leaf)
                        } else {
                            leaf
                        }
                    }
                }
                // Disease bars always come from the same on-device TFLite, so online and
                // offline scans of the same photo show the same health percentages.
                val scored = if (response.confirmsTomatoLeaf()) {
                    withContext(Dispatchers.IO) {
                        val prepared = ImageUploadHelper.bitmapForOnDeviceScan(bitmap)
                        OfflineDiseaseClassifier.classify(context, prepared, response)
                    }
                } else {
                    response
                }
                // OpenCV severity from Render — separate from AI confidence.
                val withSeverity = if (scored.confirmsTomatoLeaf() && context.hasValidatedInternet()) {
                    withContext(Dispatchers.IO) {
                        val bytes = ByteArrayOutputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                            stream.toByteArray()
                        }
                        WebsiteScanClient.withOpenCvSeverity(bytes, scored)
                    }
                } else if (scored.confirmsTomatoLeaf()) {
                    val top = scored.allDiseaseScores.maxByOrNull { it.percent }
                    if (top != null && isHealthyDisease(top.name)) {
                        scored.copy(
                            severityLabel = "Healthy",
                            affectedAreaPct = 0.0,
                            severityAssessable = true,
                            severityReason = "offline_healthy"
                        )
                    } else {
                        scored.copy(
                            severityLabel = "Unable to Assess",
                            affectedAreaPct = null,
                            severityAssessable = false,
                            severityReason = "offline_no_opencv"
                        )
                    }
                } else {
                    scored
                }
                validationResult = withSeverity
                val leafOnly = withSeverity.stage == "offline" && !withSeverity.hasDiseaseResult
                hasPendingSave = withSeverity.confirmsTomatoLeaf() && !leafOnly
                showNotTomatoAlert = !withSeverity.isError && !withSeverity.confirmsTomatoLeaf()
                Log.d(
                    "TomatoInsight",
                    "Validation result=${withSeverity.result}, class=${withSeverity.detectedClass}, " +
                        "disease=${withSeverity.topDisease} severity=${withSeverity.severityLabel} " +
                        "area=${withSeverity.affectedAreaPct} mode=${withSeverity.inferenceMode}"
                )
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e("TomatoInsight", "Validation timed out", e)
                validationError =
                    "Request timed out. The website server may be waking up. Open ${ValidationRetrofitClient.BASE_URL}health in a browser, then try again."
            } catch (e: HttpException) {
                Log.e("TomatoInsight", "HTTP error ${e.code()}", e)
                val body = e.response()?.errorBody()?.string()
                validationError = buildString {
                    append("HTTP ${e.code()}")
                    if (!body.isNullOrBlank()) append("\n$body")
                    append("\n\nServer: ${ValidationRetrofitClient.BASE_URL}")
                    append("\nTip: The first request after a pause can take a minute while Render wakes up.")
                }
            } catch (e: Exception) {
                Log.e("TomatoInsight", "Validation failed", e)
                validationError = if (!context.hasValidatedInternet()) {
                    e.message ?: "On-phone leaf check failed."
                } else {
                    buildString {
                        append(e.message ?: "Could not reach validation server.")
                        append("\n\nServer: ${ValidationRetrofitClient.BASE_URL}")
                        append("\nTip: Open ${ValidationRetrofitClient.BASE_URL}health in the phone browser. The first request can take a minute while Render wakes up.")
                    }
                }
            } finally {
                isValidating = false
            }
        }
    }

    fun processImage(bitmap: Bitmap) {
        capturedImage = bitmap
        validationResult = null
        validationError = null
        isSavedScan = false
        hasPendingSave = false
        showIntervention = false
        showNotTomatoAlert = false
        awaitingRecommendation = false
        showOfflineRecommendationAlert = false
        recommendationPendingSave = false
        validateTomatoLeaf(bitmap)
    }

    fun loadCareRecommendation(bitmap: Bitmap) {
        scope.launch {
            showIntervention = true
            isLoadingRecommendation = true
            awaitingRecommendation = false
            showOfflineRecommendationAlert = false
            try {
                val server = withTimeout(300_000L) {
                    ImageUploadHelper.validateTomatoLeaf(context, bitmap)
                }
                val current = validationResult
                val merged = if (current?.stage == "offline") {
                    current.copy(
                        aiRecommendations = server.aiRecommendations,
                        aiRecommendationError = server.aiRecommendationError,
                        aiDisclaimer = server.aiDisclaimer,
                        recommendationSource = server.recommendationSource,
                        groqModel = server.groqModel
                    ).ensureGuidesForAllDetections()
                } else {
                    server.ensureGuidesForAllDetections()
                }
                validationResult = merged
                recommendationPendingSave = isSavedScan &&
                    !hasPendingSave &&
                    merged.hasAiRecommendations
                showIntervention = true
            } catch (e: Exception) {
                Log.e("TomatoInsight", "Recommendation fetch failed", e)
                validationError = e.message ?: "Could not load the care recommendation."
                showIntervention = false
            } finally {
                isLoadingRecommendation = false
            }
        }
    }

    LaunchedEffect(awaitingRecommendation, internetAvailable) {
        val image = capturedImage
        if (awaitingRecommendation && internetAvailable && image != null) {
            loadCareRecommendation(image)
        }
    }

    val savedClassification = savedScores.any { it.percent > 0 }
    val leafOnlyOffline = validationResult?.stage == "offline" &&
        validationResult?.hasDiseaseResult != true
    val displayResult = when {
        isValidating || showNotTomatoAlert || leafOnlyOffline -> null
        validationResult?.hasAiRecommendations == true &&
            validationResult?.confirmsTomatoLeaf() == true -> validationResult
        hasPendingSave && validationResult?.confirmsTomatoLeaf() == true -> validationResult
        savedClassification -> savedScores.asSavedValidationResponse()
        validationResult?.confirmsTomatoLeaf() == true -> validationResult
        else -> null
    }

    fun rotateBitmapIfNeeded(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        } catch (e: Exception) {
            Log.e("TomatoInsight", "Error rotating bitmap", e)
        }
        return bitmap
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bitmap = if (Build.VERSION.SDK_INT < 28) {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                } else {
                    val source = ImageDecoder.createSource(context.contentResolver, it)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                }
                val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val orientedBitmap = rotateBitmapIfNeeded(context, it, argbBitmap)
                processImage(orientedBitmap)
            } catch (e: Exception) {
                Log.e("TomatoInsight", "Gallery Load Error", e)
                scope.launch { snackbarHostState.showSnackbar("Failed to load image") }
            }
        }
    }

    if (showLeafCamera) {
        LeafCameraScreen(
            onImageCaptured = { bitmap ->
                processImage(bitmap)
                showLeafCamera = false
            },
            onClose = { showLeafCamera = false }
        )
        return
    }

    if (showOfflineRecommendationAlert) {
        AlertDialog(
            onDismissRequest = { showOfflineRecommendationAlert = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = TomatoRed
                )
            },
            title = { Text("Internet required", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Care recommendations need an internet connection. " +
                        "This recommendation will open automatically when the connection is back."
                )
            },
            confirmButton = {
                TextButton(onClick = { showOfflineRecommendationAlert = false }) {
                    Text("OK", color = TomatoGreen, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showNotTomatoAlert) {
        InvalidLeafValidationDialog(
            highlightReason = validationResult?.invalidLeafHighlightReason() ?: "not-leaf",
            onDismiss = { showNotTomatoAlert = false }
        )
    }

    if (showSourceSelector) {
        AlertDialog(
            onDismissRequest = { showSourceSelector = false },
            title = { Text("Select Image Source") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("Camera") },
                        leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showSourceSelector = false
                            showLeafCamera = true
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Gallery") },
                        leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showSourceSelector = false
                            galleryLauncher.launch("image/*")
                        }
                    )
                }
            },
            confirmButton = {}
        )
    }

    val pageColor = Color(0xFFFFF9F0)
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val previousNav = window.navigationBarColor
        window.navigationBarColor = pageColor.toArgb()
        val controller = WindowCompat.getInsetsController(window, view)
        val previousLightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightNavigationBars = true
        onDispose {
            window.navigationBarColor = previousNav
            controller.isAppearanceLightNavigationBars = previousLightNav
        }
    }

    val scrollState = rememberScrollState()
    val imageCollapse = (scrollState.value / 240f).coerceIn(0f, 1f)
    val imageHeight = (220f - 72f * imageCollapse).dp

    Scaffold(
        containerColor = pageColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (tileNumber != null) "Tile $tileNumber" else "Plant Information",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = {
            CustomBottomBar(
                hideNavIcons = true,
                containerColor = pageColor,
                onCameraClick = { showSourceSelector = true }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(pageColor)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .graphicsLayer { translationY = -16f * imageCollapse },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFD9D9D9)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (capturedImage != null) {
                        Image(
                            bitmap = capturedImage!!.asImageBitmap(),
                            contentDescription = "Captured Plant Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("No image captured", color = Color.Gray)
                    }
                    BasicTextField(
                        value = plantNameInput,
                        onValueChange = { plantNameInput = it },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 12.dp, end = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.78f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFF0F172A),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        cursorBrush = SolidColor(Color(0xFF0F172A)),
                        decorationBox = { inner ->
                            Box {
                                if (plantNameInput.isEmpty()) {
                                    Text(
                                        text = "Enter plant name",
                                        color = Color(0xFF0F172A).copy(alpha = 0.55f),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = TomatoGreen,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PLANT DETAILS", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    HealthStatusCard(result = displayResult)

                    Spacer(modifier = Modifier.height(16.dp))

                    val hasPlantName = plantNameInput.trim().isNotEmpty()
                    val canSave = tileNumber != null &&
                        hasPlantName &&
                        validationResult?.confirmsTomatoLeaf() == true &&
                        !isValidating &&
                        hasPendingSave
                    val canSaveRecommendation = tileNumber != null &&
                        hasPlantName &&
                        recommendationPendingSave &&
                        validationResult?.hasAiRecommendations == true &&
                        !isLoadingRecommendation
                    val showSaveInfo = tileNumber != null &&
                        !isValidating &&
                        (
                            hasPendingSave ||
                                canSaveRecommendation ||
                                (displayResult != null && (isSavedScan || validationResult != null || savedClassification))
                            )

                    if (capturedImage != null || validationResult != null || savedClassification) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            validationError?.let { error ->
                                ValidationResultCard(
                                    title = "Connection Error",
                                    message = error,
                                    isSuccess = false
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            validationResult?.let { result ->
                                when {
                                    displayResult != null -> Unit
                                    result.confirmsTomatoLeaf() && !result.hasDiseaseResult -> {
                                        ValidationResultCard(
                                            title = "Tomato leaf detected",
                                            message = result.message
                                                ?: "This photo passed the on-phone leaf check.",
                                            isSuccess = true
                                        )
                                    }
                                    showNotTomatoAlert && !result.isError -> Unit
                                    result.isError -> {
                                        ValidationResultCard(
                                            title = "Validation Error",
                                            message = result.message ?: "Unknown server response.",
                                            isSuccess = false
                                        )
                                    }
                                }
                            }

                            // Offline scans already have rule guides for every %.
                            // When online, still ask the website/Groq to refine the top match.
                            val needsOnlineRecommendation = displayResult?.stage == "offline" &&
                                displayResult?.recommendationSource != "groq"
                            val canViewIntervention = displayResult?.allDiseaseScores?.any { it.percent > 0 } == true &&
                                !isValidating

                            if (canViewIntervention) {
                                OutlinedButton(
                                    onClick = {
                                        if (showIntervention && !needsOnlineRecommendation) {
                                            showIntervention = false
                                        } else if (needsOnlineRecommendation && !internetAvailable) {
                                            showIntervention = true
                                            awaitingRecommendation = true
                                            showOfflineRecommendationAlert = true
                                        } else if (needsOnlineRecommendation) {
                                            showIntervention = true
                                            capturedImage?.let { loadCareRecommendation(it) }
                                        } else {
                                            showIntervention = !showIntervention
                                        }
                                    },
                                    enabled = !isLoadingRecommendation,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (showIntervention) {
                                            TomatoRed.copy(alpha = 0.45f)
                                        } else {
                                            Color(0xFFE5E5E5)
                                        }
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (showIntervention) {
                                            TomatoRed.copy(alpha = 0.06f)
                                        } else {
                                            Color.White
                                        },
                                        contentColor = TomatoRed
                                    ),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isLoadingRecommendation -> "Loading recommendation..."
                                            showIntervention -> "Hide Recommendation"
                                            else -> "View Recommendation"
                                        },
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                        letterSpacing = 0.15.sp
                                    )
                                }

                                AnimatedVisibility(
                                    visible = showIntervention,
                                    enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
                                        expandVertically(tween(320, easing = FastOutSlowInEasing)),
                                    exit = fadeOut(tween(200, easing = FastOutSlowInEasing)) +
                                        shrinkVertically(tween(280, easing = FastOutSlowInEasing))
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        if (isLoadingRecommendation) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(32.dp),
                                                color = TomatoGreen,
                                                strokeWidth = 3.dp
                                            )
                                        } else {
                                            DiseaseTreatmentCard(
                                                result = displayResult!!,
                                                savedRecommendation = if (
                                                    hasPendingSave || validationResult?.hasAiRecommendations == true
                                                ) {
                                                    null
                                                } else {
                                                    savedRecommendation
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showSaveInfo) {
                        Spacer(modifier = Modifier.height(12.dp))
                        if (canSave || canSaveRecommendation) {
                            FilledTonalButton(
                                onClick = {
                                    if (isSavingInfo) return@FilledTonalButton
                                    val result = validationResult ?: return@FilledTonalButton
                                    val image = capturedImage
                                    val recommendationOnly = canSaveRecommendation && !hasPendingSave
                                    val name = plantNameInput.trim()
                                    scope.launch {
                                        isSavingInfo = true
                                        val outcome = if (recommendationOnly) {
                                            onRecommendationSaved(result)
                                            recommendationPendingSave = false
                                            snackbarHostState.showSnackbar(
                                                "Recommendation saved for Tile $tileNumber"
                                            )
                                            ScanSaveOutcome.Saved
                                        } else {
                                            onResultSaved(result, image, name)
                                        }
                                        isSavingInfo = false
                                        if (!recommendationOnly && outcome is ScanSaveOutcome.Saved) {
                                            isSavedScan = true
                                            hasPendingSave = false
                                            recommendationPendingSave = false
                                        } else if (outcome is ScanSaveOutcome.Blocked && outcome.code != "insufficient-credits") {
                                            snackbarHostState.showSnackbar(outcome.message)
                                        }
                                    }
                                },
                                enabled = !isSavingInfo,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = TomatoGreen.copy(alpha = 0.14f),
                                    contentColor = TomatoGreenDark
                                ),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = when {
                                        isSavingInfo -> "Saving..."
                                        canSaveRecommendation && !hasPendingSave -> "Save recommendation"
                                        else -> "Save Info"
                                    },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    letterSpacing = 0.15.sp
                                )
                            }
                        } else {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = if (hasPlantName && (isSavedScan || !hasPendingSave)) {
                                        TomatoGreen
                                    } else {
                                        Color(0xFFE0E0E0)
                                    },
                                    disabledContentColor = if (hasPlantName && (isSavedScan || !hasPendingSave)) {
                                        Color.White
                                    } else {
                                        Color(0xFF757575)
                                    }
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 0.dp,
                                    pressedElevation = 0.dp,
                                    disabledElevation = 0.dp
                                ),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = if (hasPlantName && (isSavedScan || !hasPendingSave)) {
                                        "Saved Info"
                                    } else {
                                        "Save Info"
                                    },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    letterSpacing = 0.15.sp
                                )
                            }
                        }
                        if (canSave) {
                            Text(
                                text = "Scan as many times as you need. Tap Save Info when you have a good result. That save uses 1 credit.",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else if (!hasPlantName && (hasPendingSave || displayResult != null)) {
                            Text(
                                text = "Enter a plant name to save this scan.",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        if (isSavedScan && !hasPendingSave) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (tileNumber != null) {
                                    "Saved scan for Tile $tileNumber"
                                } else {
                                    "Saved scan result"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TomatoGreen
                            )
                            Text(
                                text = "Take or upload a new photo to rescan this tile.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            }
        }
            if (isValidating) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White,
                        shadowElevation = 12.dp
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(28.dp)
                                .size(42.dp),
                            color = TomatoGreen,
                            strokeWidth = 3.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidationResultCard(
    title: String,
    message: String,
    isSuccess: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = if (isSuccess) TomatoGreen else TomatoRed,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color(0xFF424242)
            )
        }
    }
}

private fun shouldUseOnDeviceLeafCheck(error: Throwable, context: Context): Boolean {
    if (!context.hasValidatedInternet()) return true
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.SocketTimeoutException -> return true
        }
        current = current.cause
    }
    return false
}
