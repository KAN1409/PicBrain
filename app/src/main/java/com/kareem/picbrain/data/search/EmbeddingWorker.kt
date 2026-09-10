package com.kareem.picbrain.data.search

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kareem.picbrain.PicBrainApp
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class EmbeddingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SemanticModelStore(applicationContext)
        if (!store.isInstalled()) return Result.success(workDataOf(KEY_INDEXED to 0))

        val app = applicationContext as PicBrainApp
        val dao = app.database.mediaItemDao()
        val embedder = EmbeddingGemmaEmbedder(applicationContext)

        return try {
            val screenshots = dao.getScreenshotsNeedingEmbedding(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
                BATCH_SIZE
            )

            if (screenshots.isEmpty()) {
                return Result.success(workDataOf(KEY_COMPLETE to true, KEY_INDEXED to 0))
            }

            var indexed = 0
            var failed = 0

            for (item in screenshots) {
                coroutineContext.ensureActive()
                val source = item.ocrNormalizedText.orEmpty().trim()
                if (source.isBlank()) continue

                runCatching {
                    // Keep each inference comfortably below the model context limit.
                    // This prevents very long OCR dumps from crashing the native runtime.
                    val boundedSource = source.take(MAX_DOCUMENT_CHARS)
                    val vector = embedder.embedDocument(boundedSource)
                    dao.deleteEmbeddingsForMedia(
                        item.mediaId,
                        EmbeddingGemmaEmbedder.MODEL_ID,
                        EmbeddingGemmaEmbedder.TARGET_DIMENSIONS
                    )
                    dao.upsertEmbeddings(
                        listOf(createEmbeddingEntity(item.mediaId, boundedSource, vector))
                    )
                    indexed++
                }.onFailure {
                    failed++
                }

                setProgress(
                    workDataOf(
                        KEY_INDEXED to indexed,
                        KEY_FAILED to failed,
                        KEY_BATCH_TOTAL to screenshots.size
                    )
                )
            }

            val hasMore = dao.getScreenshotsNeedingEmbedding(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
                1
            ).isNotEmpty()

            if (hasMore) {
                Result.retry()
            } else {
                Result.success(
                    workDataOf(
                        KEY_COMPLETE to true,
                        KEY_INDEXED to indexed,
                        KEY_FAILED to failed
                    )
                )
            }
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            embedder.close()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "picbrain-semantic-embeddings"
        const val KEY_INDEXED = "indexed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_BATCH_TOTAL = "batch_total"
        const val KEY_COMPLETE = "complete"

        private const val BATCH_SIZE = 12
        private const val MAX_DOCUMENT_CHARS = 1800
    }
}
