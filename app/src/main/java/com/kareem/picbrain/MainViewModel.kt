package com.kareem.picbrain

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as PicBrainApp).database
    private val dao = db.mediaItemDao()
    private val indexer = MediaIndexer(app, dao)
    private val observer = MediaStoreObserver(app.contentResolver, ::onMediaStoreChanged)
    private val workManager = WorkManager.getInstance(app)
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
    val searchResults = searchQuery
        .flatMapLatest { q -> if (q.isBlank()) dao.observeRecentScreenshots() else dao.observeScreenshotSearch(q.trim().lowercase()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var status by mutableStateOf("Ready")
        private set
    var isSyncing by mutableStateOf(false)
        private set

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    fun setSearchQuery(value: String) { searchQuery.value = value }

    fun startMediaMonitoring() {
        if (monitoring) {
            scheduleBackgroundOcr()
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
                }
                .onFailure { status = "Index failed: ${it.message ?: "unknown error"}" }
            isSyncing = false
        }
    }

    override fun onCleared() {
        stopMediaMonitoring()
        super.onCleared()
    }
}
