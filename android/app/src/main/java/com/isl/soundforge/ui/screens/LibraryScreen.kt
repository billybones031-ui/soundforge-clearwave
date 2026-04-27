package com.isl.soundforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isl.soundforge.ui.AudioItem
import com.isl.soundforge.ui.EngineViewModel
import com.isl.soundforge.ui.theme.IslColors
import com.isl.soundforge.ui.theme.SoundForgeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    vm: EngineViewModel,
    onOpenFile: (AudioItem) -> Unit,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = SoundForgeTheme.colors
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<AudioItem?>(null) }

    LaunchedEffect(Unit) { vm.loadLibrary() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.textSecondary)
            }
            Text(
                text = "LIBRARY",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.cyan,
                letterSpacing = 3.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.libraryItems.size} files",
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(end = 16.dp)
            )
        }

        HorizontalDivider(color = colors.cyanDim, thickness = 0.5.dp)

        if (state.libraryItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AudioFile,
                        null,
                        tint = colors.cyanDim,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No enhanced files yet.", color = colors.textSecondary, fontSize = 14.sp)
                    Text(
                        "Process an audio file to see it here.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp, vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.libraryItems, key = { it.id }) { item ->
                    LibraryRow(
                        item = item,
                        onClick = { onOpenFile(item) },
                        onDelete = { pendingDelete = item },
                        onShare = {
                            val uri = item.uri ?: return@LibraryRow
                            val file = java.io.File(uri.path ?: return@LibraryRow)
                            val shareUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                putExtra(Intent.EXTRA_SUBJECT, item.name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Share ${item.name}")
                            )
                        }
                    )
                }
            }
        }
    }

    // ── Delete confirmation dialog ───────────────────────────────────────────
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?", color = colors.textPrimary) },
            text = {
                Text(
                    "\"${item.name}\" will be permanently deleted from your library and cloud storage.",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteLibraryItem(item)
                    pendingDelete = null
                }) {
                    Text("DELETE", color = IslColors.Error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
            containerColor = colors.surface,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun LibraryRow(
    item: AudioItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val colors = SoundForgeTheme.colors
    val dateStr = remember(item.createdAt) {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(item.createdAt))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.AudioFile,
            null,
            tint = if (item.isProcessed) colors.magenta else colors.cyan,
            modifier = Modifier.size(28.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.durationMs > 0) {
                    Text(
                        formatDurationLib(item.durationMs),
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
                Text(dateStr, fontSize = 11.sp, color = colors.textSecondary)
                if (item.isProcessed) {
                    Text(
                        "✦ enhanced",
                        fontSize = 10.sp,
                        color = colors.magenta
                    )
                }
            }
        }

        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Share, "Share", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete", tint = IslColors.Error.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatDurationLib(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
