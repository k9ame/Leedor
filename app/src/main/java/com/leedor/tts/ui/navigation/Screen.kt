package com.leedor.tts.ui.navigation

import androidx.annotation.StringRes
import com.leedor.tts.R

sealed class Screen(val route: String, @StringRes val titleRes: Int) {
    object Onboarding : Screen("onboarding", R.string.nav_onboarding)
    object Voices : Screen("voices", R.string.nav_voices)
    object Models : Screen("models", R.string.nav_models)
    object Settings : Screen("settings", R.string.nav_settings)
    object VoiceCloning : Screen("voice_cloning", R.string.nav_voice_cloning)
    object VoiceRecorder : Screen("voice_recorder", R.string.nav_voice_recorder)
    object ModelManager : Screen("model_manager", R.string.nav_model_manager)
}
