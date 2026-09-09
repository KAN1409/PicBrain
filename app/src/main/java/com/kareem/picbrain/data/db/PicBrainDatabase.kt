package com.kareem.picbrain.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MediaItemEntity::class, EmbeddingEntity::class],
    version = 3,
    exportSchema = false
)
abstract class PicBrainDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
}
