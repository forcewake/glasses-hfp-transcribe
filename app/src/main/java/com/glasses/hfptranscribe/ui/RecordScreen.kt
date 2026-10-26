package com.glasses.hfptranscribe.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.glasses.hfptranscribe.MainViewModel
import com.glasses.hfptranscribe.RecordPhase
import com.glasses.hfptranscribe.audio.AudioRouteSelector
import com.glasses.hfptranscribe.audio.InputKind
import com.glasses.hfptranscribe.transcript.formatTimestamp

@Composable
fun RecordScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
) {
    val state by viewModel.recorder.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val scan by viewModel.devices.collectAsState()
    val download by viewModel.downloadProgress.collectAsState()
    val banner by viewModel.banner.collectAsState()
    val recording = state.phase == RecordPhase.RECORDING || state.phase == RecordPhase.PREPARING
    val glasses = AudioRouteSelector.selectGlasses(scan.candidates, settings.glassesName)
    val needsMic = viewModel.needsMicrophone()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Glasses Transcript", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Records the Ray-Ban Display microphone over Bluetooth and transcribes it on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.phase == RecordPhase.RECORDING) {
            RecordingBanner(state.inputLabel)
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !settings.usePhoneMic,
                onClick = { if (!recording) viewModel.setPhoneMic(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !recording,
            ) { Text("Glasses") }
            SegmentedButton(
                selected = settings.usePhoneMic,
                onClick = { if (!recording) viewModel.setPhoneMic(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !recording,
            ) { Text("Phone microphone") }
        }
        InputStatus(
            usePhone = settings.usePhoneMic,
            glassesName = glasses?.productName,
            scoCount = scan.candidates.count { it.kind == InputKind.BLUETOOTH_SCO },
            permissionMissing = scan.bluetoothPermissionMissing,
            activeLabel = if (recording) state.inputLabel else null,
        )
        LevelMeter(if (state.phase == RecordPhase.RECORDING) state.level else 0f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.sampleRate > 0 && recording) {
                    "${state.sampleRate / 1000} kHz mono · ${formatTimestamp(state.elapsedMs)}"
                } else {
                    state.modelLabel.ifBlank { settings.activeModel().title }
                },
                style = MaterialTheme.typography.labelLarge,
            )
            state.rtf?.let { rtf ->
                Spacer(Modifier.width(8.dp))
                Text(
                    "RTF ${"%.2f".format(rtf)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        download?.let { progress ->
            Text(progress.label, style = MaterialTheme.typography.bodyMedium)
            if (progress.fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        banner?.let { text ->
            Text(text, color = MaterialTheme.colorScheme.error)
        }
        state.error?.takeIf { state.phase == RecordPhase.ERROR }?.let { text ->
            Text(text, color = MaterialTheme.colorScheme.error)
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RecordButton(
                recording = recording,
                needsPermission = needsMic,
                enabled = recording || needsMic || settings.usePhoneMic || glasses != null,
                onClick = {
                    if (needsMic && !recording) onRequestPermissions() else viewModel.toggleRecording()
                },
            )
        }
        if (!recording && !settings.usePhoneMic && glasses == null) {
            Text(
                "No glasses microphone is connected. Pair the Ray-Ban Display, or switch to the phone microphone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TranscriptPane(
            lines = state.lines.map { "[${formatTimestamp(it.offsetMillis)}] ${it.text}" },
            partial = state.partial,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Recording stays visible here and in the notification. It is unknown whether the glasses' capture light turns on for this Bluetooth path, so tell people nearby.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordingBanner(label: String) {
    val pulse = rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RecordingRed)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(10.dp))
        Text("Recording · $label", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InputStatus(
    usePhone: Boolean,
    glassesName: String?,
    scoCount: Int,
    permissionMissing: Boolean,
    activeLabel: String?,
) {
    val phone = usePhone
    val text = activeLabel ?: if (phone) {
        AudioRouteSelector.phoneLabel()
    } else if (glassesName != null) {
        AudioRouteSelector.glassesLabel(glassesName)
    } else if (permissionMissing) {
        "Bluetooth permission is off, so the glasses cannot be selected"
    } else if (scoCount == 0) {
        "No Bluetooth headset connected"
    } else {
        "Bluetooth headset connected"
    }
    Surface(
        color = if (phone) PhoneAmber.copy(alpha = 0.16f) else GlassesGreen.copy(alpha = 0.16f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (phone) PhoneAmber else GlassesGreen,
        )
    }
}

@Composable
private fun LevelMeter(level: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.secondary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp),
    ) {
        drawRoundRect(color = track, cornerRadius = CornerRadius(8.dp.toPx()))
        drawRoundRect(
            color = fill,
            size = Size(size.width * level.coerceIn(0f, 1f), size.height),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
    }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    needsPermission: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(96.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recording) RecordingRed else MaterialTheme.colorScheme.primary,
        ),
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = when {
                needsPermission -> "Allow microphone"
                recording -> "Stop recording"
                else -> "Start recording"
            },
        )
    }
}

@Composable
private fun TranscriptPane(
    lines: List<String>,
    partial: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Transcript", style = MaterialTheme.typography.titleMedium)
        if (lines.isEmpty() && partial.isBlank()) {
            Text(
                "Speech will show up here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lines.forEach { line ->
            Text(line)
        }
        if (partial.isNotBlank()) {
            Text(
                partial,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
