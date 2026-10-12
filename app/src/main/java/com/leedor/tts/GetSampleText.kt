package com.leedor.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Activity to provide sample text for TTS testing.
 * Called by Settings when testing the TTS engine.
 */
class GetSampleText : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val result = Intent()
        result.putExtra(
            TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
            "你好，我是侍读郎，一个完全离线的中文语音朗读引擎。"
        )
        
        setResult(RESULT_OK, result)
        finish()
    }
}
