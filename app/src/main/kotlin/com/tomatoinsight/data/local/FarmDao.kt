package com.tomatoinsight.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface FarmDao {

    @Query("SELECT * FROM farm_meta WHERE userId = :userId LIMIT 1")
    suspend fun getMeta(userId: String): FarmMetaEntity?

    @Query("SELECT * FROM tile_scans WHERE userId = :userId ORDER BY scannedAtMillis DESC")
    suspend fun getScans(userId: String): List<TileScanEntity>

    @Query("SELECT * FROM plant_names WHERE userId = :userId")
    suspend fun getPlantNames(userId: String): List<PlantNameEntity>

    @Query("SELECT * FROM farm_deletions WHERE userId = :userId ORDER BY deletedAtMillis DESC")
    suspend fun getDeletions(userId: String): List<FarmDeletionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: FarmMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScans(scans: List<TileScanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlantNames(names: List<PlantNameEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeletions(deletions: List<FarmDeletionEntity>)

    @Query("DELETE FROM tile_scans WHERE userId = :userId")
    suspend fun clearScans(userId: String)

    @Query("DELETE FROM plant_names WHERE userId = :userId")
    suspend fun clearPlantNames(userId: String)

    @Query("DELETE FROM farm_deletions WHERE userId = :userId")
    suspend fun clearDeletions(userId: String)

    @Query("DELETE FROM tile_scans WHERE userId = :userId AND scanId IN (:scanIds)")
    suspend fun deleteScans(userId: String, scanIds: List<Long>)

    @Query("DELETE FROM farm_meta WHERE userId = :userId")
    suspend fun clearMeta(userId: String)

    @Transaction
    suspend fun clearUserFarm(userId: String) {
        clearMeta(userId)
        clearScans(userId)
        clearPlantNames(userId)
        clearDeletions(userId)
    }

    @Transaction
    suspend fun replaceUserFarm(
        meta: FarmMetaEntity,
        scans: List<TileScanEntity>,
        plantNames: List<PlantNameEntity>,
        deletions: List<FarmDeletionEntity>
    ) {
        upsertMeta(meta)
        clearScans(meta.userId)
        clearPlantNames(meta.userId)
        clearDeletions(meta.userId)
        if (scans.isNotEmpty()) upsertScans(scans)
        if (plantNames.isNotEmpty()) upsertPlantNames(plantNames)
        if (deletions.isNotEmpty()) upsertDeletions(deletions)
    }
}
