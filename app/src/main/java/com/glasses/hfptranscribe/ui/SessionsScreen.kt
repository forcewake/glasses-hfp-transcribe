package com.glasses.hfptranscribe.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.glasses.hfptranscribe.MainViewModel
import com.glasses.hfptranscribe.session.SessionSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SessionsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val sessions by viewModel.sessions.collectAsState()
    val context = LocalContext.current
    var playingId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Sessions", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Each recording is a WAV file with a timestamped transcript.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (sessions.isEmpty()) {
            Text("No recordings yet.")
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    playing = playingId == session.id,
                    onPlay = {
                        player?.release()
                        if (playingId == session.id) {
                            player = null
                            playingId = null
                        } else {
                            val created = MediaPlayer().apply {
                                setDataSource(session.wav.absolutePath)
                                setOnCompletionListener {
                                    playingId = null
                                    release()
                                    player = null
                                }
                                prepare()
                                start()
                            }
                            player = created
                            playingId = session.id
                        }
                    },
                    onShare = {
                        val authority = "${context.packageName}.fileprovider"
                        val streams = arrayListOf(
                            FileProvider.getUriForFile(context, authority, session.wav),
                        )
                        if (session.transcript.isFile) {
                            streams += FileProvider.getUriForFile(context, authority, session.transcript)
                        }
                        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
                            putExtra(Intent.EXTRA_SUBJECT, "Glasses recording ${session.id}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Share recording"))
                    },
                    onDelete = { pendingDelete = session },
                )
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this recording?") },
            text = { Text("The audio and transcript will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingId == session.id) {
                        player?.release()
                        player = null
                        playingId = null
                    }
                    viewModel.deleteSession(session.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatWhen(session.startedAtEpochMs), style = MaterialTheme.typography.titleMedium)
            Text(session.inputLabel.ifBlank { session.source })
            Text(
                "${formatDuration(session.durationMs)} · ${session.modelTitle}" +
                    (session.rtf?.let { " · RTF ${"%.2f".format(it)}" } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

private fun formatWhen(epochMs: Long): String {
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
