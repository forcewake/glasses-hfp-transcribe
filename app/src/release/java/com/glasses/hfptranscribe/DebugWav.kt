package com.glasses.hfptranscribe

import android.content.Context
import android.content.Intent

/** Release builds have no file-transcription entry point. */
object DebugWav {
    fun launchFromActivity(context: Context, intent: Intent?) = Unit

    fun startFromService(app: GlassesApp, intent: Intent?, onStatus: (String) -> Unit): Boolean = false
}
