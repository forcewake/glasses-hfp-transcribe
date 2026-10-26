package com.glasses.hfptranscribe

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glasses.hfptranscribe.service.RecordingService
import com.glasses.hfptranscribe.session.SessionSummary
import com.glasses.hfptranscribe.stt.AppLanguage
import com.glasses.hfptranscribe.stt.AsrModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as GlassesApp).graph

    val recorder = graph.runtime.state
    val devices = graph.runtime.scan
    val settings = MutableStateFlow(graph.preferences.snapshot())
    val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val readyModels = MutableStateFlow<Set<String>>(emptySet())
    val banner = MutableStateFlow<String?>(null)

    val downloadProgress: StateFlow<com.glasses.hfptranscribe.stt.DownloadProgress?> =
        graph.models.progress.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refresh()
    }

    fun refresh() {
        settings.value = graph.preferences.snapshot()
        graph.runtime.refreshDevices()
        sessions.value = graph.sessions.list()
        readyModels.value = com.glasses.hfptranscribe.stt.Models.all
            .filter { graph.models.isReady(it) }
            .map { it.id }
            .toSet()
    }

    fun microphoneGranted(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun setPhoneMic(usePhone: Boolean) {
        graph.preferences.update { it.copy(usePhoneMic = usePhone) }
        refresh()
    }

    fun setLanguage(language: AppLanguage) {
        graph.preferences.update { it.copy(language = language) }
        refresh()
    }

    fun setModel(model: AsrModel) {
        graph.preferences.update { current ->
            when (current.language) {
                AppLanguage.RU -> current.copy(russianModelId = model.id)
                AppLanguage.EN -> current.copy(englishModelId = model.id)
                AppLanguage.NL -> current.copy(language = AppLanguage.NL)
            }
        }
        refresh()
    }

    fun setGlassesName(name: String) {
        graph.preferences.update { it.copy(glassesName = name) }
        refresh()
    }

    fun download(model: AsrModel) {
        viewModelScope.launch {
            banner.value = null
            try {
                graph.models.download(model)
            } catch (error: Throwable) {
                banner.value = error.message ?: "Download failed"
            } finally {
                refresh()
            }
        }
    }

    fun clearBanner() {
        banner.value = null
    }

    fun toggleRecording() {
        val phase = recorder.value.phase
        if (phase == RecordPhase.RECORDING || phase == RecordPhase.PREPARING) {
            graph.runtime.stop()
            return
        }
        val intent = Intent(getApplication(), RecordingService::class.java)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun deleteSession(id: String) {
        graph.sessions.delete(id)
        refresh()
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
