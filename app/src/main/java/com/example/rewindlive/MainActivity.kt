package com.example.rewindlive

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.rewindlive.data.RecordingItem
import com.example.rewindlive.data.StreamRepository
import com.example.rewindlive.data.StreamSource
import com.example.rewindlive.monitor.LiveMonitorWorker
import com.example.rewindlive.recorder.StreamRecorderService
import com.example.rewindlive.ui.PlayerScreen
import com.example.rewindlive.util.GalleryExporter
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveMonitorWorker.schedule(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RewindLiveApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewindLiveApp() {
    val context = LocalContext.current
    val repo = remember { StreamRepository(context) }
    val scope = rememberCoroutineScope()

    val activeRecordings by StreamRecorderService.recordingIds.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var sources by remember { mutableStateOf(repo.getSources()) }
    var recordings by remember { mutableStateOf(repo.getRecordings()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var playingFile by remember { mutableStateOf<File?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(activeRecordings, selectedTab) {
        recordings = repo.getRecordings()
    }

    if (playingFile != null) {
        PlayerScreen(
            videoFile = playingFile!!,
            onBack = { playingFile = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rewind Live Recorder") },
                actions = {
                    if (selectedTab == 1) {
                        IconButton(onClick = { recordings = repo.getRecordings() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Stream")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.LiveTv, contentDescription = null) },
                    label = { Text("Tracked Streams") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        recordings = repo.getRecordings()
                        selectedTab = 1
                    },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text("Recordings") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                TrackedStreamsTab(
                    sources = sources,
                    activeIds = activeRecordings,
                    onToggleAuto = { id ->
                        repo.toggleAutoRecord(id)
                        sources = repo.getSources()
                    },
                    onRecordToggle = { src ->
                        if (activeRecordings.contains(src.id)) {
                            StreamRecorderService.stopRecording(context, src.id)
                        } else {
                            StreamRecorderService.startRecording(context, src.id, src.name, src.streamUrl)
                        }
                    },
                    onDelete = { id ->
                        repo.deleteSource(id)
                        sources = repo.getSources()
                    }
                )
            } else {
                RecordingsTab(
                    recordings = recordings,
                    onPlay = { playingFile = it.file },
                    onSaveToGallery = { item ->
                        scope.launch {
                            val ok = GalleryExporter.saveToGallery(context, item.file)
                            Toast.makeText(
                                context,
                                if (ok) "Saved to Movies/RewindLive" else "Export failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onDelete = { item ->
                        item.file.delete()
                        recordings = repo.getRecordings()
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        AddStreamDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                repo.addSource(name, url)
                sources = repo.getSources()
                showAddDialog = false
            }
        )
    }
}

@Composable
fun TrackedStreamsTab(
    sources: List<StreamSource>,
    activeIds: Set<String>,
    onToggleAuto: (String) -> Unit,
    onRecordToggle: (StreamSource) -> Unit,
    onDelete: (String) -> Unit
) {
    if (sources.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Tap + to add a live stream (.m3u8) URL to track")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(sources, key = { it.id }) { src ->
            val isRecording = activeIds.contains(src.id)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(src.name, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { onDelete(src.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    Text(
                        src.streamUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = src.autoRecord, onCheckedChange = { onToggleAuto(src.id) })
                            Spacer(Modifier.width(8.dp))
                            Text("Auto-Record")
                        }
                        Button(
                            onClick = { onRecordToggle(src) },
                            colors = if (isRecording) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else ButtonDefaults.buttonColors()
                        ) {
                            Text(if (isRecording) "Stop" else "Record Now")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingsTab(
    recordings: List<RecordingItem>,
    onPlay: (RecordingItem) -> Unit,
    onSaveToGallery: (RecordingItem) -> Unit,
    onDelete: (RecordingItem) -> Unit
) {
    if (recordings.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No saved live recordings yet")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(recordings, key = { it.file.absolutePath }) { item ->
            val mb = item.sizeBytes / (1024f * 1024f)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("%.2f MB".format(mb), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onPlay(item) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                    IconButton(onClick = { onSaveToGallery(item) }) {
                        Icon(Icons.Default.Download, contentDescription = "Save to Gallery")
                    }
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}

@Composable
fun AddStreamDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track Live Stream") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Channel / Creator Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL (.m3u8)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = { onAdd(name, url) }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
