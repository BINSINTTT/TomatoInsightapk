package com.tomatoinsight.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.tomatoinsight.api.DiseasePrediction
import com.tomatoinsight.api.DiseaseScore
import com.tomatoinsight.api.ValidationResponse
import com.tomatoinsight.api.diseaseAbbreviation
import com.tomatoinsight.api.isHealthyDisease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class FarmerProfile(
    val firstName: String = "",
    val middleInitial: String = "",
    val lastName: String = "",
    val farmName: String = "",
    val email: String = "",
    val photo: Bitmap? = null
) {
    val displayName: String
        get() = listOf(firstName, middleInitial, lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}

data class ScanCalendar(
    val enabled: Boolean = false,
    val anchor: String = "",
    val nextDue: String = "",
    val selected: String = ""
) {
    fun dashboardNote(): String = ""

    /**
     * Farm week implied by the dates on the scan calendar.
     * Anchor alone = Week 4; selected/nextDue advance by full weeks from the anchor.
     */
    fun resolvedFarmWeek(): Int? {
        if (!enabled) return null
        val date = when {
            selected.isNotBlank() -> selected
            nextDue.isNotBlank() -> nextDue
            anchor.isNotBlank() -> anchor
            else -> return null
        }
        if (anchor.isBlank()) return FIRST_FARM_WEEK
        val start = parseScanCalendarDay(anchor) ?: return FIRST_FARM_WEEK
        val end = parseScanCalendarDay(date) ?: return FIRST_FARM_WEEK
        val days = ((end - start) / 86_400_000L).toInt()
        if (days < 0) return FIRST_FARM_WEEK
        return (FIRST_FARM_WEEK + days / 7).coerceIn(FIRST_FARM_WEEK, LAST_FARM_WEEK)
    }
}

private fun parseScanCalendarDay(iso: String): Long? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(iso)) return null
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { isLenient = false }
            .parse(iso)
            ?.time
    }.getOrNull()
}

data class FarmDeletion(
    val id: String,
    val tileNumber: Int,
    val plantName: String,
    val deletedAtMillis: Long,
    val deletedAtLabel: String
)

data class CloudFarm(
    val rows: Int = 0,
    val columns: Int = 0,
    val plantNames: Map<Int, String> = emptyMap(),
    val recommendations: Map<Int, SavedRecommendation> = emptyMap(),
    val history: TileScanHistory = emptyMap(),
    val deletions: List<FarmDeletion> = emptyList(),
    val profile: FarmerProfile = FarmerProfile(),
    val selectedFarmWeek: Int = FIRST_FARM_WEEK,
    val scanCalendar: ScanCalendar = ScanCalendar(),
    val plantNamesFromCloud: Boolean = false
) {
    val hasSharedData: Boolean
        get() = rows > 0 || columns > 0 || plantNames.isNotEmpty() || history.isNotEmpty() ||
            deletions.isNotEmpty() || profile.displayName.isNotBlank() ||
            profile.farmName.isNotBlank() || profile.photo != null || scanCalendar.enabled
}

data class CloudFarmUpdate(
    val farm: CloudFarm = CloudFarm(),
    val error: String? = null
)

private data class RawCloudBundle(
    val user: Map<String, Any>?,
    val tiles: Map<String, Map<String, Any>>,
    val deletions: List<Map<String, Any>>,
    val error: String?
)

private data class WeekSnap(
    val weekNumber: Int,
    val savedAt: String?,
    val scanDayIso: String = "",
    val scores: Map<String, Double>,
    val topBarKey: String?,
    val topPct: Double,
    val imageDataUrl: String?
)

class FirestoreFarmRepository {

    fun observe(uid: String): Flow<CloudFarmUpdate> = callbackFlow {
        val db = FirebaseFirestore.getInstance()
        val userRef = db.collection("users").document(uid)
        var user: Map<String, Any>? = null
        var tiles: Map<String, Map<String, Any>> = emptyMap()
        var deletions: List<Map<String, Any>> = emptyList()
        var error: String? = null
        var seenUser = false
        var seenTiles = false
        var seenDeletions = false

        fun publish() {
            if (!seenUser || !seenTiles || !seenDeletions) return
            trySend(RawCloudBundle(user, tiles, deletions, error))
        }

        val registrations = mutableListOf<ListenerRegistration>()
        registrations += userRef.addSnapshotListener { snapshot, failure ->
            if (failure != null) error = firestoreError(failure)
            user = snapshot?.data
            seenUser = true
            publish()
        }
        registrations += userRef.collection("tiles").addSnapshotListener { snapshot, failure ->
            if (failure != null) error = firestoreError(failure)
            tiles = snapshot?.documents?.associate { doc ->
                doc.id to doc.data.orEmpty()
            }.orEmpty()
            seenTiles = true
            publish()
        }
        registrations += userRef.collection("deletions").addSnapshotListener { snapshot, failure ->
            if (failure != null) error = firestoreError(failure)
            deletions = snapshot?.documents?.map { doc ->
                doc.data.orEmpty().toMutableMap().apply { put("id", doc.id) }
            }.orEmpty()
            seenDeletions = true
            publish()
        }

        awaitClose { registrations.forEach { it.remove() } }
    }.map { bundle ->
        withContext(Dispatchers.IO) {
            if (bundle.error != null && bundle.user == null && bundle.tiles.isEmpty()) {
                CloudFarmUpdate(error = bundle.error)
            } else {
                CloudFarmUpdate(farm = bundle.toCloudFarm(), error = bundle.error)
            }
        }
    }

    suspend fun deleteTileData(
        uid: String,
        tileNumber: Int,
        plantName: String,
        deletionId: String? = null
    ) = withContext(Dispatchers.IO) {
        val db = FirebaseFirestore.getInstance()
        val userRef = db.collection("users").document(uid)
        val tileId = tileNumber.toString()
        val id = deletionId?.ifBlank { null } ?: "$tileNumber-${System.currentTimeMillis()}"
        val deletedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val deletion = mapOf(
            "id" to id,
            "tileNum" to tileNumber,
            "plantName" to plantName,
            "deletedAt" to deletedAt
        )
        val tileRef = userRef.collection("tiles").document(tileId)
        val deletionRef = userRef.collection("deletions").document(id)
        val plantNamePath = "plantNames.$tileId"
        val batch = db.batch()
        batch.delete(tileRef)
        batch.set(deletionRef, deletion)
        batch.update(userRef, plantNamePath, com.google.firebase.firestore.FieldValue.delete())
        try {
            Tasks.await(batch.commit())
        } catch (_: Exception) {
            Tasks.await(tileRef.delete())
            Tasks.await(deletionRef.set(deletion))
            try {
                Tasks.await(
                    userRef.update(plantNamePath, com.google.firebase.firestore.FieldValue.delete())
                )
            } catch (_: Exception) {
                Tasks.await(
                    userRef.set(
                        mapOf(
                            "plantNames" to mapOf(
                                tileId to com.google.firebase.firestore.FieldValue.delete()
                            )
                        ),
                        SetOptions.merge()
                    )
                )
            }
        }
    }

    suspend fun saveLayout(uid: String, rows: Int, columns: Int) = withContext(Dispatchers.IO) {
        val userRef = FirebaseFirestore.getInstance().collection("users").document(uid)
        Tasks.await(
            userRef.set(
                mapOf(
                    "layout" to mapOf(
                        "rows" to rows,
                        "columns" to columns,
                        "updatedAt" to isoTimestamp(System.currentTimeMillis())
                    )
                ),
                SetOptions.merge()
            )
        )
    }

    suspend fun saveSelectedFarmWeek(uid: String, weekNumber: Int) = withContext(Dispatchers.IO) {
        val userRef = FirebaseFirestore.getInstance().collection("users").document(uid)
        Tasks.await(
            userRef.set(
                mapOf("selectedFarmWeek" to weekNumber),
                SetOptions.merge()
            )
        )
    }

    suspend fun saveProfile(
        uid: String,
        profile: FarmerProfile,
        includePhoto: Boolean
    ) = withContext(Dispatchers.IO) {
        val userRef = FirebaseFirestore.getInstance().collection("users").document(uid)
        val fields = mutableMapOf<String, Any>(
            "updatedAt" to isoTimestamp(System.currentTimeMillis())
        )
        if (includePhoto) {
            // Photo-only saves must not blank out names already stored in Firestore.
            profile.firstName.trim().takeIf { it.isNotEmpty() }?.let { fields["firstName"] = it }
            profile.middleInitial.trim().uppercase().take(1).takeIf { it.isNotEmpty() }
                ?.let { fields["middleInitial"] = it }
            profile.lastName.trim().takeIf { it.isNotEmpty() }?.let { fields["lastName"] = it }
            profile.farmName.trim().takeIf { it.isNotEmpty() }?.let { fields["farmName"] = it }
            profile.email.trim().takeIf { it.isNotEmpty() }?.let { fields["email"] = it }
            fields["photo"] = profile.photo?.toProfilePhotoDataUrl().orEmpty()
        } else {
            fields["firstName"] = profile.firstName.trim()
            fields["middleInitial"] = profile.middleInitial.trim().uppercase().take(1)
            fields["lastName"] = profile.lastName.trim()
            fields["farmName"] = profile.farmName.trim()
            fields["email"] = profile.email.trim()
        }
        Tasks.await(userRef.set(fields, SetOptions.merge()))
    }

    /** One server read of the shared farm so offline phone saves can merge with website tiles. */
    suspend fun fetchFarm(uid: String): CloudFarm = withContext(Dispatchers.IO) {
        val db = FirebaseFirestore.getInstance()
        val userRef = db.collection("users").document(uid)
        val userSnap = Tasks.await(userRef.get(Source.SERVER))
        val tileSnap = Tasks.await(userRef.collection("tiles").get(Source.SERVER))
        val deletionSnap = Tasks.await(userRef.collection("deletions").get(Source.SERVER))
        RawCloudBundle(
            user = userSnap.data,
            tiles = tileSnap.documents.associate { doc -> doc.id to doc.data.orEmpty() },
            deletions = deletionSnap.documents.map { doc ->
                doc.data.orEmpty().toMutableMap().apply { put("id", doc.id) }
            },
            error = null
        ).toCloudFarm()
    }

    /**
     * Merges the phone farm with Firestore, then uploads only live tiles.
     * Website scans stay; offline phone scans are added; deletes apply both ways.
     */
    suspend fun syncLocalFarm(
        uid: String,
        rows: Int,
        columns: Int,
        plantNames: Map<Int, String>,
        history: Map<Int, List<TileScanResult>>,
        profile: FarmerProfile,
        calendar: ScanCalendar,
        week: Int,
        deletions: List<FarmDeletion> = emptyList()
    ) {
        if (rows > 0 && columns > 0) saveLayout(uid, rows, columns)
        if (week in FIRST_FARM_WEEK..LAST_FARM_WEEK) saveSelectedFarmWeek(uid, week)
        if (profile.displayName.isNotBlank() || profile.farmName.isNotBlank() || profile.photo != null) {
            saveProfile(uid, profile, includePhoto = profile.photo != null)
        }
        saveScanCalendar(uid, calendar)

        val cloud = runCatching { fetchFarm(uid) }.getOrDefault(CloudFarm())
        val allDeletions = mergeDeletions(deletions, cloud.deletions)
        val removedTiles = activeDeletedTiles(allDeletions, history, cloud.history)
        val mergedHistory = mergeScanHistories(history, cloud.history, removedTiles)
        val mergedNames = mergePlantNames(
            local = plantNames,
            cloud = cloud.plantNames,
            localHistory = history,
            cloudHistory = cloud.history,
            deletedTiles = removedTiles
        )

        mergedHistory.forEach { (tile, scans) ->
            if (tile in removedTiles) return@forEach
            if (scans.isNotEmpty()) {
                saveTileScan(uid, tile, scans, mergedNames[tile].orEmpty())
            }
        }
        // Only push deletes for tiles Firestore still has, so the phone never restores them.
        allDeletions
            .filter { it.tileNumber in removedTiles }
            .groupBy { it.tileNumber }
            .forEach { (tile, entries) ->
                val stillOnCloud = cloud.history.containsKey(tile) || cloud.plantNames.containsKey(tile)
                if (!stillOnCloud) return@forEach
                val entry = entries.maxBy { it.deletedAtMillis }
                deleteTileData(uid, entry.tileNumber, entry.plantName, entry.id)
            }
        withContext(Dispatchers.IO) {
            Tasks.await(
                FirebaseFirestore.getInstance().collection("users").document(uid).get(Source.SERVER)
            )
        }
    }

    suspend fun saveScanCalendar(uid: String, calendar: ScanCalendar) = withContext(Dispatchers.IO) {
        val userRef = FirebaseFirestore.getInstance().collection("users").document(uid)
        Tasks.await(
            userRef.set(
                mapOf(
                    "scanCalendar" to mapOf(
                        "enabled" to calendar.enabled,
                        "anchor" to calendar.anchor,
                        "nextDue" to calendar.nextDue,
                        "selected" to calendar.selected
                    )
                ),
                SetOptions.merge()
            )
        )
    }

    /**
     * Writes one tile in the same shape the website stores:
     * scores, top disease, week history, and the photo.
     */
    suspend fun saveTileScan(
        uid: String,
        tileNumber: Int,
        scans: List<TileScanResult>,
        plantName: String
    ) = withContext(Dispatchers.IO) {
        if (scans.isEmpty()) return@withContext
        val userRef = FirebaseFirestore.getInstance().collection("users").document(uid)
        val byWeek = linkedMapOf<Int, TileScanResult>()
        scans.sortedBy { it.scannedAtMillis }.forEach { scan ->
            val week = canonicalWeek(scan.weekNumber) ?: FIRST_FARM_WEEK
            byWeek[week] = scan
        }
        val tileRef = userRef.collection("tiles").document(tileNumber.toString())
        val existing = runCatching { Tasks.await(tileRef.get()).data }.getOrNull().orEmpty()
        val existingWeeks = existing["weeks"].asMap().orEmpty()
        val latest = byWeek.values.maxByOrNull { it.scannedAtMillis }
            ?: scans.maxBy { it.scannedAtMillis }
        val weeks = linkedMapOf<String, Map<String, Any?>>()
        existingWeeks.forEach { (key, value) ->
            val week = canonicalWeek(key.toIntOrNull() ?: value.asMap()?.get("weekNumber").asInt())
                ?: return@forEach
            if (week !in byWeek) {
                val kept = value.asMap() ?: return@forEach
                weeks[week.toString()] = kept
            }
        }
        byWeek.forEach { (week, scan) ->
            weeks[week.toString()] = scan.toFirestoreWeek(includeImage = true)
        }
        var record = latest.toFirestoreWeek(includeImage = true).toMutableMap()
        record["weeks"] = weeks
        val name = plantName.trim()
        if (name.isNotEmpty()) record["plantName"] = name
        val recommendation = plantRecommendationRecord(
            latest,
            latest.response.takeIf { it.hasAiRecommendations }?.asSavedRecommendation(),
            latest.savedDiseaseSummary(),
            isoTimestamp(System.currentTimeMillis())
        )
        if (recommendation != null) record["recommendation"] = recommendation.withoutHealthyDiseaseName()
        record = record.shrunkToFit()
        Tasks.await(tileRef.set(record.withoutNulls(), SetOptions.merge()))

        if (name.isNotEmpty()) {
            val snapshot = Tasks.await(userRef.get())
            val previous = snapshot.get("plantNames").asMap().orEmpty()[tileNumber.toString()]
            val entry = plantNameEntry(name, latest, previous)
            writePlantNameEntry(userRef, tileNumber, entry)
        }
    }

    /**
     * Writes the care guide onto users/{uid}.plantNames.{tile}.recommendation
     * after a phone scan already saved its scores. Does not add another scan.
     */
    suspend fun savePlantRecommendation(
        uid: String,
        tileNumber: Int,
        plantName: String,
        scan: TileScanResult
    ) = withContext(Dispatchers.IO) {
        if (!scan.response.hasAiRecommendations) return@withContext
        val userRef = FirebaseFirestore.getInstance().collection("users").document(uid)
        val snapshot = Tasks.await(userRef.get())
        val previous = snapshot.get("plantNames").asMap().orEmpty()[tileNumber.toString()]
        val name = plantName.trim().ifBlank { previous.savedPlantName() }
        if (name.isEmpty()) return@withContext
        val entry = plantNameEntry(name, scan, previous)
        if (entry["recommendation"] == null) return@withContext
        writePlantNameEntry(userRef, tileNumber, entry)
    }
}

private fun RawCloudBundle.toCloudFarm(): CloudFarm {
    val source = user.orEmpty()
    val layout = source["layout"].asMap()
    val rows = layout?.get("rows").asInt() ?: 0
    val columns = layout?.get("columns").asInt() ?: 0
    val savedPlants = source["plantNames"].asMap().orEmpty().mapNotNull { (key, value) ->
        val tile = key.toTileNumber() ?: return@mapNotNull null
        value.asSavedPlant()?.let { tile to it }
    }.toMap()
    val removed = deletions.mapNotNull { entry -> itToDeletion(entry) }
        .sortedByDescending { it.deletedAtMillis }
    val names = mutableMapOf<Int, String>()
    val recommendations = mutableMapOf<Int, SavedRecommendation>()
    savedPlants.forEach { (tile, plant) ->
        val plantName = plant.name.trim()
        if (plantName.isNotEmpty()) names[tile] = plantName
        plant.recommendation?.let { recommendations[tile] = it }
    }
    tiles.forEach { (id, data) ->
        val tile = id.toTileNumber() ?: return@forEach
        val tileName = data.savedPlantName()
        if (tileName.isNotBlank() && names[tile].isNullOrBlank()) names[tile] = tileName
        if (recommendations[tile] == null) {
            data["recommendation"].asPlantRecommendation()?.let { recommendations[tile] = it }
        }
    }

    val history = tiles.mapNotNull { (id, data) ->
        val tileNumber = id.toIntOrNull() ?: return@mapNotNull null
        val scans = data.toTileScans(tileNumber)
            .map { scan -> scan.applySavedDisease(savedPlants[tileNumber]) }
        if (scans.isEmpty()) null else tileNumber to scans
    }.toMap()

    val calendar = source["scanCalendar"].asMap()
    return CloudFarm(
        rows = rows.coerceAtLeast(0),
        columns = columns.coerceAtLeast(0),
        plantNames = names,
        recommendations = recommendations,
        history = history,
        deletions = removed,
        profile = FarmerProfile(
            firstName = source["firstName"].asText(),
            middleInitial = source["middleInitial"].asText().take(1),
            lastName = source["lastName"].asText(),
            farmName = source["farmName"].asText(),
            email = source["email"].asText(),
            photo = decodeImageDataUrl(source["photo"].asText())
        ),
        selectedFarmWeek = source["selectedFarmWeek"].asInt()
            ?.takeIf { it in FIRST_FARM_WEEK..LAST_FARM_WEEK }
            ?: FIRST_FARM_WEEK,
        scanCalendar = ScanCalendar(
            enabled = calendar?.get("enabled") == true,
            anchor = calendar?.get("anchor").asText(),
            nextDue = calendar?.get("nextDue").asText(),
            selected = calendar?.get("selected").asText()
        ),
        plantNamesFromCloud = source.containsKey("plantNames") || names.isNotEmpty()
    )
}

private fun itToDeletion(entry: Map<String, Any?>): FarmDeletion? {
    val tileNumber = (entry["tileNum"] ?: entry["tileNumber"]).asInt() ?: return null
    val deletedAt = entry["deletedAt"]
    val millis = parseTimestamp(deletedAt)
    val name = entry["plantName"].asText().ifBlank { "Tile $tileNumber" }
    return FarmDeletion(
        id = entry["id"].asText().ifBlank { "$tileNumber-$millis" },
        tileNumber = tileNumber,
        plantName = name,
        deletedAtMillis = millis,
        deletedAtLabel = if (millis > 0L) formatMillis(millis) else "Date unknown"
    )
}

private fun Map<String, Any?>.toTileScans(tileNumber: Int): List<TileScanResult> {
    val parentScores = scoreMap()
    val parentTopBar = this["topBarKey"].asText().ifBlank { null }
    val parentTopPct = this["topPct"].asDouble() ?: 0.0
    val weeks = linkedMapOf<Int, WeekSnap>()
    this["weeks"].asMap()?.forEach { (key, value) ->
        val scan = value.asMap() ?: return@forEach
        val rawWeek = (scan["weekNumber"] ?: key).asInt()
        val week = canonicalWeek(rawWeek) ?: return@forEach
        val snap = scan.toWeekSnap(week)?.withParentScores(parentScores, parentTopBar, parentTopPct)
            ?: return@forEach
        if (weeks[week] == null || rawWeek != 3) weeks[week] = snap
    }

    val topWeek = canonicalWeek(this["weekNumber"].asInt())
        ?: FIRST_FARM_WEEK.takeIf { weeks.isEmpty() && hasSavedInfo() }
    if (topWeek != null && weeks[topWeek] == null && hasSavedInfo()) {
        toWeekSnap(topWeek)?.let { weeks[topWeek] = it }
    }
    val parentImage = this["imageDataUrl"].asText()
    if (topWeek != null && parentImage.isNotBlank()) {
        val existing = weeks[topWeek]
        if (existing != null && existing.imageDataUrl.isNullOrBlank()) {
            weeks[topWeek] = existing.copy(imageDataUrl = parentImage)
        }
    }

    return weeks.values
        .sortedBy { it.weekNumber }
        .mapNotNull { it.toScan(tileNumber) }
}

private fun Map<String, Any?>.toWeekSnap(weekNumber: Int): WeekSnap? {
    if (!hasSavedInfo()) return null
    val diseaseLabel = listOf("topBarKey", "diseaseLabel", "diseaseName", "topDisease")
        .firstNotNullOfOrNull { field -> this[field].asText().ifBlank { null } }
    val diseasePct = listOf("topPct", "diseasePct", "percent")
        .firstNotNullOfOrNull { this[it].asScorePercent() } ?: 0.0
    return WeekSnap(
        weekNumber = weekNumber,
        savedAt = this["savedAt"].asTimestampText(),
        scanDayIso = this["scanDayIso"].asText(),
        scores = scoreMap(),
        topBarKey = diseaseLabel,
        topPct = diseasePct,
        imageDataUrl = this["imageDataUrl"].asText().ifBlank { null }
    )
}

private fun Map<String, Any?>.hasSavedInfo(): Boolean {
    if (parseTimestamp(this["savedAt"]) > 0L || this["imageDataUrl"].asText().isNotBlank()) return true
    return scoreMap().values.any { it > 0.0 }
}

private fun WeekSnap.withParentScores(
    parentScores: Map<String, Double>,
    parentTopBar: String?,
    parentTopPct: Double
): WeekSnap {
    if (scores.values.any { it > 0.0 } || parentScores.values.none { it > 0.0 }) return this
    return copy(
        scores = parentScores,
        topBarKey = topBarKey ?: parentTopBar,
        topPct = if (topPct > 0.0) topPct else parentTopPct
    )
}

private fun Map<String, Any?>.scoreMap(): Map<String, Double> {
    val scores = this["scores"].asMap() ?: return emptyMap()
    return scores.mapNotNull { (key, value) ->
        val percent = value.asScorePercent() ?: return@mapNotNull null
        val barKey = barKeyForDiseaseName(key) ?: key
        barKey to percent
    }.toMap()
}

/** Website scores are 0–100. A 0–1 fraction is scaled up. */
private fun Any?.asScorePercent(): Double? {
    val raw = asDouble() ?: return null
    val percent = if (raw in 0.0..1.0 && raw != 0.0 && raw != 1.0) raw * 100.0 else raw
    return percent.coerceIn(0.0, 100.0)
}

private fun Double.toPercent(): Int = (this + 0.5).toInt().coerceIn(0, 100)

private data class SavedPlant(
    val name: String,
    val diseases: List<StoredDisease> = emptyList(),
    val healthyPct: Double? = null,
    val recommendation: SavedRecommendation? = null
) {
    val diseaseLabel: String
        get() = diseases.firstOrNull()?.name.orEmpty()
    val diseasePct: Int
        get() = diseases.firstOrNull()?.pct?.toPercent() ?: 0
}

/** plantNames values are a name, or { name, diseaseLabel, diseases, recommendation }. */
private fun Any?.asSavedPlant(): SavedPlant? {
    val record = asMap()
    if (record != null) {
        val name = record.savedPlantName()
        val diseases = record.savedDiseases()
        val healthyPct = record["healthy"].asMap()?.get("pct").asScorePercent()
        if (name.isBlank() && diseases.isEmpty() && healthyPct == null) return null
        return SavedPlant(
            name,
            diseases,
            healthyPct,
            record["recommendation"].asPlantRecommendation()
        )
    }
    val name = asText()
    if (name.isBlank() || name.startsWith("{") || name.startsWith("[")) return null
    return SavedPlant(name)
}

private fun Map<String, Any?>.savedDiseases(): List<StoredDisease> {
    val detailed = this["diseases"].asStoredDiseases()
    if (detailed.isNotEmpty()) return detailed
    val names = this["diseaseLabel"].asNameList()
        .filter { name ->
            val key = barKeyForDiseaseName(name)
            key != null && key != BAR_HEALTHY
        }
    if (names.isEmpty()) return emptyList()
    val pct = this["diseasePct"].asScorePercent()?.toPercent() ?: 0
    if (names.size == 1 && pct > 0) return listOf(StoredDisease(names[0], pct.toDouble()))
    return names.map { StoredDisease(it, 0.0) }
}

private fun Any?.asStoredDiseases(): List<StoredDisease> {
    val list = this as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        val record = (item as? Map<*, *>) ?: return@mapNotNull null
        val name = record["name"].asText()
        val key = barKeyForDiseaseName(name)
        val pct = record["pct"].asScorePercent() ?: return@mapNotNull null
        if (name.isBlank() || key == null || key == BAR_HEALTHY || pct <= 0.0) return@mapNotNull null
        StoredDisease(name, pct)
    }.sortedByDescending { it.pct }
}

private fun Any?.asNameList(): List<String> {
    val list = this as? List<*>
    if (list != null) {
        return list.mapNotNull { item ->
            when (item) {
                is String -> item.trim().ifBlank { null }
                is Map<*, *> -> item["name"].asText().ifBlank { null }
                else -> null
            }
        }
    }
    val text = asText()
    if (text.isBlank() || barKeyForDiseaseName(text) == BAR_HEALTHY) return emptyList()
    return listOf(text)
}

private fun Any?.savedPlantName(): String {
    val record = asMap()
    if (record != null) {
        return listOf("name", "plantName", "plant_name")
            .firstNotNullOfOrNull { key -> record[key].asText().ifBlank { null } }
            .orEmpty()
    }
    val text = asText()
    if (text.isBlank() || text.startsWith("{") || text.startsWith("[")) return ""
    return text
}

private fun String.toTileNumber(): Int? = trim().toIntOrNull()

/** Use every saved disease when the tile scores did not come through. */
private fun TileScanResult.applySavedDisease(plant: SavedPlant?): TileScanResult {
    if (plant == null || (plant.diseases.isEmpty() && plant.healthyPct == null)) return this
    if (scores.any { it.percent > 0 }) return this
    val byKey = plant.diseases.mapNotNull { disease ->
        val key = barKeyForDiseaseName(disease.name) ?: return@mapNotNull null
        key to disease.pct.toPercent()
    }.toMap()
    if (byKey.isEmpty() && plant.healthyPct == null) return this
    val updated = MONITORING_BAR_KEYS.map { bar ->
        val name = diseaseNameForBar(bar)
        val percent = if (bar == BAR_HEALTHY) {
            plant.healthyPct?.toPercent() ?: 0
        } else {
            byKey[bar] ?: 0
        }
        DiseaseScore(
            name = name,
            abbreviation = diseaseAbbreviation(name),
            percent = percent
        )
    }
    val top = updated.maxByOrNull { it.percent }
    return copy(
        diseaseName = top?.name ?: diseaseName,
        abbreviation = top?.abbreviation ?: abbreviation,
        percent = top?.percent ?: 0,
        isHealthy = top == null || top.percent <= 0 || isHealthyDisease(top.name),
        scores = updated,
        response = updated.asSavedValidationResponse()
    )
}

private fun WeekSnap.toScan(tileNumber: Int): TileScanResult? {
    val savedAtMillis = parseTimestamp(savedAt).takeIf { it > 0L } ?: (tileNumber * 100L + weekNumber)
    val topKey = topBarKey?.let(::barKeyForDiseaseName) ?: barKeyForDiseaseName(
        scores.maxByOrNull { it.value }?.key.orEmpty()
    )
    val topPercent = if (topPct > 0.0) {
        topPct.toPercent()
    } else {
        topKey?.let { key -> scores[key]?.toPercent() } ?: 0
    }
    val scoreList = MONITORING_BAR_KEYS.map { key ->
        val name = diseaseNameForBar(key)
        val stored = scores[key]?.toPercent() ?: 0
        val percent = if (key == topKey && stored == 0 && topPercent > 0) topPercent else stored
        DiseaseScore(
            name = name,
            abbreviation = diseaseAbbreviation(name),
            percent = percent
        )
    }
    val diseaseName = monitoringResultLabel(topKey) ?: diseaseNameForBar(topKey.orEmpty()).ifBlank { "Saved" }
    val healthy = topKey == null || topKey == BAR_HEALTHY
    return TileScanResult(
        scanId = savedAtMillis + weekNumber,
        scannedAtMillis = savedAtMillis,
        scanDayIso = scanDayIso,
        tileNumber = tileNumber,
        weekNumber = weekNumber,
        diseaseName = if (topKey == null && topPercent <= 0) "Saved" else diseaseName,
        abbreviation = if (topKey == null) "Saved" else diseaseAbbreviation(diseaseNameForBar(topKey)),
        percent = topPercent,
        isHealthy = healthy,
        scores = scoreList,
        response = ValidationResponse(
            result = "TomatoLeaf",
            confidence = 1.0,
            minConfidence = 0.0,
            detectedClass = "SavedScan",
            stage = "saved",
            topDisease = diseaseNameForBar(topKey.orEmpty()).ifBlank { null },
            topDiseaseConfidence = topPercent / 100.0,
            predictions = scoreList.map { score ->
                DiseasePrediction(
                    className = score.name,
                    confidence = score.percent / 100.0
                )
            },
            diseaseClasses = scoreList.map { it.name }
        ),
        image = decodeImageDataUrl(imageDataUrl)
    )
}

private fun plantNameEntry(
    name: String,
    scan: TileScanResult,
    previous: Any?
): Map<String, Any> {
    val savedAt = isoTimestamp(System.currentTimeMillis())
    val previousRecord = previous.asMap()
    val entry = linkedMapOf<String, Any>()
    previousRecord?.forEach { (key, value) ->
        if (value != null && key != "recommendation") entry[key] = value
    }
    entry["name"] = name
    entry["updatedAt"] = savedAt
    entry.remove("diseasePct")
    val healthyPct = scan.scores
        .filter { barKeyForDiseaseName(it.name) == BAR_HEALTHY }
        .maxOfOrNull { it.percent }
    if (healthyPct != null) {
        entry["healthy"] = mapOf(
            "pct" to (kotlin.math.round(healthyPct.coerceIn(0, 100) * 10.0) / 10.0)
        )
    }
    val detected = scan.detectedDiseaseRecords()
    if (detected.isNotEmpty()) {
        entry["diseaseLabel"] = detected.map { it.name }
        entry["diseases"] = detected.map { disease ->
            mapOf("name" to disease.name, "pct" to disease.pct)
        }
    } else {
        entry.remove("diseaseLabel")
        entry.remove("diseases")
    }
    val topDisease = detected.firstOrNull()
    val disease = if (topDisease != null) {
        topDisease.pct to topDisease.name
    } else {
        scan.savedDiseaseSummary()
    }
    val guide = scan.response.takeIf { it.hasAiRecommendations }?.asSavedRecommendation()
    val recommendation = plantRecommendationRecord(scan, guide, disease, savedAt)
        ?: previousRecommendationIfSame(previousRecord, detected)
    if (recommendation != null) entry["recommendation"] = recommendation.withoutHealthyDiseaseName()
    return entry
}

private suspend fun writePlantNameEntry(
    userRef: com.google.firebase.firestore.DocumentReference,
    tileNumber: Int,
    entry: Map<String, Any>
) {
    val path = "plantNames.$tileNumber"
    try {
        // Dotted update keeps every other plant name on the user document.
        Tasks.await(userRef.update(path, entry))
    } catch (error: Exception) {
        var cause: Throwable? = error
        var missing = false
        while (cause != null) {
            val code = (cause as? FirebaseFirestoreException)?.code
            if (code == FirebaseFirestoreException.Code.NOT_FOUND) missing = true
            cause = cause.cause
        }
        if (!missing) throw error
        // Document is new — no sibling plant names to keep yet.
        Tasks.await(
            userRef.set(
                mapOf("plantNames" to mapOf(tileNumber.toString() to entry)),
                SetOptions.merge()
            )
        )
    }
}

private data class StoredDisease(val name: String, val pct: Double)

/** Diseases above 0%, highest first. Healthy is not included. */
private fun TileScanResult.detectedDiseaseRecords(): List<StoredDisease> {
    val found = listOf(BAR_TLCV, BAR_EARLY_BLIGHT, BAR_SPOTTED_WILT).mapNotNull { key ->
        val pct = scores
            .filter { barKeyForDiseaseName(it.name) == key }
            .maxOfOrNull { it.percent }
            ?.coerceIn(0, 100)
            ?.toDouble()
            ?: 0.0
        if (pct <= 0.0) return@mapNotNull null
        val name = when (key) {
            BAR_TLCV -> "TLCV"
            BAR_EARLY_BLIGHT -> "Early blight"
            BAR_SPOTTED_WILT -> "Spotted wilt"
            else -> return@mapNotNull null
        }
        StoredDisease(name, kotlin.math.round(pct * 10.0) / 10.0)
    }.sortedByDescending { it.pct }
    if (found.isNotEmpty()) return found
    val topKey = barKeyForDiseaseName(diseaseName)
    if (topKey == null || topKey == BAR_HEALTHY || percent <= 0) return emptyList()
    val name = when (topKey) {
        BAR_TLCV -> "TLCV"
        BAR_EARLY_BLIGHT -> "Early blight"
        BAR_SPOTTED_WILT -> "Spotted wilt"
        else -> return emptyList()
    }
    return listOf(StoredDisease(name, percent.coerceIn(0, 100).toDouble()))
}

/** Same plantNames fields the website writes: name, diseaseLabel, diseases, recommendation. */
private fun TileScanResult.savedDiseaseSummary(): Pair<Double, String> {
    var bestKey = ""
    var bestPct = 0.0
    listOf(BAR_HEALTHY, BAR_TLCV, BAR_EARLY_BLIGHT, BAR_SPOTTED_WILT).forEach { key ->
        val pct = scores
            .filter { barKeyForDiseaseName(it.name) == key }
            .maxOfOrNull { it.percent }
            ?.coerceIn(0, 100)
            ?.toDouble()
            ?: 0.0
        if (pct > bestPct) {
            bestPct = pct
            bestKey = key
        }
    }
    val topKey = barKeyForDiseaseName(diseaseName)
    if (bestKey.isEmpty() && topKey != null && percent > 0) {
        bestKey = topKey
        bestPct = percent.coerceIn(0, 100).toDouble()
    }
    val label = when (bestKey) {
        BAR_HEALTHY -> "Healthy"
        BAR_TLCV -> "Yellow leaf curl virus"
        BAR_EARLY_BLIGHT -> "Early blight"
        BAR_SPOTTED_WILT -> "Spotted wilt"
        else -> ""
    }
    val rounded = kotlin.math.round(bestPct * 10.0) / 10.0
    return rounded to label
}

private fun plantRecommendationRecord(
    scan: TileScanResult,
    guide: SavedRecommendation?,
    disease: Pair<Double, String>,
    savedAt: String
): Map<String, Any>? {
    if (guide == null || disease.second.isBlank()) return null
    val topKey = barKeyForDiseaseName(guide.diseaseName)
        ?: barKeyForDiseaseName(disease.second)
        ?: return null
    val scoreValues = linkedMapOf<String, Any>(
        BAR_HEALTHY to 0,
        BAR_TLCV to 0,
        BAR_EARLY_BLIGHT to 0,
        BAR_SPOTTED_WILT to 0
    )
    scan.scores.forEach { score ->
        val key = barKeyForDiseaseName(score.name) ?: return@forEach
        scoreValues[key] = score.percent.coerceIn(0, 100)
    }
    val source = if (scan.response.recommendationSource == "groq") "groq" else "rules"
    return guide.toFirestore(
        topBarKey = topKey,
        topPct = disease.first,
        weekNumber = canonicalWeek(scan.weekNumber),
        source = source,
        savedAt = savedAt,
        scores = scoreValues
    )
}

private fun previousRecommendationIfSame(
    previous: Map<String, Any?>?,
    detected: List<StoredDisease>
): Any? {
    val record = previous ?: return null
    val recommendation = record["recommendation"] ?: return null
    if (detected.isEmpty()) return null
    val guideName = record["recommendation"].asMap()?.get("diseaseName").asText()
    val guideKey = barKeyForDiseaseName(guideName) ?: return null
    return if (detected.any { barKeyForDiseaseName(it.name) == guideKey }) recommendation else null
}

private fun TileScanResult.toFirestoreWeek(includeImage: Boolean): Map<String, Any?> {
    val scoreValues = linkedMapOf(
        BAR_HEALTHY to 0,
        BAR_TLCV to 0,
        BAR_EARLY_BLIGHT to 0,
        BAR_SPOTTED_WILT to 0
    )
    scores.forEach { score ->
        val key = barKeyForDiseaseName(score.name) ?: return@forEach
        scoreValues[key] = score.percent.coerceIn(0, 100)
    }
    val topKey = barKeyForDiseaseName(diseaseName)
        ?: scoreValues.maxByOrNull { it.value }?.key?.takeIf { scoreValues.getValue(it) > 0 }
    return mapOf(
        "savedAt" to isoTimestamp(scannedAtMillis),
        "scanDayIso" to scanDayIso,
        "weekNumber" to (canonicalWeek(weekNumber) ?: FIRST_FARM_WEEK),
        "scores" to scoreValues,
        "topBarKey" to topKey,
        "topPct" to percent.coerceIn(0, 100),
        "imageDataUrl" to if (includeImage) image.toJpegDataUrl() else null
    )
}

private fun Map<String, Any?>.withoutNulls(): Map<String, Any> =
    mapNotNull { (key, value) ->
        when (value) {
            null -> null
            is Map<*, *> -> {
                val nested = value.entries.mapNotNull { (nestedKey, nestedValue) ->
                    val name = nestedKey as? String ?: return@mapNotNull null
                    when (nestedValue) {
                        null -> null
                        is Map<*, *> -> name to nestedValue.entries.mapNotNull { (innerKey, innerValue) ->
                            val innerName = innerKey as? String ?: return@mapNotNull null
                            if (innerValue == null) null else innerName to innerValue
                        }.toMap()
                        else -> name to nestedValue
                    }
                }.toMap()
                key to nested
            }
            else -> key to value
        }
    }.toMap()

private fun MutableMap<String, Any?>.shrunkToFit(): MutableMap<String, Any?> {
    fun sizeOf(): Int = values.sumOf { value ->
        when (value) {
            is String -> value.length
            is Map<*, *> -> value.values.sumOf { (it as? String)?.length ?: 0 }
            else -> 0
        }
    }
    if (sizeOf() <= TILE_DOC_CHAR_BUDGET) return this
    val weeks = (this["weeks"] as? Map<*, *>)?.mapNotNull { (key, value) ->
        val name = key as? String ?: return@mapNotNull null
        val week = (value as? Map<*, *>)?.entries?.mapNotNull { (field, fieldValue) ->
            val fieldName = field as? String ?: return@mapNotNull null
            fieldName to fieldValue
        }?.toMap()?.toMutableMap() ?: return@mapNotNull null
        name to week
    }?.toMap()?.toMutableMap()
    weeks?.values?.forEach { week -> week["imageDataUrl"] = null }
    if (weeks != null) this["weeks"] = weeks
    if (sizeOf() > TILE_DOC_CHAR_BUDGET) this["imageDataUrl"] = null
    return this
}

private fun Bitmap?.toJpegDataUrl(): String? {
    val source = this ?: return null
    val maxEdge = 720
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
    val bitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else {
        source
    }
    val bytes = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
    if (bitmap !== source) bitmap.recycle()
    val encoded = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
    return "data:image/jpeg;base64,$encoded"
}

/** Square JPEG the website profile page accepts (data URL under 180k characters). */
private fun Bitmap.toProfilePhotoDataUrl(): String {
    val size = 320
    val scale = maxOf(size / width.toFloat().coerceAtLeast(1f), size / height.toFloat().coerceAtLeast(1f))
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(square).drawBitmap(
        scaled,
        (size - scaledWidth) / 2f,
        (size - scaledHeight) / 2f,
        null
    )
    if (scaled !== this) scaled.recycle()
    var quality = 82
    var encoded = square.toJpegDataUrl(quality).orEmpty()
    while (encoded.length > 170_000 && quality > 50) {
        quality -= 8
        encoded = square.toJpegDataUrl(quality).orEmpty()
    }
    square.recycle()
    return if (encoded.length > 180_000) "" else encoded
}

private fun Bitmap.toJpegDataUrl(quality: Int): String {
    val bytes = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, bytes)
    val encoded = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
    return "data:image/jpeg;base64,$encoded"
}

private fun isoTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))

private const val TILE_DOC_CHAR_BUDGET = 800_000

private fun diseaseNameForBar(key: String): String = when (key) {
    BAR_HEALTHY -> "Healthy"
    BAR_TLCV -> "Yellow leaf curl virus"
    BAR_EARLY_BLIGHT -> "Early blight"
    BAR_SPOTTED_WILT -> "Spotted wilt"
    else -> ""
}

private fun canonicalWeek(value: Int?): Int? {
    val week = when (value) {
        3 -> FIRST_FARM_WEEK
        else -> value
    }
    return week?.takeIf { it in FIRST_FARM_WEEK..LAST_FARM_WEEK }
}

private fun firestoreError(error: Exception): String {
    val code = error.message.orEmpty().lowercase()
    return when {
        "permission" in code ->
            "Firestore denied this account. Sign in with the same user as the website."
        "unavailable" in code || "network" in code ->
            "Could not reach Firestore. Check the internet connection."
        else -> error.message ?: "Could not read Firestore."
    }
}

private fun Any?.asMap(): Map<String, Any?>? {
    val raw = this as? Map<*, *> ?: return null
    return raw.entries.mapNotNull { (key, value) ->
        val name = key as? String ?: return@mapNotNull null
        name to value
    }.toMap()
}

private fun Any.withoutHealthyDiseaseName(): Any {
    val record = asMap()?.toMutableMap() ?: return this
    if (barKeyForDiseaseName(record["diseaseName"].asText()) == BAR_HEALTHY) {
        record.remove("diseaseName")
    }
    return record
}

private fun Any?.asPlantRecommendation(): SavedRecommendation? {
    val record = asMap() ?: return null
    val summary = record["summary"].asText().take(800)
    val steps = record["actionSteps"].asStringList()
    val guides = record["guides"].asSavedGuides()
    if (summary.isBlank() && steps.isEmpty() && guides.isEmpty()) return null
    val storedName = record["diseaseName"].asText().take(80)
    val diseaseName = storedName.ifBlank {
        if (barKeyForDiseaseName(record["topBarKey"].asText()) == BAR_HEALTHY) "Healthy" else ""
    }
    return SavedRecommendation(
        diseaseName = diseaseName,
        urgencyNote = record["urgencyNote"].asText().take(240),
        summary = summary,
        actionSteps = steps,
        preventionTips = record["preventionTips"].asStringList(),
        isCriticalRemoval = record["isCriticalRemoval"] == true,
        guides = guides
    )
}

private fun Any?.asSavedGuides(): List<SavedGuide> {
    val items = this as? List<*> ?: return emptyList()
    return items.mapNotNull { item ->
        val guide = item.asMap() ?: return@mapNotNull null
        val summary = guide["summary"].asText().take(800)
        val steps = guide["actionSteps"].asStringList()
        if (summary.isBlank() && steps.isEmpty()) return@mapNotNull null
        SavedGuide(
            diseaseName = guide["diseaseName"].asText().take(80),
            confidencePercent = guide["confidencePercent"].asInt()?.coerceIn(0, 100) ?: 0,
            urgencyNote = guide["urgencyNote"].asText().take(240),
            summary = summary,
            actionSteps = steps,
            preventionTips = guide["preventionTips"].asStringList(),
            isCriticalRemoval = guide["isCriticalRemoval"] == true
        )
    }
}

private fun Any?.asStringList(): List<String> {
    val items = this as? List<*>
    if (items != null) {
        return items.mapNotNull { item -> item.asText().ifBlank { null } }.take(12)
    }
    val map = asMap() ?: return emptyList()
    return map.entries
        .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
        .mapNotNull { it.value.asText().ifBlank { null } }
        .take(12)
}

private fun Any?.asText(): String = when (this) {
    null -> ""
    is String -> this.trim()
    else -> this.toString().trim()
}

private fun Any?.asInt(): Int? = when (this) {
    is Int -> this
    is Long -> this.toInt()
    is Double -> this.toInt()
    is Float -> this.toInt()
    is String -> this.toIntOrNull()
    else -> null
}

private fun Any?.asDouble(): Double? = when (this) {
    is Double -> this
    is Float -> this.toDouble()
    is Int -> this.toDouble()
    is Long -> this.toDouble()
    is String -> this.toDoubleOrNull()
    else -> null
}

fun decodeImageDataUrl(dataUrl: String?): Bitmap? {
    val value = dataUrl?.trim().orEmpty()
    if (value.isBlank()) return null
    val encoded = value.substringAfter("base64,", value).substringBefore(" ")
    return runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, 720)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
}

private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
    var sample = 1
    if (width <= 0 || height <= 0) return sample
    while (width / sample > maxSize || height / sample > maxSize) {
        sample *= 2
    }
    return sample
}

private fun Any?.asTimestampText(): String? {
    val millis = parseTimestamp(this)
    return if (millis > 0L) isoTimestamp(millis) else asText().ifBlank { null }
}

private fun parseTimestamp(value: Any?): Long {
    when (value) {
        null -> return 0L
        is com.google.firebase.Timestamp -> return value.toDate().time
        is Date -> return value.time
        is Number -> {
            val raw = value.toLong()
            return when {
                raw <= 0L -> 0L
                raw < 10_000_000_000L -> raw * 1000L
                else -> raw
            }
        }
    }
    val text = value.asText()
    if (text.isEmpty()) return 0L
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd"
    )
    patterns.forEach { pattern ->
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(text)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return 0L
}

private fun formatMillis(millis: Long): String {
    return SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(millis))
}
