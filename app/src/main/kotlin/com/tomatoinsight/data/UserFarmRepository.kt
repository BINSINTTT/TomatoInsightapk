package com.tomatoinsight.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tomatoinsight.api.DiseaseScore
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.data.local.FarmDeletionEntity
import com.tomatoinsight.data.local.FarmMetaEntity
import com.tomatoinsight.data.local.PlantNameEntity
import com.tomatoinsight.data.local.TileScanEntity
import com.tomatoinsight.data.local.TomatoInsightDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class LoadedFarmData(
    val rows: Int = 0,
    val columns: Int = 0,
    val history: Map<Int, List<TileScanResult>> = emptyMap(),
    val plantNames: Map<Int, String> = emptyMap(),
    val deletions: List<FarmDeletion> = emptyList()
)

/** Legacy JSON shape used only to migrate older installs into SQLite. */
private data class LegacyFarmData(
    val rows: Int = 0,
    val columns: Int = 0,
    val scans: List<LegacyTileScan> = emptyList(),
    val plantNames: Map<String, String> = emptyMap(),
    val deletions: List<LegacyDeletion> = emptyList()
)

private data class LegacyDeletion(
    val id: String = "",
    val tileNumber: Int = 0,
    val plantName: String = "",
    val deletedAtMillis: Long = 0L,
    val deletedAtLabel: String = ""
)

private data class LegacyTileScan(
    val scanId: Long,
    val scannedAtMillis: Long,
    val tileNumber: Int,
    val weekNumber: Int? = null,
    val diseaseName: String,
    val abbreviation: String,
    val percent: Int,
    val isHealthy: Boolean,
    val scores: List<DiseaseScore>,
    val response: ValidationResponse,
    val imageFileName: String? = null
)

/**
 * Local farm storage on the phone.
 * Scan history, plant names, and deletions live in SQLite (Room).
 * Photos stay as JPEG files under the app's private files directory.
 */
class UserFarmRepository(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val dao = TomatoInsightDatabase.get(appContext).farmDao()
    private val scoresType = object : TypeToken<List<DiseaseScore>>() {}.type

    suspend fun loadForUser(userId: String): LoadedFarmData = withContext(Dispatchers.IO) {
        migrateLegacyJsonIfNeeded(userId)

        val meta = dao.getMeta(userId) ?: return@withContext LoadedFarmData()
        val history = dao.getScans(userId)
            .map { it.toTileScanResult() }
            .groupBy { it.tileNumber }
            .mapValues { (_, scans) -> scans.sortedByDescending { it.scannedAtMillis } }
        val names = dao.getPlantNames(userId)
            .mapNotNull { entity ->
                val name = entity.name.trim()
                if (name.isEmpty()) null else entity.tileNumber to name
            }
            .toMap()
        val deletions = dao.getDeletions(userId).map { it.toFarmDeletion() }

        LoadedFarmData(
            rows = meta.rows,
            columns = meta.columns,
            history = history,
            plantNames = names,
            deletions = deletions
        )
    }

    suspend fun deleteScanImages(userId: String, scanIds: List<Long>) = withContext(Dispatchers.IO) {
        if (scanIds.isEmpty()) return@withContext
        scanIds.forEach { scanId ->
            File(imagesDir(userId), "$scanId.jpg").delete()
        }
        dao.deleteScans(userId, scanIds)
    }

    /** Wipes this uid's local farm rows (used after signup so a new account starts empty). */
    suspend fun clearForUser(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        val scanIds = dao.getScans(userId).map { it.scanId }
        if (scanIds.isNotEmpty()) {
            scanIds.forEach { scanId ->
                File(imagesDir(userId), "$scanId.jpg").delete()
            }
        }
        dao.clearUserFarm(userId)
        farmDataFile(userId).delete()
        imagesDir(userId).deleteRecursively()
    }

    suspend fun saveForUser(
        userId: String,
        rows: Int,
        columns: Int,
        history: Map<Int, List<TileScanResult>>,
        plantNames: Map<Int, String> = emptyMap(),
        deletions: List<FarmDeletion> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val scans = history.values
            .flatten()
            .sortedByDescending { it.scannedAtMillis }
            .map { scan ->
                val imageFileName = scan.image?.let { bitmap ->
                    saveScanImage(userId, scan.scanId, bitmap)
                } ?: findExistingImageFileName(userId, scan.scanId)
                scan.toEntity(userId, imageFileName)
            }

        val names = plantNames.mapNotNull { (tile, value) ->
            val name = value.trim()
            if (name.isEmpty()) null else PlantNameEntity(userId, tile, name)
        }
        val deletionEntities = deletions.map { it.toEntity(userId) }

        dao.replaceUserFarm(
            meta = FarmMetaEntity(userId = userId, rows = rows, columns = columns),
            scans = scans,
            plantNames = names,
            deletions = deletionEntities
        )

        // Drop legacy JSON once SQLite is the source of truth.
        farmDataFile(userId).delete()
    }

    private suspend fun migrateLegacyJsonIfNeeded(userId: String) {
        if (dao.getMeta(userId) != null) return
        val dataFile = farmDataFile(userId)
        if (!dataFile.exists()) return

        runCatching {
            val legacy = gson.fromJson(dataFile.readText(), LegacyFarmData::class.java)
                ?: return
            val scans = legacy.scans.map { scan ->
                TileScanEntity(
                    userId = userId,
                    scanId = scan.scanId,
                    scannedAtMillis = scan.scannedAtMillis,
                    tileNumber = scan.tileNumber,
                    weekNumber = scan.weekNumber,
                    diseaseName = scan.diseaseName,
                    abbreviation = scan.abbreviation,
                    percent = scan.percent,
                    isHealthy = scan.isHealthy,
                    scoresJson = gson.toJson(scan.scores),
                    responseJson = gson.toJson(scan.response),
                    imageFileName = scan.imageFileName
                        ?: findExistingImageFileName(userId, scan.scanId)
                )
            }
            val names = legacy.plantNames.mapNotNull { (key, value) ->
                val tile = key.toIntOrNull() ?: return@mapNotNull null
                val name = value.trim()
                if (name.isEmpty()) null else PlantNameEntity(userId, tile, name)
            }
            val deletions = legacy.deletions.map { deletion ->
                FarmDeletionEntity(
                    userId = userId,
                    id = deletion.id.ifBlank { "${deletion.tileNumber}-${deletion.deletedAtMillis}" },
                    tileNumber = deletion.tileNumber,
                    plantName = deletion.plantName,
                    deletedAtMillis = deletion.deletedAtMillis,
                    deletedAtLabel = deletion.deletedAtLabel
                )
            }
            dao.replaceUserFarm(
                meta = FarmMetaEntity(userId, legacy.rows, legacy.columns),
                scans = scans,
                plantNames = names,
                deletions = deletions
            )
            dataFile.delete()
            Log.i(TAG, "Migrated legacy farm JSON into SQLite for user $userId")
        }.onFailure { error ->
            Log.e(TAG, "Failed to migrate legacy farm JSON for $userId", error)
        }
    }

    private fun farmDataFile(userId: String): File =
        File(appContext.filesDir, "users/$userId/farm_data.json")

    private fun imagesDir(userId: String): File =
        File(appContext.filesDir, "users/$userId/images").also { it.mkdirs() }

    private fun saveScanImage(userId: String, scanId: Long, bitmap: Bitmap): String {
        val fileName = "$scanId.jpg"
        val file = File(imagesDir(userId), fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        return fileName
    }

    private fun findExistingImageFileName(userId: String, scanId: Long): String? {
        val file = File(imagesDir(userId), "$scanId.jpg")
        return file.name.takeIf { file.exists() }
    }

    private fun loadScanImage(userId: String, fileName: String?): Bitmap? {
        if (fileName.isNullOrBlank()) return null
        val file = File(imagesDir(userId), fileName)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun TileScanEntity.toTileScanResult(): TileScanResult {
        val scores = runCatching {
            gson.fromJson<List<DiseaseScore>>(scoresJson, scoresType).orEmpty()
        }.getOrElse { emptyList() }
        val response = runCatching {
            gson.fromJson(responseJson, ValidationResponse::class.java)
        }.getOrNull() ?: ValidationResponse(result = "Error", message = "Stored scan could not be read.")

        return TileScanResult(
            scanId = scanId,
            scannedAtMillis = scannedAtMillis,
            scanDayIso = scanDayIso,
            tileNumber = tileNumber,
            weekNumber = weekNumber,
            diseaseName = diseaseName,
            abbreviation = abbreviation,
            percent = percent,
            isHealthy = isHealthy,
            scores = scores,
            response = response,
            image = loadScanImage(userId, imageFileName)
        )
    }

    private fun TileScanResult.toEntity(userId: String, imageFileName: String?): TileScanEntity =
        TileScanEntity(
            userId = userId,
            scanId = scanId,
            scannedAtMillis = scannedAtMillis,
            tileNumber = tileNumber,
            weekNumber = weekNumber,
            diseaseName = diseaseName,
            abbreviation = abbreviation,
            percent = percent,
            isHealthy = isHealthy,
            scoresJson = gson.toJson(scores),
            responseJson = gson.toJson(response),
            imageFileName = imageFileName,
            scanDayIso = scanDayIso
        )

    private fun FarmDeletionEntity.toFarmDeletion(): FarmDeletion =
        FarmDeletion(
            id = id.ifBlank { "$tileNumber-$deletedAtMillis" },
            tileNumber = tileNumber,
            plantName = plantName,
            deletedAtMillis = deletedAtMillis,
            deletedAtLabel = deletedAtLabel
        )

    private fun FarmDeletion.toEntity(userId: String): FarmDeletionEntity =
        FarmDeletionEntity(
            userId = userId,
            id = id.ifBlank { "$tileNumber-$deletedAtMillis" },
            tileNumber = tileNumber,
            plantName = plantName,
            deletedAtMillis = deletedAtMillis,
            deletedAtLabel = deletedAtLabel
        )

    companion object {
        private const val TAG = "UserFarmRepository"
    }
}
