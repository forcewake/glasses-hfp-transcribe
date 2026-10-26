package com.glasses.hfptranscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glasses.hfptranscribe.MainViewModel
import com.glasses.hfptranscribe.stt.AppLanguage
import com.glasses.hfptranscribe.stt.Models
import com.glasses.hfptranscribe.stt.languagesSelectable
import com.glasses.hfptranscribe.stt.megabytes

@Composable
fun SettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val ready by viewModel.readyModels.collectAsState()
    val download by viewModel.downloadProgress.collectAsState()
    val banner by viewModel.banner.collectAsState()
    val nemotronReady = Models.nemotron.id in ready
    val languages = languagesSelectable(nemotronReady)
    val choices = Models.choicesFor(settings.language)
    val selectedId = when (settings.language) {
        AppLanguage.RU -> settings.russianModelId
        AppLanguage.EN -> settings.englishModelId
        AppLanguage.NL -> Models.nemotron.id
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Language", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { language ->
                val enabled = language in languages
                FilterChip(
                    selected = settings.language == language,
                    onClick = { if (enabled) viewModel.setLanguage(language) },
                    enabled = enabled,
                    label = { Text(language.label) },
                )
            }
        }
        if (!nemotronReady) {
            Text(
                "Dutch appears after the all-languages model is downloaded.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Model", style = MaterialTheme.typography.titleMedium)
        choices.forEach { model ->
            val downloaded = model.id in ready
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedId == model.id,
                        onClick = { viewModel.setModel(model) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedId == model.id, onClick = { viewModel.setModel(model) })
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(model.title)
                    Text(
                        model.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (downloaded) "Downloaded" else "Not downloaded · ${megabytes(model.downloadBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (!downloaded) {
                Button(
                    onClick = { viewModel.download(model) },
                    enabled = download == null,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(if (download?.modelId == model.id) "Downloading" else "Download")
                }
            }
        }
        download?.let { progress ->
            Text(progress.label)
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
        Text("Glasses name", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = settings.glassesName,
            onValueChange = viewModel::setGlassesName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Match this product name") },
            supportingText = {
                Text("Used when more than one Bluetooth headset is connected. Ray-Ban is the default.")
            },
            singleLine = true,
        )
        Text(
            "Audio and transcripts stay in this app's private storage. Nothing is uploaded. " +
                "Speech models download from the sherpa-onnx project on GitHub the first time you use them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
