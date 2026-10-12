package com.leedor.tts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.leedor.tts.data.PrefsManager
import com.leedor.tts.support.SupportCrashHandler
import com.leedor.tts.support.SupportLogStore
import com.leedor.tts.support.SupportReportManager
import com.leedor.tts.ui.theme.AppTheme
import com.leedor.tts.ui.theme.DarkThemeMode
import com.leedor.tts.ui.theme.LeedorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SupportCrashHandler.install(this)
        SupportReportManager.cleanupReports(this)
        SupportLogStore.log(this, "MainActivity", "Application UI started")
        enableEdgeToEdge()
        setContent {
            ThemedApp()
        }
    }
}

@Composable
fun ThemedApp() {
    val context = LocalContext.current
    val prefs = remember { PrefsManager(context) }
    
    // Collect reactivity
    val counter by prefs.themeCounter.collectAsState(initial = 0)
    
    // Read and parse theme settings
    val appTheme = remember(counter) {
        try { AppTheme.valueOf(prefs.appTheme) } catch (e: Exception) { AppTheme.BLUE }
    }
    
    val darkMode = remember(counter) {
        try { DarkThemeMode.valueOf(prefs.darkMode) } catch (e: Exception) { DarkThemeMode.FOLLOW_SYSTEM }
    }
    
    LeedorTheme(
        appTheme = appTheme,
        darkThemeMode = darkMode
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainScreen()
        }
    }
}
