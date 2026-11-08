package com.tomatoinsight.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.tomatoinsight.R
import com.tomatoinsight.data.BAR_EARLY_BLIGHT
import com.tomatoinsight.data.BAR_HEALTHY
import com.tomatoinsight.data.BAR_SPOTTED_WILT
import com.tomatoinsight.data.BAR_TLCV
import com.tomatoinsight.data.ScanCalendar
import com.tomatoinsight.data.TileScanHistory
import com.tomatoinsight.data.TileScanResult
import com.tomatoinsight.data.asOfIso
import com.tomatoinsight.data.barKeyForDiseaseName
import com.tomatoinsight.data.formatScanDueLabel
import com.tomatoinsight.data.latestByTile
import com.tomatoinsight.data.latestFor
import com.tomatoinsight.data.reminderWeekForPlant
import com.tomatoinsight.data.scansFor
import com.tomatoinsight.ui.components.ConnectionStatusDot
import com.tomatoinsight.ui.components.CustomBottomBar
import com.tomatoinsight.ui.components.InternetConnectionNotice
import com.tomatoinsight.ui.theme.TomatoGreen
import com.tomatoinsight.ui.theme.TomatoRed
import com.tomatoinsight.util.hasValidatedInternet
import com.tomatoinsight.util.observeValidatedInternet

private val FarmSkyTop = Color(0xFFFFFBEB)
private val FarmSkySun = Color(0xFFFEF08A)
private val FarmSkyMid = Color(0xFFD9F99D)
private val FarmSkyField = Color(0xFF86EFAC)
private val TomatoShell = Color(0xFF9B2B26)
private val FarmWorkspaceSubtitle = Color(0xFF8B95A7)
private val FarmLegendChipBg = Color(0xFFF4F6F8)
private val FarmLegendText = Color(0xFF475467)
private val TileToneHealthy = Color(0xFF43A047)
private val TileToneTlcv = Color(0xFFFB8C00)
private val TileToneBlight = Color(0xFFE53935)
private val TileToneWilt = Color(0xFF8E24AA)
private val TileToneEmpty = Color(0xFFF1F8E9)

/** Same face images the website uses on farm tiles. */
private fun tileResultEmoji(result: TileScanResult?): Int {
    if (result == null || result.percent <= 0) return R.drawable.tile_icon_leaf
    if (barKeyForDiseaseName(result.diseaseName) == BAR_HEALTHY) return R.drawable.happy_emoji
    return if (result.percent >= 100) R.drawable.sad_emoji else R.drawable.worry_emoji
}

private fun tileResultTone(result: TileScanResult?): Color {
    if (result == null || result.percent <= 0) return TileToneEmpty
    return when (barKeyForDiseaseName(result.diseaseName)) {
        BAR_HEALTHY -> TileToneHealthy
        BAR_TLCV -> TileToneTlcv
        BAR_EARLY_BLIGHT -> TileToneBlight
        BAR_SPOTTED_WILT -> TileToneWilt
        else -> TileToneEmpty
    }
}

/** Same surface as the website farm blueprint: sky gradient with the grass strip along the bottom. */
private fun Modifier.farmLayoutBackground(grass: Painter): Modifier = drawBehind {
    drawRect(
        brush = Brush.verticalGradient(
            0f to FarmSkyTop,
            0.28f to FarmSkySun,
            0.62f to FarmSkyMid,
            1f to FarmSkyField
        )
    )
    val tileWidth = 865.dp.toPx()
    val tileHeight = 288.dp.toPx()
    if (tileWidth <= 0f || tileHeight <= 0f) return@drawBehind
    val top = size.height - tileHeight
    var x = ((size.width - tileWidth) / 2f) % tileWidth
    if (x > 0f) x -= tileWidth
    while (x < size.width) {
        translate(left = x, top = top) {
            with(grass) {
                draw(size = Size(tileWidth, tileHeight))
            }
        }
        x += tileWidth
    }
}

@Composable
private fun HomeMetricCard(
    modifier: Modifier,
    value: String,
    label: String,
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color
) {
    Card(
        modifier = modifier.heightIn(min = 84.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = value,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF222222),
                    maxLines = 1
                )
                Text(
                    text = label,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = Color.Gray,
                    maxLines = 2
                )
            }
        }
    }
}

private fun getTileNumber(r: Int, c: Int, rows: Int, columns: Int): Int {
    val fromBottomRow = (rows - 1 - r)
    val isEvenFromBottom = fromBottomRow % 2 == 0
    val colOffset = if (isEvenFromBottom) (columns - 1 - c) else c
    return fromBottomRow * columns + colOffset + 1
}

@Composable
fun DashboardScreen(
    rows: Int,
    columns: Int,
    farmerName: String = "",
    farmName: String = "",
    plantNames: Map<Int, String> = emptyMap(),
    selectedFarmWeek: Int = 0,
    scanCalendar: ScanCalendar = ScanCalendar(),
    scanCalendarNote: String = "",
    farmLoading: Boolean = false,
    creditBalance: Int? = null,
    creditsLoaded: Boolean = false,
    profileBonusGranted: Boolean = true,
    onOpenCreditPlans: () -> Unit = {},
    cloudError: String? = null,
    onLogout: () -> Unit,
    onNavigateToAddBlueprint: () -> Unit,
    onNavigateToMonitoring: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    selectedTile: Int? = null,
    tileScanHistory: TileScanHistory = emptyMap(),
    onTileSelected: (Int) -> Unit = {},
    onTileDoubleTap: (Int) -> Unit = {},
    onDeleteTile: (Int) -> Unit = {},
    onCameraClick: () -> Unit = {}
) {
    val auth = FirebaseAuth.getInstance()
    val user = auth.currentUser
    val userName = farmerName.ifBlank {
        user?.displayName?.ifBlank { null }
            ?: user?.email?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
            ?: "Farmer"
    }
    val usersFarm = "$userName's Farm"
    val farmLabel = farmName.trim().ifBlank { "Your farm" }
    val totalPlants = if (rows > 0 && columns > 0) rows * columns else 0
    var healthyPlants = 0
    var attentionPlants = 0
    tileScanHistory.latestByTile().forEach { (tile, result) ->
        if (totalPlants > 0 && (tile < 1 || tile > totalPlants)) return@forEach
        if (result.isHealthy) healthyPlants += 1 else attentionPlants += 1
    }
    val creditText = if (creditsLoaded && creditBalance != null) creditBalance.toString() else "—"

    var notifyTile by remember { mutableStateOf<Int?>(null) }
    var deleteTile by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val internetAvailable by produceState(initialValue = context.hasValidatedInternet(), context) {
        context.observeValidatedInternet().collect { value = it }
    }

    Scaffold(
        containerColor = Color(0xFFFAFAFA),
        bottomBar = {
            CustomBottomBar(
                selectedTab = "home",
                cameraEnabled = selectedTile != null,
                onHomeClick = { },
                onMonitoringClick = onNavigateToMonitoring,
                onCameraClick = onCameraClick,
                onHistoryClick = onNavigateToHistory,
                onProfileClick = onNavigateToProfile
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFFAFAFA))
        ) {
            notifyTile?.let { tile ->
                TileNotificationDialog(
                    tileNumber = tile,
                    plantName = plantNames[tile].orEmpty(),
                    messages = tileNotificationMessages(tile, tileScanHistory, scanCalendar),
                    scanCalendar = scanCalendar,
                    onDismiss = { notifyTile = null }
                )
            }

            deleteTile?.let { tile ->
                val name = plantNames[tile]?.trim().orEmpty().ifBlank { "Tile $tile" }
                AlertDialog(
                    onDismissRequest = { deleteTile = null },
                    title = { Text("Delete saved data?", fontWeight = FontWeight.Bold) },
                    text = {
                        Text("Delete all saved data for $name? This removes the plant name, scans, and scores.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                deleteTile = null
                                onDeleteTile(tile)
                            }
                        ) {
                            Text("Delete", color = TomatoRed, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteTile = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Hero Farm Card
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                InternetConnectionNotice()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = TomatoRed),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        TomatoRed,
                                        Color(0xFFE53935)
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = usersFarm,
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = farmLabel,
                                        color = Color.White.copy(alpha = 0.92f),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                val headerWeek = scanCalendar.resolvedFarmWeek()
                                    ?: selectedFarmWeek.takeIf { it > 0 }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.25f)
                                ) {
                                    Text(
                                        text = if (headerWeek != null) "Week $headerWeek" else "● Active",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ConnectionStatusDot(size = 8.dp, ring = true)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (internetAvailable) "Online" else "Offline",
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (!cloudError.isNullOrBlank()) {
                                        Text(
                                            text = cloudError,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.25f),
                                    modifier = Modifier.clickable(onClick = onOpenCreditPlans)
                                ) {
                                    Text(
                                        text = "Credit: $creditText",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HomeMetricCard(
                        modifier = Modifier.weight(1f),
                        value = totalPlants.toString(),
                        label = "Total plants",
                        icon = Icons.Default.Eco,
                        iconTint = TomatoRed,
                        iconBackground = Color(0xFFFDECEA)
                    )
                    HomeMetricCard(
                        modifier = Modifier.weight(1f),
                        value = healthyPlants.toString(),
                        label = "Healthy",
                        icon = Icons.Default.Favorite,
                        iconTint = Color(0xFF2E7D32),
                        iconBackground = Color(0xFFE8F5E9)
                    )
                    HomeMetricCard(
                        modifier = Modifier.weight(1f),
                        value = attentionPlants.toString(),
                        label = "Need attention",
                        icon = Icons.Default.Warning,
                        iconTint = Color(0xFFEF6C00),
                        iconBackground = Color(0xFFFFF3E0)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            val hasFarmLayout = rows > 0 && columns > 0
            val farmSurface = painterResource(R.drawable.dashboard_farm_layout_background)
            if (hasFarmLayout) {
                FarmStatusLegendRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .then(
                        if (!hasFarmLayout) {
                            Modifier.border(
                                width = 2.dp,
                                color = TomatoGreen.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(20.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                FarmBlueprintToolbar(
                    hasLayout = hasFarmLayout,
                    onEditLayout = onNavigateToAddBlueprint,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .farmLayoutBackground(farmSurface)
                        .then(
                            if (!hasFarmLayout) {
                                Modifier.clickable { onNavigateToAddBlueprint() }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    if (hasFarmLayout) {
                        FarmGridView(
                            rows = rows,
                            columns = columns,
                            selectedTile = selectedTile,
                            tileScanHistory = tileScanHistory,
                            plantNames = plantNames,
                            scanCalendar = scanCalendar,
                            onTileSelected = onTileSelected,
                            onTileDoubleTap = onTileDoubleTap,
                            onNotifyTile = { notifyTile = it },
                            onDeleteTile = { deleteTile = it }
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                color = TomatoGreen.copy(alpha = 0.12f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Blueprint",
                                        modifier = Modifier.size(36.dp),
                                        tint = TomatoGreen
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Add farm blueprint",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF222222)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap here to set rows and columns for your tomato farm",
                                fontSize = 13.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FarmStatusLegendRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FarmLegendChip(
            label = "Healthy",
            dotColor = TileToneHealthy,
            modifier = Modifier.weight(1f)
        )
        FarmLegendChip(
            label = "TLCV",
            dotColor = TileToneTlcv,
            modifier = Modifier.weight(1f)
        )
        FarmLegendChip(
            label = "Early blight",
            dotColor = TileToneBlight,
            modifier = Modifier.weight(1f)
        )
        FarmLegendChip(
            label = "Spotted wilt",
            dotColor = TileToneWilt,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FarmBlueprintToolbar(
    hasLayout: Boolean,
    onEditLayout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (hasLayout) 1f else 0.65f }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Farm blueprint",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF222222)
                )
                Text(
                    text = "Tap a plant to scan, save, and track it.",
                    fontSize = 13.sp,
                    color = FarmWorkspaceSubtitle,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (hasLayout) {
                Surface(
                    onClick = onEditLayout,
                    shape = RoundedCornerShape(50),
                    color = TomatoShell,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Edit layout",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FarmLegendChip(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(FarmLegendChipBg)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = FarmLegendText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 12.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FarmTile(
    tileNumber: Int,
    isSelected: Boolean,
    result: TileScanResult?,
    plantName: String = "",
    tileSize: Dp,
    notificationCount: Int = 0,
    onNotify: () -> Unit = {},
    onDelete: () -> Unit = {},
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val hasResult = result != null && result.percent > 0
    val hasSavedPlant = plantName.isNotBlank() || result != null
    val background = tileResultTone(result)
    val labelColor = if (hasResult) Color.White else Color.Black
    val contentScale = (tileSize.value / 80f).coerceIn(0.35f, 1f)
    val actionSize = (tileSize * 0.24f).coerceIn(14.dp, 22.dp)
    val emojiSize = (if (hasResult) 30.dp else 26.dp) * contentScale
    Box(modifier = Modifier.size(tileSize)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = onClick,
                    onDoubleClick = onDoubleClick
                ),
            shape = RoundedCornerShape(12.dp * contentScale),
            color = background,
            border = if (isSelected) BorderStroke(2.dp, TomatoGreen) else null,
            shadowElevation = if (isSelected) 4.dp else 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 4.dp * contentScale,
                        end = 4.dp * contentScale,
                        top = if (isSelected && hasSavedPlant) {
                            actionSize * 0.35f
                        } else {
                            3.dp * contentScale
                        },
                        bottom = 3.dp * contentScale
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(tileResultEmoji(result)),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(emojiSize)
                )
                Text(
                    text = plantName.ifBlank { if (hasResult) "#$tileNumber" else "$tileNumber" },
                    fontSize = (if (hasResult) 11.sp else 12.sp) * contentScale,
                    fontWeight = FontWeight.Bold,
                    color = labelColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (hasSavedPlant && isSelected) {
            TileActionButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp),
                size = actionSize,
                icon = Icons.Default.Notifications,
                contentDescription = "Notifications for Tile $tileNumber",
                tint = if (notificationCount > 0) TomatoRed else TomatoGreen,
                badgeCount = notificationCount,
                onClick = onNotify
            )
            TileActionButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                size = actionSize,
                icon = Icons.Default.Delete,
                contentDescription = "Delete Tile $tileNumber",
                tint = TomatoRed,
                onClick = onDelete
            )
        } else if (hasSavedPlant && notificationCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size((tileSize * 0.28f).coerceIn(14.dp, 18.dp))
                    .clip(CircleShape)
                    .background(TomatoRed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = notificationCount.coerceAtMost(9).toString(),
                    color = Color.White,
                    fontSize = (tileSize.value * 0.16f).coerceIn(8f, 11f).sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 10.sp
                )
            }
        }
    }
}

@Composable
fun FarmGridView(
    rows: Int,
    columns: Int,
    selectedTile: Int?,
    tileScanHistory: TileScanHistory,
    plantNames: Map<Int, String> = emptyMap(),
    scanCalendar: ScanCalendar = ScanCalendar(),
    onTileSelected: (Int) -> Unit,
    onTileDoubleTap: (Int) -> Unit = {},
    onNotifyTile: (Int) -> Unit = {},
    onDeleteTile: (Int) -> Unit = {}
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val spacing = 8.dp
        val inset = 12.dp
        val rowCount = rows.coerceAtLeast(1)
        val columnCount = columns.coerceAtLeast(1)
        val widthBudget = (maxWidth - inset * 2 - spacing * (columnCount - 1)).coerceAtLeast(1.dp)
        val heightBudget = (maxHeight - inset * 2 - spacing * (rowCount - 1)).coerceAtLeast(1.dp)
        val fitTile = minOf(widthBudget / columnCount, heightBudget / rowCount, 80.dp)
            .coerceAtLeast(1.dp)
        val maxZoom = (96.dp / fitTile).coerceIn(2.5f, 6f)

        var scale by remember(rows, columns) { mutableFloatStateOf(1f) }
        var offset by remember(rows, columns) { mutableStateOf(Offset.Zero) }
        var containerWidth by remember { mutableFloatStateOf(0f) }
        var containerHeight by remember { mutableFloatStateOf(0f) }
        var contentWidth by remember { mutableFloatStateOf(0f) }
        var contentHeight by remember { mutableFloatStateOf(0f) }

        fun clampOffset(candidate: Offset, atScale: Float): Offset {
            val maxX = ((contentWidth * atScale - containerWidth) / 2f).coerceAtLeast(0f)
            val maxY = ((contentHeight * atScale - containerHeight) / 2f).coerceAtLeast(0f)
            return Offset(
                x = candidate.x.coerceIn(-maxX, maxX),
                y = candidate.y.coerceIn(-maxY, maxY)
            )
        }

        fun applyTransform(zoomChange: Float, previousFocal: Offset, focal: Offset) {
            val oldScale = scale
            if (oldScale <= 0f) return
            val newScale = (oldScale * zoomChange).coerceIn(1f, maxZoom)
            val viewCenter = Offset(containerWidth / 2f, containerHeight / 2f)
            val ratio = newScale / oldScale
            val next = focal - viewCenter - (previousFocal - viewCenter - offset) * ratio
            scale = newScale
            offset = if (newScale <= 1.001f) Offset.Zero else clampOffset(next, newScale)
        }

        Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerWidth = size.width.toFloat()
                containerHeight = size.height.toFloat()
            }
            .pointerInput(rows, columns, maxZoom) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    var transforming = false
                    var pastSlop = false
                    var accumulated = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (transforming) event.changes.forEach { it.consume() }
                            break
                        }
                        if (pressed.size >= 2) {
                            transforming = true
                            pastSlop = true
                            val first = pressed[0]
                            val second = pressed[1]
                            val previousDistance = (first.previousPosition - second.previousPosition)
                                .getDistance()
                                .coerceAtLeast(1f)
                            val nextDistance = (first.position - second.position)
                                .getDistance()
                                .coerceAtLeast(1f)
                            val centroid = (first.position + second.position) / 2f
                            val previousCentroid = (first.previousPosition + second.previousPosition) / 2f
                            event.changes.forEach { if (it.pressed) it.consume() }
                            applyTransform(
                                zoomChange = nextDistance / previousDistance,
                                previousFocal = previousCentroid,
                                focal = centroid
                            )
                        } else if (pastSlop && scale > 1.02f) {
                            val change = pressed[0]
                            transforming = true
                            change.consume()
                            applyTransform(1f, change.previousPosition, change.position)
                        } else {
                            val change = pressed[0]
                            accumulated += change.position - change.previousPosition
                            if (
                                accumulated.getDistance() > touchSlop &&
                                scale > 1.02f
                            ) {
                                pastSlop = true
                                transforming = true
                                change.consume()
                                applyTransform(1f, change.previousPosition, change.position)
                            }
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .onSizeChanged { size ->
                    contentWidth = size.width.toFloat()
                    contentHeight = size.height.toFloat()
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(rows) { rowIndex ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(columns) { colIndex ->
                            val tileNumber = getTileNumber(rowIndex, colIndex, rows, columns)
                            FarmTile(
                                tileNumber = tileNumber,
                                isSelected = selectedTile == tileNumber,
                                result = tileScanHistory.latestFor(tileNumber),
                                plantName = plantNames[tileNumber].orEmpty(),
                                tileSize = fitTile,
                                notificationCount = tileNotificationMessages(
                                    tileNumber,
                                    tileScanHistory,
                                    scanCalendar
                                ).size,
                                onNotify = { onNotifyTile(tileNumber) },
                                onDelete = { onDeleteTile(tileNumber) },
                                onClick = { onTileSelected(tileNumber) },
                                onDoubleClick = { onTileDoubleTap(tileNumber) }
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TileActionButton(
    size: Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    badgeCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.94f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size * 0.62f)
            )
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(size * 0.42f)
                    .clip(CircleShape)
                    .background(TomatoRed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeCount.coerceAtMost(9).toString(),
                    color = Color.White,
                    fontSize = (size.value * 0.32f).coerceIn(5f, 11f).sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (size.value * 0.32f).coerceIn(5f, 11f).sp
                )
            }
        }
    }
}

@Composable
fun TileNotificationDialog(
    tileNumber: Int,
    plantName: String,
    messages: List<String>,
    scanCalendar: ScanCalendar = ScanCalendar(),
    onDismiss: () -> Unit
) {
    val plant = plantName.ifBlank { "Tile $tileNumber" }
    val dateLabel = remember(
        scanCalendar.enabled,
        scanCalendar.selected,
        scanCalendar.nextDue,
        scanCalendar.anchor
    ) {
        formatScanDueLabel(scanCalendar.asOfIso())
    }

    val cream = Color(0xFFF9F8F5)
    val brickRed = Color(0xFFA32E27)
    val dateRed = Color(0xFF95372C)
    val charcoal = Color(0xFF1A2B34)
    val panelFill = Color(0xFFF2EEE9)
    val panelBorder = Color(0xFFE6E1DC)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = cream,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.login_logo),
                    contentDescription = "TomatoInsight logo",
                    modifier = Modifier.size(92.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = dateLabel,
                    color = dateRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = plant,
                    color = charcoal,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (messages.isEmpty()) {
                    Text(
                        text = "No notifications for this plant right now.",
                        color = charcoal.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        messages.forEach { message ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(panelFill)
                                    .border(1.dp, panelBorder, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(brickRed)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = message,
                                    color = charcoal,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = brickRed),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Close",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

fun tileNotificationMessages(
    tileNumber: Int,
    history: TileScanHistory,
    calendar: ScanCalendar
): List<String> {
    val messages = mutableListOf<String>()
    val latest = history.latestFor(tileNumber) ?: return messages
    latest.scores.forEach { score ->
        if (score.percent <= 0 || score.name.equals("Healthy", ignoreCase = true)) return@forEach
        val label = when {
            score.name.contains("yellow leaf curl", ignoreCase = true) -> "TLCV"
            score.name.contains("early blight", ignoreCase = true) -> "Early blight"
            score.name.contains("spotted wilt", ignoreCase = true) -> "Spotted wilt"
            else -> score.name
        }
        messages += if (score.percent >= 100) {
            "$label 100%. Remove this leaf now."
        } else {
            "$label ${score.percent}% detected."
        }
    }
    // Same per-plant due logic as website farm-notifications.js / reminderWeekForPlant.
    val nextWeek = reminderWeekForPlant(
        calendar = calendar,
        latestWeek = latest.weekNumber,
        scannedAtMillis = latest.scannedAtMillis,
        scanDayIso = latest.scanDayIso
    )
    if (nextWeek != null) {
        val savedWeeks = history.scansFor(tileNumber).mapNotNull { it.weekNumber }.toSet()
        if (nextWeek !in savedWeeks) {
            messages += "Week $nextWeek needs to be scanned again for this plant (Tile $tileNumber)."
        }
    }
    return messages
}
