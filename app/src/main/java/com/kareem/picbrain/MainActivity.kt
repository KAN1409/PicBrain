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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
    val recentMedia by vm.recentMedia.collectAsStateWithLifecycle()
    val recentScreenshots by vm.recentScreenshots.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(hasImageReadPermission(vm)) }

    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = hasImageReadPermission(vm)
        if (permissionGranted) vm.startMediaMonitoring()
    }

    DisposableEffect(permissionGranted) {
        if (permissionGranted) vm.startMediaMonitoring()
        onDispose { }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PicBrain", style = MaterialTheme.typography.headlineLarge)
        Text("Your Visual Memory", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CountBlock("Pictures", mediaCount)
            CountBlock("Screenshots", screenshotCount)
            CountBlock("OCR ready", ocrDoneCount)
        }

        if (!permissionGranted) {
            Text("PicBrain builds a read-only local index. Your originals are never copied, moved, or modified.")
            Button(onClick = { launcher.launch(permissions) }) { Text("Allow photo access") }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.setSearchQuery(it)
                filter = if (it.isBlank()) LibraryFilter.SCREENSHOTS else LibraryFilter.SEARCH
            },
            label = { Text("Search screenshot text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == LibraryFilter.ALL, { filter = LibraryFilter.ALL; query = ""; vm.setSearchQuery("") }, { Text("All") })
            FilterChip(filter == LibraryFilter.SCREENSHOTS, { filter = LibraryFilter.SCREENSHOTS; query = ""; vm.setSearchQuery("") }, { Text("Screenshots") })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::rebuildIndex, enabled = !vm.isSyncing) { Text(if (vm.isSyncing) "Syncing…" else "Sync") }
            Button(onClick = vm::runOcrBatch, enabled = !vm.isOcrRunning) { Text(if (vm.isOcrRunning) "Reading…" else "Read next 25") }
        }
        Text(vm.status, style = MaterialTheme.typography.bodySmall)
        Text("OCR is local. This first engine is a retrieval foundation; Arabic accuracy is a separate quality gate.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(2.dp))
        Text(if (filter == LibraryFilter.SEARCH) "Search results" else "Index review", style = MaterialTheme.typography.titleMedium)
        val items = when (filter) {
            LibraryFilter.ALL -> recentMedia
            LibraryFilter.SCREENSHOTS -> recentScreenshots
            LibraryFilter.SEARCH -> searchResults
        }
        MediaGrid(items, Modifier.weight(1f))
    }
}

@Composable
private fun CountBlock(label: String, value: Int) {
    Column { Text(label, style = MaterialTheme.typography.labelLarge); Text(value.toString(), style = MaterialTheme.typography.headlineSmall) }
}

@Composable
private fun MediaGrid(items: List<MediaItemEntity>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) { Text("No matching indexed images."); return }
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

private fun hasImageReadPermission(vm: MainViewModel): Boolean = when {
    Build.VERSION.SDK_INT >= 34 -> vm.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) || vm.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> vm.hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
    else -> vm.hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
}
