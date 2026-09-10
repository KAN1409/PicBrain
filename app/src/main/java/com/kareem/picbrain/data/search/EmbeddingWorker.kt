package com.kareem.picbrain.data.search

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
        if (!store.isInstalled()) {
            return Result.failure(
                workDataOf(KEY_LAST_ERROR to "Semantic model is not installed")
            )
        }

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
                return Result.success(
                    workDataOf(
                        KEY_COMPLETE to true,
                        KEY_INDEXED to 0,
                        KEY_FAILED to 0,
                        KEY_BATCH_TOTAL to 0
                    )
                )
            }

            var indexed = 0
            var failed = 0
            var lastError = ""

            setProgress(
                workDataOf(
                    KEY_INDEXED to 0,
                    KEY_FAILED to 0,
                    KEY_BATCH_TOTAL to screenshots.size,
                    KEY_CURRENT_ITEM to 0
                )
            )

            screenshots.forEachIndexed { index, item ->
                coroutineContext.ensureActive()
                val source = item.ocrNormalizedText.orEmpty().trim()
                if (source.isBlank()) return@forEachIndexed

                runCatching {
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
                }.onFailure { error ->
                    failed++
                    lastError = buildString {
                        append(error::class.java.simpleName)
                        error.message?.takeIf(String::isNotBlank)?.let {
                            append(": ")
                            append(it.take(240))
                        }
                    }
                }

                setProgress(
                    workDataOf(
                        KEY_INDEXED to indexed,
                        KEY_FAILED to failed,
                        KEY_BATCH_TOTAL to screenshots.size,
                        KEY_CURRENT_ITEM to index + 1,
                        KEY_LAST_ERROR to lastError
                    )
                )
            }

            if (indexed == 0 && failed > 0) {
                return Result.failure(
                    workDataOf(
                        KEY_INDEXED to indexed,
                        KEY_FAILED to failed,
                        KEY_BATCH_TOTAL to screenshots.size,
                        KEY_LAST_ERROR to lastError.ifBlank { "Embedding batch failed" }
                    )
                )
            }

            val hasMore = dao.getScreenshotsNeedingEmbedding(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS,
                1
            ).isNotEmpty()

            if (hasMore) {
                val next = OneTimeWorkRequestBuilder<EmbeddingWorker>().build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    next
                )
            }

            Result.success(
                workDataOf(
                    KEY_COMPLETE to !hasMore,
                    KEY_INDEXED to indexed,
                    KEY_FAILED to failed,
                    KEY_BATCH_TOTAL to screenshots.size,
                    KEY_LAST_ERROR to lastError
                )
            )
        } catch (error: Throwable) {
            Result.failure(
                workDataOf(
                    KEY_LAST_ERROR to buildString {
                        append(error::class.java.simpleName)
                        error.message?.takeIf(String::isNotBlank)?.let {
                            append(": ")
                            append(it.take(240))
                        }
                    }
                )
            )
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
        const val KEY_CURRENT_ITEM = "current_item"
        const val KEY_COMPLETE = "complete"
        const val KEY_LAST_ERROR = "last_error"

        private const val BATCH_SIZE = 8
        private const val MAX_DOCUMENT_CHARS = 1800
    }
}
