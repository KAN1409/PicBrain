package com.kareem.picbrain.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val mediaId: Long,
    val contentUri: String,
    val displayName: String?,
    val relativePath: String?,
    val bucketName: String?,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val dateTakenMillis: Long?,
    val sizeBytes: Long,
    val isScreenshot: Boolean,
    val indexedAtMillis: Long,
    val ocrText: String? = null,
    val ocrNormalizedText: String? = null,
    val ocrState: String = "NOT_PROCESSED",
    val ocrEngine: String? = null,
    val ocrProcessedAtMillis: Long? = null,
    val ocrError: String? = null,
    val semanticState: String = "PENDING",
    val semanticAttemptCount: Int = 0,
    val semanticLastError: String? = null,
    val semanticLastAttemptAtMillis: Long? = null
)
