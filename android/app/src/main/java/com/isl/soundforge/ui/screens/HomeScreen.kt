package com.isl.soundforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isl.soundforge.ui.EngineViewModel
import com.isl.soundforge.ui.Screen
import com.isl.soundforge.ui.theme.IslColors
import com.isl.soundforge.ui.theme.SoundForgeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: EngineViewModel,
    onNavigate: (Screen) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = SoundForgeTheme.colors

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            vm.onFilePicked(uri)
            onNavigate(Screen.PROCESS)
        }
    }

    LaunchedEffect(Unit) {
        vm.loadLibrary()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SOUNDFORGE",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.cyan,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "Hello, ${state.currentUser?.displayName ?: state.currentUser?.email ?: "Creator"}",
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }
            IconButton(onClick = { onNavigate(Screen.SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Quick actions ────────────────────────────────────────────────────
        Text(
            text = "QUICK ACTION",
            fontSize = 10.sp,
            color = colors.textSecondary,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionCard(
                icon = Icons.Default.Mic,
                label = "RECORD",
                accentColor = colors.magenta,
                modifier = Modifier.weight(1f),
                onClick = {
                    // TODO: request RECORD_AUDIO permission before navigating
                    onNavigate(Screen.PROCESS)
                }
            )
            ActionCard(
                icon = Icons.Default.AudioFile,
                label = "IMPORT",
                accentColor = colors.cyan,
                modifier = Modifier.weight(1f),
                onClick = { filePicker.launch("audio/*") }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Nav grid ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NavCard(
                icon = Icons.Default.LibraryMusic,
                label = "LIBRARY",
                count = state.libraryItems.size,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.LIBRARY) }
            )
            NavCard(
                icon = Icons.Default.GraphicEq,
                label = "PROCESS",
                count = null,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Screen.PROCESS) }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Recent files ─────────────────────────────────────────────────────
        Text(
            text = "RECENT",
            fontSize = 10.sp,
            color = colors.textSecondary,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(8.dp))

        if (state.libraryItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .border(0.5.dp, colors.cyanDim, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No files yet.\nRecord or import audio to get started.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.libraryItems.take(5)) { item ->
                    RecentFileRow(
                        name = item.name,
                        durationMs = item.durationMs,
                        createdAt = item.createdAt,
                        waveform = item.waveformData,
                        onClick = {
                            vm.selectLibraryItem(item)
                            onNavigate(Screen.PROCESS)
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionCard(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = SoundForgeTheme.colors
    Box(
        modifier = modifier
            .height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = accentColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = accentColor, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun NavCard(
    icon: ImageVector,
    label: String,
    count: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = SoundForgeTheme.colors
    Box(
        modifier = modifier
            .height(70.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, letterSpacing = 1.sp)
            if (count != null) {
                Spacer(Modifier.weight(1f))
                Text(count.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.cyan)
            }
        }
    }
}

@Composable
private fun RecentFileRow(
    name: String,
    durationMs: Long,
    createdAt: Long,
    waveform: FloatArray,
    onClick: () -> Unit
) {
    val colors = SoundForgeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mini waveform
        WaveformThumbnail(waveform, modifier = Modifier.size(48.dp, 32.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatDuration(durationMs) + "  ·  " +
                SimpleDateFormat("MMM d", Locale.US).format(Date(createdAt)),
                fontSize = 11.sp,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun WaveformThumbnail(data: FloatArray, modifier: Modifier = Modifier) {
    val cyanColor = IslColors.Cyan.copy(alpha = 0.8f)
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val step = w / data.size
        data.forEachIndexed { i, amp ->
            val x = i * step
            val halfH = amp * midY * 0.9f
            drawLine(
                color = cyanColor,
                start = Offset(x, midY - halfH),
                end = Offset(x, midY + halfH),
                strokeWidth = maxOf(1f, step * 0.6f)
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
