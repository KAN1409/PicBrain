package com.kareem.picbrain.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {
    @Upsert
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_items")
    suspend fun getAll(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items ORDER BY COALESCE(dateTakenMillis, dateAddedSeconds * 1000) DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isScreenshot = 1 ORDER BY COALESCE(dateTakenMillis, dateAddedSeconds * 1000) DESC LIMIT :limit")
    fun observeRecentScreenshots(limit: Int = 200): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isScreenshot = 1 AND ocrState = 'DONE' ORDER BY COALESCE(dateTakenMillis, dateAddedSeconds * 1000) DESC")
    fun observeOcrSearchCorpus(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isScreenshot = 1 AND ocrState = 'NOT_PROCESSED' ORDER BY dateAddedSeconds DESC LIMIT :limit")
    suspend fun getScreenshotsNeedingOcr(limit: Int = 25): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isScreenshot = 1")
    fun observeScreenshotCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isScreenshot = 1 AND ocrState = 'DONE'")
    fun observeOcrDoneCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isScreenshot = 1 AND ocrState = 'NOT_PROCESSED'")
    fun observeOcrPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isScreenshot = 1 AND ocrState = 'FAILED'")
    fun observeOcrFailedCount(): Flow<Int>

    @Query("SELECT MAX(dateAddedSeconds) FROM media_items")
    suspend fun latestDateAddedSeconds(): Long?

    @Query("UPDATE media_items SET ocrText=:text, ocrNormalizedText=:normalized, ocrState='DONE', ocrEngine=:engine, ocrProcessedAtMillis=:processedAt, ocrError=NULL WHERE mediaId=:mediaId")
    suspend fun markOcrDone(mediaId: Long, text: String, normalized: String, engine: String, processedAt: Long)

    @Query("UPDATE media_items SET ocrState='FAILED', ocrEngine=:engine, ocrProcessedAtMillis=:processedAt, ocrError=:error WHERE mediaId=:mediaId")
    suspend fun markOcrFailed(mediaId: Long, engine: String, processedAt: Long, error: String)

    @Query("UPDATE media_items SET ocrState='NOT_PROCESSED', ocrText=NULL, ocrNormalizedText=NULL, ocrEngine=NULL, ocrProcessedAtMillis=NULL, ocrError=NULL WHERE isScreenshot=1")
    suspend fun resetScreenshotOcr()

    @Query("DELETE FROM media_items WHERE indexedAtMillis != :scanMarker")
    suspend fun deleteNotSeenInScan(scanMarker: Long)

    @Query("DELETE FROM media_items")
    suspend fun deleteAll()
}
