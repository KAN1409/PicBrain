package com.kareem.picbrain.data.db

import androidx.room.Entity

@Entity(
    tableName = "media_embeddings",
    primaryKeys = ["mediaId", "chunkIndex", "modelId", "dimensions"]
)
data class EmbeddingEntity(
    val mediaId: Long,
    val chunkIndex: Int,
    val modelId: String,
    val dimensions: Int,
    val textHash: String,
    val sourceText: String,
    val vector: ByteArray,
    val createdAtMillis: Long
)
