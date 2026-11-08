package com.tomatoinsight.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatoinsight.R
import com.tomatoinsight.ui.theme.TomatoGreen
import com.tomatoinsight.ui.theme.TomatoRed

private fun getTileNumber(r: Int, c: Int, rows: Int, columns: Int): Int {
    val fromBottomRow = (rows - 1 - r)
    val isEvenFromBottom = fromBottomRow % 2 == 0
    val colOffset = if (isEvenFromBottom) (columns - 1 - c) else c
    return fromBottomRow * columns + colOffset + 1
}

private fun tileNumbersForLayout(rows: Int, columns: Int): Set<Int> {
    if (rows <= 0 || columns <= 0) return emptySet()
    return buildSet {
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                add(getTileNumber(r, c, rows, columns))
            }
        }
    }
}

/** Same rule as website `tilesRemovedByLayout`: tiles with data that would fall outside the new grid. */
fun tilesRemovedByLayout(
    rows: Int,
    columns: Int,
    occupiedTiles: Map<Int, String>
): List<Int> {
    val kept = tileNumbersForLayout(rows, columns)
    return occupiedTiles.keys
        .filter { it !in kept }
        .sorted()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlueprintScreen(
    onNavigateBack: () -> Unit,
    onSaveSuccess: (Int, Int) -> Unit,
    initialRows: Int = 0,
    initialColumns: Int = 0,
    /** tileNumber → display name for plants that still have saved data. */
    occupiedTiles: Map<Int, String> = emptyMap()
) {
    val isExpanding = initialRows > 0 && initialColumns > 0
    var rows by remember { mutableIntStateOf(if (isExpanding) initialRows else 3) }
    var columns by remember { mutableIntStateOf(if (isExpanding) initialColumns else 3) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    fun plantLabel(tile: Int): String =
        occupiedTiles[tile]?.trim()?.ifBlank { null } ?: "Tile $tile"

    fun refreshBlockedNote() {
        blockedMessage = null
    }

    fun attemptSave() {
        val affected = tilesRemovedByLayout(rows, columns, occupiedTiles)
        if (affected.isEmpty()) {
            blockedMessage = null
            onSaveSuccess(rows, columns)
            return
        }
        val names = affected.map(::plantLabel)
        blockedMessage = if (names.size == 1) {
            "Layout can't be changed because ${names[0]} still has data and would be removed."
        } else {
            "Layout can't be changed because these tiles still have data and would be removed: ${names.joinToString(", ")}."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 2.dp
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isExpanding) "Expand Farm Layout" else "Farm Layout Setup",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF222222)
        )
        Text(
            text = if (isExpanding) {
                "Add more rows or columns to grow your farm grid. Existing tile scans are kept. Shrinking is blocked if it would remove a saved plant."
            } else {
                "Configure rows and columns to arrange your farm blueprint."
            },
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CounterControl(
                label = "Rows",
                count = rows,
                onIncrement = {
                    if (rows < 10) {
                        rows++
                        refreshBlockedNote()
                    }
                },
                onDecrement = {
                    if (rows > 1) {
                        rows--
                        refreshBlockedNote()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            CounterControl(
                label = "Columns",
                count = columns,
                onIncrement = {
                    if (columns < 10) {
                        columns++
                        refreshBlockedNote()
                    }
                },
                onDecrement = {
                    if (columns > 1) {
                        columns--
                        refreshBlockedNote()
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (!blockedMessage.isNullOrBlank()) {
            Text(
                text = blockedMessage!!,
                color = TomatoRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Live Blueprint Preview (${rows * columns} Plants)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val totalCells = rows * columns
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        items(totalCells) { index ->
                            val r = index / columns
                            val c = index % columns
                            val tileNumber = getTileNumber(r, c, rows, columns)
                            val hasData = tileNumber in occupiedTiles

                            Surface(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = if (hasData) Color(0xFFFFF3E0) else Color(0xFFF1F8E9),
                                shadowElevation = 1.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_tomato_plant),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = "$tileNumber",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { attemptSave() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TomatoRed)
        ) {
            Text(
                text = "Save Blueprint",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun CounterControl(
    label: String,
    count: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontWeight = FontWeight.SemiBold, color = Color(0xFF424242), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = onDecrement,
                shape = CircleShape,
                color = Color(0xFFEEEEEE),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease $label", tint = Color(0xFF424242))
                }
            }
            Text(
                text = count.toString(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF222222),
                modifier = Modifier.widthIn(min = 28.dp)
            )
            Surface(
                onClick = onIncrement,
                shape = CircleShape,
                color = TomatoGreen.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = "Increase $label", tint = TomatoGreen)
                }
            }
        }
    }
}
