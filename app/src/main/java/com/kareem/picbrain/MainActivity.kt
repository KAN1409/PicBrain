package com.kareem.picbrain

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.kareem.picbrain.data.db.MediaItemEntity
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { PicBrainHome() } } }
    }
}

private enum class LibraryFilter { ALL, SCREENSHOTS, SEARCH }

@Composable
private fun PicBrainHome(vm: MainViewModel = viewModel()) {
    val mediaCount by vm.mediaCount.collectAsStateWithLifecycle()
    val screenshotCount by vm.screenshotCount.collectAsStateWithLifecycle()
    val ocrDoneCount by vm.ocrDoneCount.collectAsStateWithLifecycle()
    val ocrPendingCount by vm.ocrPendingCount.collectAsStateWithLifecycle()
    val ocrFailedCount by vm.ocrFailedCount.collectAsStateWithLifecycle()
    val recentMedia by vm.recentMedia.collectAsStateWithLifecycle()
    val recentScreenshots by vm.recentScreenshots.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val semanticDiagnostics by vm.semanticDiagnostics.collectAsStateWithLifecycle()
    val semanticScores by vm.semanticScores.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(hasImageReadPermission(vm)) }

    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = hasImageReadPermission(vm)
        if (permissionGranted) vm.startMediaMonitoring()
    }

    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSemanticModel(uri)
    }

    DisposableEffect(permissionGranted) {
        if (permissionGranted) vm.startMediaMonitoring()
        onDispose { }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PicBrain", style = MaterialTheme.typography.headlineLarge)
        Text("Your Visual Memory", style = MaterialTheme.typography.titleMedium)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CountBlock("Pictures", mediaCount)
            CountBlock("Screenshots", screenshotCount)
            CountBlock("OCR ready", ocrDoneCount)
        }

        if (!permissionGranted) {
            Text("PicBrain builds a read-only local index. Your originals are never copied, moved, or modified.")
            Button(onClick = { permissionLauncher.launch(permissions) }) { Text("Allow photo access") }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.setSearchQuery(it)
                filter = if (it.isBlank()) LibraryFilter.SCREENSHOTS else LibraryFilter.SEARCH
            },
            label = { Text("Search screenshots in Arabic or English") },
            supportingText = {
                Text(
                    if (vm.semanticModelInstalled)
                        "Hybrid search: exact + OCR fuzzy + filtered local semantic meaning."
                    else
                        "Exact + OCR fuzzy search active. Install EmbeddingGemma for semantic meaning."
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == LibraryFilter.ALL,
                onClick = {
                    filter = LibraryFilter.ALL
                    query = ""
                    vm.setSearchQuery("")
                },
                label = { Text("All") }
            )
            FilterChip(
                selected = filter == LibraryFilter.SCREENSHOTS,
                onClick = {
                    filter = LibraryFilter.SCREENSHOTS
                    query = ""
                    vm.setSearchQuery("")
                },
                label = { Text("Screenshots") }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::rebuildIndex, enabled = !vm.isSyncing) {
                Text(if (vm.isSyncing) "Syncing…" else "Sync")
            }
            Button(onClick = vm::scheduleBackgroundOcr) { Text("Continue OCR") }
            if (vm.semanticModelInstalled) {
                Button(onClick = vm::scheduleSemanticIndexing) { Text("Semantic index") }
            }
        }

        if (!vm.semanticModelInstalled) {
            Text(
                "PicBrain can download Google's official MediaPipe EmbeddingGemma model directly. " +
                    "The model stays on your device after installation.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = vm::downloadSemanticModel,
                enabled = !vm.isSemanticModelDownloading
            ) {
                Text(
                    if (vm.isSemanticModelDownloading)
                        "Downloading ${vm.semanticDownloadProgress}%"
                    else
                        "Download semantic model"
                )
            }
            if (vm.isSemanticModelDownloading) {
                LinearProgressIndicator(
                    progress = { vm.semanticDownloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedButton(onClick = {
                modelLauncher.launch(arrayOf("application/octet-stream", "application/x-tflite", "*/*"))
            }) {
                Text("Import model file manually")
            }
        }

        val totalOcr = ocrDoneCount + ocrPendingCount + ocrFailedCount
        if (totalOcr > 0) {
            LinearProgressIndicator(
                progress = { ocrDoneCount.toFloat() / totalOcr.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "OCR: $ocrDoneCount / $totalOcr ready • $ocrPendingCount pending • $ocrFailedCount failed",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(vm.status, style = MaterialTheme.typography.bodySmall)
        Text(
            if (vm.semanticModelInstalled)
                "Semantic retrieval runs fully on-device. Weak semantic matches are filtered before ranking."
            else
                "Lexical and fuzzy retrieval remain active until the semantic model is installed.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(2.dp))
        Text(
            when (filter) {
                LibraryFilter.SEARCH -> "Search results (${searchResults.size})"
                else -> "Index review"
            },
            style = MaterialTheme.typography.titleMedium
        )

        if (filter == LibraryFilter.SEARCH && query.isNotBlank() && vm.semanticModelInstalled) {
            val best = semanticDiagnostics.bestScore?.let(::formatScore) ?: "n/a"
            Text(
                "Semantic best: $best • threshold: ${formatScore(semanticDiagnostics.threshold)} • " +
                    "accepted: ${semanticDiagnostics.acceptedCount}/${semanticDiagnostics.evaluatedCount}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        when (filter) {
            LibraryFilter.ALL -> MediaGrid(recentMedia, modifier = Modifier.weight(1f))
            LibraryFilter.SCREENSHOTS -> MediaGrid(recentScreenshots, modifier = Modifier.weight(1f))
            LibraryFilter.SEARCH -> MediaGrid(
                items = searchResults,
                modifier = Modifier.weight(1f),
                semanticScores = semanticScores
            )
        }
    }
}

@Composable
private fun CountBlock(label: String, value: Int) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItemEntity>,
    modifier: Modifier = Modifier,
    semanticScores: Map<Long, Float> = emptyMap()
) {
    if (items.isEmpty()) {
        Text("No confident matching indexed images.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 142.dp),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items, key = { it.mediaId }) { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(
                    model = item.contentUri,
                    contentDescription = item.displayName ?: if (item.isScreenshot) "Screenshot" else "Picture",
                    modifier = Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                semanticScores[item.mediaId]?.let { score ->
                    Text(
                        text = "Semantic score: ${formatScore(score)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (item.isScreenshot) {
                    Text(
                        text = when (item.ocrState) {
                            "DONE" -> item.ocrText?.ifBlank { "No text detected" } ?: "No text detected"
                            "FAILED" -> "OCR failed"
                            else -> "Waiting for OCR"
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatScore(value: Float): String = String.format(Locale.US, "%.3f", value)

private fun hasImageReadPermission(vm: MainViewModel): Boolean = when {
    Build.VERSION.SDK_INT >= 34 ->
        vm.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
            vm.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> vm.hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
    else -> vm.hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
}
