package com.glasses.hfptranscribe

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.glasses.hfptranscribe.service.RecordingService
import com.glasses.hfptranscribe.stt.AppLanguage
import com.glasses.hfptranscribe.stt.Models
import java.io.File

/**
 * Debug-only file transcription. There is no button for this. A debug build
 * starts it from an adb intent so an emulator can feed a known WAV through
 * the same recognizer, session files, and recording screen as the microphone.
 * Release builds replace this type with a no-op.
 */
object DebugWav {
    const val ACTION = "com.glasses.hfptranscribe.action.DEBUG_TRANSCRIBE_WAV"
    const val EXTRA_WAV = "wav"
    const val EXTRA_MODEL = "model"

    fun launchFromActivity(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val service = Intent(context, RecordingService::class.java).apply {
            action = ACTION
            putExtra(EXTRA_WAV, intent.getStringExtra(EXTRA_WAV))
            putExtra(EXTRA_MODEL, intent.getStringExtra(EXTRA_MODEL))
        }
        ContextCompat.startForegroundService(context, service)
    }

    fun startFromService(app: GlassesApp, intent: Intent?, onStatus: (String) -> Unit): Boolean {
        if (intent?.action != ACTION) return false
        val path = intent.getStringExtra(EXTRA_WAV)?.takeIf { it.isNotBlank() } ?: return false
        val model = intent.getStringExtra(EXTRA_MODEL)?.let { Models.byId(it) }
            ?: app.graph.preferences.snapshot().activeModel()
        app.graph.preferences.update { current ->
            when (model.id) {
                Models.enKroko.id -> current.copy(language = AppLanguage.EN, englishModelId = model.id)
                Models.ruSmall.id, Models.ruTone.id -> current.copy(
                    language = AppLanguage.RU,
                    russianModelId = model.id,
                )
                else -> current
            }
        }
        app.graph.runtime.transcribeFile(File(path), model, onStatus)
        return true
    }
}
