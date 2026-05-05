package com.clipvault.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipvault.app.ClipVaultApp
import com.clipvault.app.data.ClipEntity
import com.clipvault.app.data.ClipRepository
import com.clipvault.app.service.ClipboardAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ClipVaultApp
        setContent {
            ClipVaultTheme {
                MainScreen(repository = app.repository)
            }
        }
    }
}

@Composable
fun ClipVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6200EE),
            onPrimary = Color.White,
            surface = Color(0xFF1E1E2E),
            onSurface = Color(0xFFEEEEF2),
            background = Color(0xFF121220),
            onBackground = Color(0xFFEEEEF2),
            surfaceVariant = Color(0xFF2A2A3E),
            onSurfaceVariant = Color(0xFFCCCCD6)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: ClipRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pinnedFirst by remember { mutableStateOf(true) }
    val selectedIds = remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode by remember { derivedStateOf { selectedIds.value.isNotEmpty() } }
    val clipToDelete = remember { mutableStateOf<Long?>(null) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    val clips by remember(searchQuery, pinnedFirst) {
        if (searchQuery.isBlank()) {
            repository.getAllClips(pinnedFirst)
        } else {
            repository.searchClips(searchQuery, pinnedFirst)
        }
    }.collectAsState(initial = emptyList())

    val isServiceEnabled = remember {
        mutableStateOf(ClipboardAccessibilityService.instance != null)
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        isServiceEnabled.value = ClipboardAccessibilityService.instance != null
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                // Multi-select toolbar
                TopAppBar(
                    title = { Text("${selectedIds.value.size} selected") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF3700B3),
                        titleContentColor = Color.White
                    ),
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.value = emptySet() }) {
                            Icon(Icons.Default.Close, "Cancel", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                repository.pinMany(selectedIds.value.toList())
                                selectedIds.value = emptySet()
                            }
                        }) {
                            Icon(Icons.Filled.PushPin, "Pin selected", tint = Color.White)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                repository.unpinMany(selectedIds.value.toList())
                                selectedIds.value = emptySet()
                            }
                        }) {
                            Icon(Icons.Outlined.PushPin, "Unpin selected", tint = Color.White)
                        }
                        IconButton(onClick = {
                            showDeleteSelectedDialog = true
                        }) {
                            Icon(Icons.Default.Delete, "Delete selected", tint = Color(0xFFFF6B6B))
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("ClipVault", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    actions = {
                        // Sort toggle
                        IconButton(onClick = { pinnedFirst = !pinnedFirst }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = if (pinnedFirst) "Sort: pinned first" else "Sort: recent first",
                                tint = if (pinnedFirst) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear unpinned",
                                tint = Color(0xFFFF6B6B)
                            )
                        }
                        IconButton(onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add clip")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Service status banner
            if (!isServiceEnabled.value && !isSelectionMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    color = Color(0xFF3700B3)
                ) {
                    Text(
                        "⚠️ Accessibility service not enabled. Tap to enable.",
                        modifier = Modifier.padding(16.dp),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }

            // Sort indicator chip
            if (!isSelectionMode) {
                Text(
                    text = if (pinnedFirst) "📌 Pinned first" else "⏱ Recent first",
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search clips…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Clips list
            if (clips.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searchQuery.isBlank()) "No clips yet. Copy some text to get started!"
                        else "No clips match your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(clips, key = { it.id }) { clip ->
                        ClipCard(
                            clip = clip,
                            isSelected = selectedIds.value.contains(clip.id),
                            isSelectionMode = isSelectionMode,
                            onTap = {
                                if (isSelectionMode) {
                                    selectedIds.value = if (selectedIds.value.contains(clip.id))
                                        selectedIds.value - clip.id
                                    else
                                        selectedIds.value + clip.id
                                }
                            },
                            onLongPress = {
                                if (!isSelectionMode) {
                                    selectedIds.value = setOf(clip.id)
                                }
                            },
                            onPinToggle = { scope.launch { repository.togglePin(clip.id) } },
                            onDelete = { clipToDelete.value = clip.id }
                        )
                    }
                }
            }
        }
    }

    // Add clip dialog
    if (showAddDialog) {
        var newText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add clip") },
            text = {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    placeholder = { Text("Enter text to save…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        scope.launch { repository.addClip(newText) }
                        showAddDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Confirm clear dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear unpinned clips?") },
            text = { Text("This will delete all unpinned clips. Pinned clips will be kept.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteAllUnpinned() }
                    showClearDialog = false
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Confirm single clip delete
    if (clipToDelete.value != null) {
        AlertDialog(
            onDismissRequest = { clipToDelete.value = null },
            title = { Text("Delete this clip?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.delete(clipToDelete.value!!) }
                    clipToDelete.value = null
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { clipToDelete.value = null }) { Text("Cancel") }
            }
        )
    }

    // Confirm multi-select delete
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete ${selectedIds.value.size} clips?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteMany(selectedIds.value.toList())
                        selectedIds.value = emptySet()
                    }
                    showDeleteSelectedDialog = false
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipCard(
    clip: ClipEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onPinToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val bgColor = if (isSelected) Color(0x44BB86FC) else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onTap() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = clip.preview,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    fontWeight = if (clip.isPinned) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = android.text.format.DateUtils.getRelativeTimeSpanString(
                        clip.createdAt,
                        System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS,
                        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
                    ).toString(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isSelectionMode) {
                    Row {
                        IconButton(onClick = onPinToggle, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (clip.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (clip.isPinned) "Unpin" else "Pin",
                                tint = if (clip.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
