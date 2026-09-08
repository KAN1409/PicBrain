package com.kareem.picbrain

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kareem.picbrain.data.media.MediaIndexer
import com.kareem.picbrain.data.media.MediaStoreObserver
import com.kareem.picbrain.data.ocr.MlKitOcrEngine
import com.kareem.picbrain.data.ocr.ScreenshotOcrProcessor
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
    private val ocrProcessor = ScreenshotOcrProcessor(dao, MlKitOcrEngine(app))
    private val observer = MediaStoreObserver(app.contentResolver, ::onMediaStoreChanged)
    private var changeJob: Job? = null
    private var monitoring = false
    private val searchQuery = MutableStateFlow("")

    val mediaCount = dao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val screenshotCount = dao.observeScreenshotCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val ocrDoneCount = dao.observeOcrDoneCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val recentMedia = dao.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentScreenshots = dao.observeRecentScreenshots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val searchResults = searchQuery
        .flatMapLatest { q -> if (q.isBlank()) dao.observeRecentScreenshots() else dao.observeScreenshotSearch(q.trim().lowercase()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var status by mutableStateOf("Ready")
        private set
    var isSyncing by mutableStateOf(false)
        private set
    var isOcrRunning by mutableStateOf(false)
        private set

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    fun setSearchQuery(value: String) { searchQuery.value = value }

    fun startMediaMonitoring() {
        if (monitoring) return
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

    fun runOcrBatch() {
        if (isOcrRunning) return
        viewModelScope.launch {
            isOcrRunning = true
            status = "Reading screenshot text…"
            runCatching { ocrProcessor.processPending(25) }
                .onSuccess {
                    status = if (it.attempted == 0) "No screenshots waiting for OCR"
                    else "OCR: ${it.success} read, ${it.failed} failed"
                }
                .onFailure { status = "OCR failed: ${it.message ?: "unknown error"}" }
            isOcrRunning = false
        }
    }

    fun resetOcr() {
        viewModelScope.launch {
            dao.resetScreenshotOcr()
            status = "Screenshot OCR reset"
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
