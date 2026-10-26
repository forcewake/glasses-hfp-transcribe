package com.glasses.hfptranscribe.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glasses.hfptranscribe.MainViewModel

private enum class Destination(val label: String) {
    Record("Record"),
    Sessions("Sessions"),
    Settings("Settings"),
}

@Composable
fun HomeScaffold(viewModel: MainViewModel) {
    var destination by rememberSaveable { mutableStateOf(Destination.Record) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = {
                            destination = item
                            viewModel.refresh()
                        },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    Destination.Record -> Icons.Filled.Mic
                                    Destination.Sessions -> Icons.Filled.Folder
                                    Destination.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (destination) {
            Destination.Record -> RecordScreen(
                viewModel = viewModel,
                modifier = modifier,
                onRequestPermissions = {
                    permissionLauncher.launch(viewModel.requiredPermissions())
                },
            )
            Destination.Sessions -> SessionsScreen(viewModel, modifier)
            Destination.Settings -> SettingsScreen(viewModel, modifier)
        }
    }
}

internal fun MainViewModel.needsMicrophone(): Boolean = needsPermissions()

internal const val MIC_PERMISSION = Manifest.permission.RECORD_AUDIO
