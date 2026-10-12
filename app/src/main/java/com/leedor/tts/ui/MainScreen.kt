package com.leedor.tts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leedor.tts.data.PrefsManager
import com.leedor.tts.ui.navigation.Screen
import com.leedor.tts.ui.screens.SettingsScreen
import com.leedor.tts.ui.screens.VoicesScreen
import com.leedor.tts.ui.screens.VoiceRecorderScreen
import com.leedor.tts.ui.screens.OnboardingScreen
import com.leedor.tts.ui.screens.ModelManagerScreen

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val prefs = remember { PrefsManager(context) }
    
    val startDestination = if (prefs.isOnboardingComplete) Screen.Voices.route else Screen.Onboarding.route
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // Hide bottom bar on certain screens
    val hideBottomBarRoutes = listOf(
        Screen.Onboarding.route,
        Screen.VoiceRecorder.route,
        Screen.ModelManager.route
    )
    val showBottomBar = currentRoute !in hideBottomBarRoutes
    
    // State for voice cloning callback (Path, Name, Transcript)
    var pendingVoiceCloneData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(navController)
            }
        },
        contentWindowInsets = WindowInsets.navigationBars
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(navController = navController)
            }
            
            composable(Screen.Voices.route) {
                VoicesScreen(
                    navController = navController,
                    pendingVoiceCloneData = pendingVoiceCloneData,
                    onVoiceCloneHandled = { pendingVoiceCloneData = null }
                )
            }
            
            composable(Screen.Settings.route) {
                SettingsScreen(navController = navController)
            }
            
            composable(Screen.VoiceRecorder.route) {
                VoiceRecorderScreen(
                    navController = navController,
                    onVoiceRecorded = { path: String, name: String, transcript: String ->
                        pendingVoiceCloneData = Triple(path, name, transcript)
                    }
                )
            }
            
            composable(Screen.ModelManager.route) {
                com.leedor.tts.ui.screens.ModelManagerScreen(navController = navController)
            }
        }
    }
}

@Composable
fun BottomNavBar(navController: NavHostController) {
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        val items = listOf(
            Screen.Voices to Icons.AutoMirrored.Filled.List,
            Screen.Settings to Icons.Default.Settings
        )

        items.forEach { (screen, icon) ->
            val title = stringResource(screen.titleRes)
            NavigationBarItem(
                icon = { Icon(icon, contentDescription = title) },
                label = { Text(title) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
