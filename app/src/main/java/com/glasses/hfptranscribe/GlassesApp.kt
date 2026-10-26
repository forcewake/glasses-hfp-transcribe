package com.glasses.hfptranscribe

import android.app.Application
import com.glasses.hfptranscribe.audio.AudioRouteController
import com.glasses.hfptranscribe.session.SessionStore
import com.glasses.hfptranscribe.stt.ModelRepository
import java.io.File

class GlassesApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(app: Application) {
    val preferences = AppPreferences(app)
    val routes = AudioRouteController(app)
    val sessions = SessionStore(File(app.filesDir, "sessions"))
    val models = ModelRepository(app)
    val runtime = RecordingRuntime(
        routes = routes,
        sessions = sessions,
        models = models,
        prefs = preferences,
    )
}
