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
            val existing = dao.getEmbeddings(
                EmbeddingGemmaEmbedder.MODEL_ID,
                EmbeddingGemmaEmbedder.TARGET_DIMENSIONS
            ).associateBy { it.mediaId to it.chunkIndex }

            var indexed = 0
            var skipped = 0
            var failed = 0

            val screenshots = dao.getAll()
                .asSequence()
                .filter { it.isScreenshot && it.ocrState == "DONE" }
                .sortedByDescending { it.dateTakenMillis ?: it.dateAddedSeconds * 1000L }
                .toList()

            for (item in screenshots) {
                coroutineContext.ensureActive()
                val source = item.ocrNormalizedText.orEmpty().trim()
                if (source.isBlank()) {
                    dao.deleteEmbeddingsForMedia(
                        item.mediaId,
                        EmbeddingGemmaEmbedder.MODEL_ID,
                        EmbeddingGemmaEmbedder.TARGET_DIMENSIONS
                    )
                    continue
                }

                val hash = textSha256(source)
                val current = existing[item.mediaId to 0]
                if (current?.textHash == hash) {
                    skipped++
                    continue
                }

                runCatching {
                    val vector = embedder.embedDocument(source)
                    dao.upsertEmbeddings(
                        listOf(createEmbeddingEntity(item.mediaId, source, vector))
                    )
                    indexed++
                }.onFailure {
                    failed++
                }

                setProgress(
                    workDataOf(
                        KEY_INDEXED to indexed,
                        KEY_SKIPPED to skipped,
                        KEY_FAILED to failed
                    )
                )
            }

            Result.success(
                workDataOf(
                    KEY_INDEXED to indexed,
                    KEY_SKIPPED to skipped,
                    KEY_FAILED to failed
                )
            )
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
    }
}
