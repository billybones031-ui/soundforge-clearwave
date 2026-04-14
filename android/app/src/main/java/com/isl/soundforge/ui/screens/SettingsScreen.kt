package com.isl.soundforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isl.soundforge.audio.AudioProcessor
import com.isl.soundforge.ui.EngineViewModel
import com.isl.soundforge.ui.theme.IslColors
import com.isl.soundforge.ui.theme.SoundForgeTheme

@Composable
fun SettingsScreen(
    vm: EngineViewModel,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = SoundForgeTheme.colors
    var showApiKey by remember { mutableStateOf(false) }

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
                text = "SETTINGS",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.cyan,
                letterSpacing = 3.sp
            )
        }

        HorizontalDivider(color = colors.cyanDim, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Account ──────────────────────────────────────────────────────
            SettingsGroup(label = "ACCOUNT") {
                SettingsInfoRow(
                    icon = { Icon(Icons.Default.Person, null, tint = colors.cyan, modifier = Modifier.size(20.dp)) },
                    label = "Signed in as",
                    value = state.currentUser?.email ?: "—"
                )
            }

            // ── Gemini API ───────────────────────────────────────────────────
            SettingsGroup(label = "AI ENGINE") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = colors.cyan, modifier = Modifier.size(20.dp))
                        Text(
                            "  Gemini API Key",
                            fontSize = 13.sp,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = state.geminiApiKey,
                        onValueChange = vm::updateGeminiKey,
                        placeholder = { Text("Paste your API key here", fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.cyan,
                            unfocusedBorderColor = colors.cyanDim,
                            focusedTextColor     = colors.textPrimary,
                            unfocusedTextColor   = colors.textPrimary,
                            cursorColor          = colors.cyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Get a free key at aistudio.google.com. " +
                        "Used for AI audio analysis only — never sent with your audio.",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        lineHeight = 16.sp
                    )
                }
            }

            // ── Default processing options ───────────────────────────────────
            SettingsGroup(label = "DEFAULT PROCESSING") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val opts = state.processingOptions

                    LufsSlider(
                        value = opts.targetLUFS,
                        onValueChange = {
                            vm.updateProcessingOptions(opts.copy(targetLUFS = it))
                        }
                    )

                    NoiseSlider(
                        value = opts.noiseThreshold,
                        onValueChange = {
                            vm.updateProcessingOptions(opts.copy(noiseThreshold = it))
                        }
                    )
                }
            }

            // ── App info ─────────────────────────────────────────────────────
            SettingsGroup(label = "ABOUT") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsInfoRow(
                        icon = { Icon(Icons.Default.Info, null, tint = colors.cyan, modifier = Modifier.size(20.dp)) },
                        label = "Version",
                        value = "1.0.0"
                    )
                    Text(
                        "Infinite Signal Labs · Missoula, MT\n\"We listen to machines.\"",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // ── Sign out ─────────────────────────────────────────────────────────
        Button(
            onClick = {
                vm.signOut()
                onSignOut()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IslColors.Error.copy(alpha = 0.15f))
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, null, tint = IslColors.Error, modifier = Modifier.size(18.dp))
            Text(
                "  SIGN OUT",
                color = IslColors.Error,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    val colors = SoundForgeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 10.sp, color = colors.textSecondary, letterSpacing = 2.sp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .padding(14.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsInfoRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String
) {
    val colors = SoundForgeTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon()
        Text(label, fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = colors.textPrimary)
    }
}

@Composable
private fun LufsSlider(value: Float, onValueChange: (Float) -> Unit) {
    val colors = SoundForgeTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Target Loudness", fontSize = 13.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("%.0f LUFS".format(value), fontSize = 12.sp, color = colors.cyan)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -23f..-9f,
            steps = 13,
            colors = SliderDefaults.colors(
                thumbColor = colors.cyan,
                activeTrackColor = colors.cyan,
                inactiveTrackColor = colors.cyanDim
            )
        )
        Row {
            Text("-23 (broadcast)", fontSize = 10.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("-9 (loud)", fontSize = 10.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun NoiseSlider(value: Float, onValueChange: (Float) -> Unit) {
    val colors = SoundForgeTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Noise Aggressiveness", fontSize = 13.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("%.0f%%".format(value * 100), fontSize = 12.sp, color = colors.cyan)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.cyan,
                activeTrackColor = colors.cyan,
                inactiveTrackColor = colors.cyanDim
            )
        )
        Row {
            Text("Gentle", fontSize = 10.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("Aggressive", fontSize = 10.sp, color = colors.textSecondary)
        }
    }
}
