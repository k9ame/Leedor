package com.leedor.tts

import android.app.Activity
import android.os.Bundle

/**
 * Activity for installing voice data.
 * For 侍读郎, voice data is bundled in the APK, so this is a no-op.
 */
class InstallVoiceData : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Voice data is bundled in the APK, nothing to install
        setResult(RESULT_OK)
        finish()
    }
}
