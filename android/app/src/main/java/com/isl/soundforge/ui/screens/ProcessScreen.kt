package com.isl.soundforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isl.soundforge.audio.AudioProcessor
import com.isl.soundforge.ui.AudioItem
import com.isl.soundforge.ui.EngineViewModel
import com.isl.soundforge.ui.ProcessingState
import com.isl.soundforge.ui.theme.IslColors
import com.isl.soundforge.ui.theme.SoundForgeTheme

@Composable
fun ProcessScreen(vm: EngineViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = SoundForgeTheme.colors

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.onFilePicked(uri) }

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
                text = "PROCESS",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.cyan,
                letterSpacing = 3.sp,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = colors.cyanDim, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── File selection ───────────────────────────────────────────────
            SectionLabel("INPUT")
            if (state.selectedItem == null) {
                EmptyDropZone(onClick = { filePicker.launch("audio/*") })
            } else {
                FileInfoCard(state.selectedItem!!)
            }

            // ── Waveform display ─────────────────────────────────────────────
            if (state.selectedItem != null) {
                SectionLabel("WAVEFORM")
                WaveformDisplay(
                    original = state.selectedItem!!.waveformData,
                    // Only pass processed waveform if it actually has data; an empty
                    // FloatArray(0) would show the legend with no visible line.
                    processed = state.processedItem?.waveformData?.takeIf { it.isNotEmpty() }
                )
            }

            // ── AI analysis result ───────────────────────────────────────────
            state.geminiAnalysis?.let { analysis ->
                SectionLabel("AI ANALYSIS")
                AiAnalysisCard(analysis.recommendation, analysis.explanation)
            }

            // ── Processing options ───────────────────────────────────────────
            SectionLabel("OPTIONS")
            OptionsPanel(
                options = state.processingOptions,
                useCloud = state.useCloudProcessing,
                onOptionsChange = vm::updateProcessingOptions,
                onToggleCloud = vm::toggleCloudProcessing
            )

            // ── Processing state / progress ──────────────────────────────────
            when (val ps = state.processingState) {
                is ProcessingState.Idle -> Unit
                is ProcessingState.Uploading -> ProgressCard("Uploading…", ps.progress)
                is ProcessingState.Analyzing -> LoadingCard("Analyzing with AI…")
                is ProcessingState.Processing -> ProgressCard(ps.label, ps.progress)
                is ProcessingState.Downloading -> LoadingCard("Downloading result…")
                is ProcessingState.Done -> DoneCard(ps.outputItem)
                is ProcessingState.Error -> ErrorCard(ps.message)
            }
        }

        // ── Enhance button ───────────────────────────────────────────────────
        val busy = state.processingState !is ProcessingState.Idle &&
                   state.processingState !is ProcessingState.Done &&
                   state.processingState !is ProcessingState.Error
        val alreadyProcessed = state.selectedItem?.isProcessed == true
        val canEnhance = state.selectedItem != null && !busy && !alreadyProcessed

        Button(
            onClick = vm::enhance,
            enabled = canEnhance,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 20.dp, vertical = 4.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.cyan,
                disabledContainerColor = colors.cyanDim
            )
        ) {
            when {
                busy -> CircularProgressIndicator(color = IslColors.Void, modifier = Modifier.size(20.dp))
                alreadyProcessed -> {
                    Icon(Icons.Default.CheckCircle, null, tint = IslColors.Void)
                    Text(
                        "  ALREADY ENHANCED",
                        color = IslColors.Void,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                else -> {
                    Icon(Icons.Default.AutoFixHigh, null, tint = IslColors.Void)
                    Text(
                        "  ENHANCE",
                        color = IslColors.Void,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = SoundForgeTheme.colors.textSecondary,
        letterSpacing = 2.sp
    )
}

@Composable
private fun EmptyDropZone(onClick: () -> Unit) {
    val colors = SoundForgeTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.cyanDim, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.FileUpload, null, tint = colors.cyanDim, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap to select an audio file",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun FileInfoCard(item: AudioItem) {
    val colors = SoundForgeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.FileUpload, null, tint = colors.cyan, modifier = Modifier.size(28.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            val info = buildString {
                if (item.durationMs > 0) append(formatDurationProcess(item.durationMs))
                if (item.sizeBytes > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(formatBytes(item.sizeBytes))
                }
            }
            if (info.isNotEmpty()) {
                Text(info, fontSize = 11.sp, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun WaveformDisplay(original: FloatArray, processed: FloatArray?) {
    val colors = SoundForgeTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // Grid line
            drawLine(
                color = IslColors.GridLine,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 0.5f
            )

            // Original waveform (cyan)
            if (original.isNotEmpty()) {
                val step = w / original.size
                original.forEachIndexed { i, amp ->
                    val x = i * step
                    val halfH = amp * midY * 0.85f
                    drawLine(
                        color = IslColors.Cyan.copy(alpha = if (processed != null) 0.4f else 0.8f),
                        start = Offset(x, midY - halfH),
                        end = Offset(x, midY + halfH),
                        strokeWidth = maxOf(1f, step * 0.5f)
                    )
                }
            }

            // Processed waveform (magenta overlay)
            if (processed != null && processed.isNotEmpty()) {
                val step = w / processed.size
                processed.forEachIndexed { i, amp ->
                    val x = i * step
                    val halfH = amp * midY * 0.85f
                    drawLine(
                        color = IslColors.Magenta.copy(alpha = 0.8f),
                        start = Offset(x, midY - halfH),
                        end = Offset(x, midY + halfH),
                        strokeWidth = maxOf(1f, step * 0.5f)
                    )
                }
            }
        }

        // Legend
        if (processed != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegendDot(IslColors.Cyan.copy(alpha = 0.5f), "Original")
                LegendDot(IslColors.Magenta, "Enhanced")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Text(" $label", fontSize = 9.sp, color = SoundForgeTheme.colors.textSecondary)
    }
}

@Composable
private fun AiAnalysisCard(recommendation: String, explanation: String) {
    val colors = SoundForgeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(0.5.dp, colors.magenta.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "✦ $recommendation",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colors.magenta
        )
        Spacer(Modifier.height(6.dp))
        Text(explanation, fontSize = 12.sp, color = colors.textSecondary, lineHeight = 18.sp)
    }
}

@Composable
private fun OptionsPanel(
    options: AudioProcessor.ProcessingOptions,
    useCloud: Boolean,
    onOptionsChange: (AudioProcessor.ProcessingOptions) -> Unit,
    onToggleCloud: (Boolean) -> Unit
) {
    val colors = SoundForgeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToggleRow("Noise Reduction", options.noiseReduction) {
            onOptionsChange(options.copy(noiseReduction = it))
        }
        ToggleRow("Room Correction", options.roomCorrection) {
            onOptionsChange(options.copy(roomCorrection = it))
        }
        ToggleRow("Loudness Normalization", options.normalization) {
            onOptionsChange(options.copy(normalization = it))
        }
        HorizontalDivider(color = colors.cyanDim, thickness = 0.5.dp)
        ToggleRow("Cloud Processing (Python backend)", useCloud, onToggleCloud)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val colors = SoundForgeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = IslColors.Void,
                checkedTrackColor = colors.cyan
            )
        )
    }
}

@Composable
private fun ProgressCard(label: String, progress: Float) {
    val colors = SoundForgeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = colors.cyan,
                strokeWidth = 2.dp
            )
            Text(
                "  $label",
                fontSize = 13.sp,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(progress * 100).toInt()}%",
                fontSize = 12.sp,
                color = colors.cyan
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = colors.cyan,
            trackColor = colors.cyanDim
        )
    }
}

@Composable
private fun LoadingCard(label: String) {
    val colors = SoundForgeTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "alpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = colors.magenta.copy(alpha = alpha),
            strokeWidth = 2.dp
        )
        Text("  $label", fontSize = 13.sp, color = colors.textPrimary)
    }
}

@Composable
private fun DoneCard(item: AudioItem) {
    val colors = SoundForgeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(0.5.dp, IslColors.Success.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = IslColors.Success, modifier = Modifier.size(22.dp))
        Text(
            "  Enhanced: ${item.name}",
            fontSize = 13.sp,
            color = IslColors.Success,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    val colors = SoundForgeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(0.5.dp, IslColors.Error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = IslColors.Error, modifier = Modifier.size(22.dp))
        Text(
            "  $message",
            fontSize = 12.sp,
            color = IslColors.Error,
            modifier = Modifier.weight(1f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilities
// ─────────────────────────────────────────────────────────────────────────────

private fun formatDurationProcess(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}
