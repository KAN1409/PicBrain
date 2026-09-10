package com.kareem.picbrain

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kareem.picbrain.data.db.PicBrainDatabase

class PicBrainApp : Application() {
    val database by lazy {
        Room.databaseBuilder(this, PicBrainDatabase::class.java, "picbrain.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_embeddings (
                        mediaId INTEGER NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        modelId TEXT NOT NULL,
                        dimensions INTEGER NOT NULL,
                        textHash TEXT NOT NULL,
                        sourceText TEXT NOT NULL,
                        vector BLOB NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(mediaId, chunkIndex, modelId, dimensions)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS media_fts USING fts4(mediaId, ocrNormalizedText, tokenize=unicode61, notindexed=mediaId)"
                )
                db.execSQL(
                    """
                    INSERT INTO media_fts(mediaId, ocrNormalizedText)
                    SELECT mediaId, COALESCE(ocrNormalizedText, '')
                    FROM media_items
                    WHERE isScreenshot = 1 AND ocrState = 'DONE'
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS media_fts_ai
                    AFTER INSERT ON media_items
                    WHEN NEW.isScreenshot = 1 AND NEW.ocrState = 'DONE'
                    BEGIN
                        INSERT INTO media_fts(mediaId, ocrNormalizedText)
                        VALUES (NEW.mediaId, COALESCE(NEW.ocrNormalizedText, ''));
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS media_fts_au
                    AFTER UPDATE OF ocrNormalizedText, ocrState, isScreenshot ON media_items
                    BEGIN
                        DELETE FROM media_fts WHERE mediaId = OLD.mediaId;
                        INSERT INTO media_fts(mediaId, ocrNormalizedText)
                        SELECT NEW.mediaId, COALESCE(NEW.ocrNormalizedText, '')
                        WHERE NEW.isScreenshot = 1 AND NEW.ocrState = 'DONE';
                    END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS media_fts_ad
                    AFTER DELETE ON media_items
                    BEGIN
                        DELETE FROM media_fts WHERE mediaId = OLD.mediaId;
                        DELETE FROM media_embeddings WHERE mediaId = OLD.mediaId;
                    END
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN semanticState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE media_items ADD COLUMN semanticAttemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN semanticLastError TEXT")
                db.execSQL("ALTER TABLE media_items ADD COLUMN semanticLastAttemptAtMillis INTEGER")
                db.execSQL(
                    """
                    UPDATE media_items
                    SET semanticState = CASE
                        WHEN EXISTS (
                            SELECT 1 FROM media_embeddings e
                            WHERE e.mediaId = media_items.mediaId
                        ) THEN 'DONE'
                        ELSE 'PENDING'
                    END
                    """.trimIndent()
                )
            }
        }
    }
}
