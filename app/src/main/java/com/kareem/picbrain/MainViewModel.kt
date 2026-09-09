package com.kareem.picbrain

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kareem.picbrain.data.media.MediaIndexer
import com.kareem.picbrain.data.media.MediaStoreObserver
import com.kareem.picbrain.data.ocr.OcrWorker
import com.kareem.picbrain.data.search.EmbeddingGemmaSemanticEngine
import com.kareem.picbrain.data.search.EmbeddingWorker
import com.kareem.picbrain.data.search.HybridSearchRepository
import com.kareem.picbrain.data.search.SemanticModelStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as PicBrainApp).database
    private val dao = db.mediaItemDao()
    private val indexer = MediaIndexer(app, dao)
    private val observer = MediaStoreObserver(app.contentResolver, ::onMediaStoreChanged)
    private val workManager = WorkManager.getInstance(app)
    private val semanticModelStore = SemanticModelStore(app)
    private val semanticEngine = EmbeddingGemmaSemanticEngine(app, dao)
    private val searchRepository = HybridSearchRepository(dao, semanticEngine)

    private var changeJob: Job? = null
    private var monitoring = false
    private val searchQuery = MutableStateFlow("")

    val mediaCount = dao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val screenshotCount = dao.observeScreenshotCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val ocrDoneCount = dao.observeOcrDoneCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val ocrPendingCount = dao.observeOcrPendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val ocrFailedCount = dao.observeOcrFailedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val recentMedia = dao.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentScreenshots = dao.observeRecentScreenshots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val searchResults = combine(searchQuery, dao.observeOcrSearchCorpus()) { rawQuery, corpus ->
        searchRepository.search(rawQuery, corpus)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var status by mutableStateOf("Ready")
        private set
    var isSyncing by mutableStateOf(false)
        private set
    var semanticModelInstalled by mutableStateOf(semanticModelStore.isInstalled())
        private set
    var isSemanticModelDownloading by mutableStateOf(false)
        private set
    var semanticDownloadProgress by mutableIntStateOf(0)
        private set

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    fun setSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun reportStatus(message: String) {
        status = message
    }

    fun startMediaMonitoring() {
        if (monitoring) {
            scheduleBackgroundOcr()
            scheduleSemanticIndexing()
            return
        }
        monitoring = true
        observer.start()
        reconcile("Checking library…")
    }

    fun stopMediaMonitoring() {
        monitoring = false
        observer.stop()
        changeJob?.cancel()
    }

    fun rebuildIndex() = reconcile("Rebuilding index…")

    fun scheduleBackgroundOcr() {
        if (!OcrWorker.hasImageReadPermission(getApplication())) return
        val request = OneTimeWorkRequestBuilder<OcrWorker>().build()
        workManager.enqueueUniqueWork(
            OcrWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun scheduleSemanticIndexing() {
        if (!semanticModelStore.isInstalled()) return
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>().build()
        workManager.enqueueUniqueWork(
            EmbeddingWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun downloadSemanticModel() {
        if (isSemanticModelDownloading) return
        viewModelScope.launch {
            isSemanticModelDownloading = true
            semanticDownloadProgress = 0
            status = "Downloading official EmbeddingGemma model…"
            runCatching {
                semanticModelStore.downloadOfficial { progress ->
                    semanticDownloadProgress = progress
                    status = "Downloading EmbeddingGemma… $progress%"
                }
            }.onSuccess { bytes ->
                semanticModelInstalled = true
                semanticDownloadProgress = 100
                status = "Semantic model installed (${bytes / (1024 * 1024)} MB). Building local embeddings…"
                scheduleSemanticIndexing()
            }.onFailure { error ->
                semanticModelInstalled = semanticModelStore.isInstalled()
                semanticDownloadProgress = 0
                status = "Semantic model download failed: ${error.message ?: "unknown error"}"
            }
            isSemanticModelDownloading = false
        }
    }

    fun importSemanticModel(uri: Uri) {
        viewModelScope.launch {
            status = "Validating semantic model…"
            runCatching { semanticModelStore.importFrom(uri) }
                .onSuccess { bytes ->
                    semanticModelInstalled = true
                    status = "Semantic model installed (${bytes / (1024 * 1024)} MB). Building local embeddings…"
                    scheduleSemanticIndexing()
                }
                .onFailure { error ->
                    semanticModelInstalled = semanticModelStore.isInstalled()
                    status = "Semantic model install failed: ${error.message ?: "unknown error"}"
                }
        }
    }

    fun resetOcr() {
        viewModelScope.launch {
            dao.resetScreenshotOcr()
            status = "Screenshot OCR reset"
            scheduleBackgroundOcr()
        }
    }

    private fun onMediaStoreChanged() {
        changeJob?.cancel()
        changeJob = viewModelScope.launch {
            delay(800)
            runCatching { indexer.incrementalSync() }
            delay(800)
            reconcile("Library changed…", silentSuccess = true)
        }
    }

    private fun reconcile(startMessage: String, silentSuccess: Boolean = false) {
        viewModelScope.launch {
            isSyncing = true
            status = startMessage
            runCatching { indexer.reconcile() }
                .onSuccess {
                    status = if (!silentSuccess) "Indexed ${it.indexed} images" else "Library is up to date"
                    scheduleBackgroundOcr()
                    scheduleSemanticIndexing()
                }
                .onFailure { status = "Index failed: ${it.message ?: "unknown error"}" }
            isSyncing = false
        }
    }

    override fun onCleared() {
        stopMediaMonitoring()
        semanticEngine.close()
        super.onCleared()
    }
}
