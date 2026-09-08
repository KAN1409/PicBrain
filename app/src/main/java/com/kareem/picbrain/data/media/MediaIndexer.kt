package com.kareem.picbrain.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import com.kareem.picbrain.data.db.MediaItemDao
import com.kareem.picbrain.data.db.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaIndexer(
    private val context: Context,
    private val dao: MediaItemDao
) {
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.SIZE
    )

    suspend fun reconcile(): SyncResult = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItemEntity>()
        val existing = dao.getAll().associateBy { it.mediaId }
        val scanMarker = System.currentTimeMillis()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.toEntity(scanMarker, existing)
            }
        }

        if (items.isNotEmpty()) {
            dao.upsertAll(items)
            dao.deleteNotSeenInScan(scanMarker)
        } else {
            dao.deleteAll()
        }
        SyncResult(indexed = items.size, authoritative = true)
    }

    suspend fun incrementalSync(): SyncResult = withContext(Dispatchers.IO) {
        val after = dao.latestDateAddedSeconds() ?: return@withContext reconcile()
        val items = mutableListOf<MediaItemEntity>()
        val existing = dao.getAll().associateBy { it.mediaId }
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf(after.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val incrementalMarker = System.currentTimeMillis()
            while (cursor.moveToNext()) items += cursor.toEntity(incrementalMarker, existing)
        }
        if (items.isNotEmpty()) dao.upsertAll(items)
        SyncResult(indexed = items.size, authoritative = false)
    }

    private fun Cursor.toEntity(indexedAtMillis: Long, existing: Map<Long, MediaItemEntity>): MediaItemEntity {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        val name = getString(getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
        val path = getString(getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
        val bucket = getString(getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
        val takenCol = getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val modified = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
        val previous = existing[id]
        val preserveOcr = previous != null && previous.dateModifiedSeconds == modified
        return MediaItemEntity(
            mediaId = id,
            contentUri = ContentUris.withAppendedId(collection, id).toString(),
            displayName = name,
            relativePath = path,
            bucketName = bucket,
            mimeType = getString(getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)),
            width = getInt(getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
            height = getInt(getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
            dateAddedSeconds = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)),
            dateModifiedSeconds = modified,
            dateTakenMillis = if (isNull(takenCol)) null else getLong(takenCol),
            sizeBytes = getLong(getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
            isScreenshot = ScreenshotDetector.isScreenshot(name, path, bucket),
            indexedAtMillis = indexedAtMillis,
            ocrText = if (preserveOcr) previous?.ocrText else null,
            ocrNormalizedText = if (preserveOcr) previous?.ocrNormalizedText else null,
            ocrState = if (preserveOcr) previous?.ocrState ?: "NOT_PROCESSED" else "NOT_PROCESSED",
            ocrEngine = if (preserveOcr) previous?.ocrEngine else null,
            ocrProcessedAtMillis = if (preserveOcr) previous?.ocrProcessedAtMillis else null,
            ocrError = if (preserveOcr) previous?.ocrError else null
        )
    }
}

data class SyncResult(
    val indexed: Int,
    val authoritative: Boolean
)
