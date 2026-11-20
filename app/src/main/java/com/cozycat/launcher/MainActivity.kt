package com.cozycat.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var apps by mutableStateOf<List<LauncherApp>>(emptyList())
    private var homeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Perf.exp = intent?.getIntExtra("exp", 0) ?: 0
        setContent {
            CozyCatTheme {
                LauncherScreen(
                    apps = apps,
                    homeSignal = homeSignal,
                    onLaunch = ::launchApp,
                    onSystemAction = ::runSystemAction,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh on every resume so newly installed / removed apps show up.
        lifecycleScope.launch {
            apps = withContext(Dispatchers.Default) { AppRepository(applicationContext).load() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
    }

    private fun launchApp(app: LauncherApp) {
        try {
            startActivity(app.intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runSystemAction(action: SystemAction) {
        val candidates = when (action) {
            SystemAction.SEARCH -> listOf(
                Intent("android.search.action.GLOBAL_SEARCH"),
                Intent(Intent.ACTION_ASSIST),
            )
            SystemAction.CAST -> listOf(Intent(Settings.ACTION_CAST_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            SystemAction.SETTINGS -> listOf(Intent(Settings.ACTION_SETTINGS))
            SystemAction.ACCOUNT -> listOf(Intent(Settings.ACTION_SYNC_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // try the next candidate
            }
        }
        Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
    }
}
