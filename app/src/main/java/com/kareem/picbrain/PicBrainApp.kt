package com.kareem.picbrain

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kareem.picbrain.data.db.PicBrainDatabase

class PicBrainApp : Application() {
    val database by lazy {
        Room.databaseBuilder(this, PicBrainDatabase::class.java, "picbrain.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN ocrText TEXT")
                db.execSQL("ALTER TABLE media_items ADD COLUMN ocrNormalizedText TEXT")
                db.execSQL("ALTER TABLE media_items ADD COLUMN ocrState TEXT NOT NULL DEFAULT 'NOT_PROCESSED'")
                db.execSQL("ALTER TABLE media_items ADD COLUMN ocrEngine TEXT")
                db.execSQL("ALTER TABLE media_items ADD COLUMN ocrProcessedAtMillis INTEGER")
                db.execSQL("ALTER TABLE media_items ADD COLUMN ocrError TEXT")
            }
        }
    }
}
