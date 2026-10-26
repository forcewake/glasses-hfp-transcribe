package com.glasses.hfptranscribe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasses.hfptranscribe.ui.GlassesTheme
import com.glasses.hfptranscribe.ui.HomeScaffold

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DebugWav.launchFromActivity(this, intent)
        setContent {
            GlassesTheme {
                val viewModel: MainViewModel = viewModel()
                HomeScaffold(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DebugWav.launchFromActivity(this, intent)
    }
}
