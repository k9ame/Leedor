package com.leedor.tts.data

data class VoiceDefinition(
    val id: String,
    val name: String,
    val gender: String, // "Male" or "Female"
    val region: String = "US",
    val description: String? = null,
    val sampleUrl: String? = null, // Link to HuggingFace sample
    val modelType: String // "pocket_v1", "kitten_nano"
)

object VoiceDefinitions {
    
    // Pocket-TTS Standard Voices (Kyutai) - All speak English only
    val POCKET_VOICES = listOf(
        VoiceDefinition("alba", "Alba", "Male", "English", "Casual speaking style", "https://huggingface.co/kyutai/tts-voices/blob/main/alba-mackenna/casual.wav", "pocket_v1"),
        VoiceDefinition("marius", "Marius", "Male", "English", "Selfie (Voice Donation)", "https://huggingface.co/kyutai/tts-voices/blob/main/voice-donations/Selfie.wav", "pocket_v1"),
        VoiceDefinition("javert", "Javert", "Male", "English", "Butter (Voice Donation)", "https://huggingface.co/kyutai/tts-voices/blob/main/voice-donations/Butter.wav", "pocket_v1"),
        VoiceDefinition("jean", "Jean", "Male", "English", "EARS p010 speaker", "https://huggingface.co/kyutai/tts-voices/blob/main/ears/p010/freeform_speech_01.wav", "pocket_v1"),
        VoiceDefinition("fantine", "Fantine", "Female", "English", "VCTK speaker p244", "https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p244_023.wav", "pocket_v1"),
        VoiceDefinition("cosette", "Cosette", "Female", "English", "Expresso - Confused", "https://huggingface.co/kyutai/tts-voices/blob/main/expresso/ex04-ex02_confused_001_channel1_499s.wav", "pocket_v1"),
        VoiceDefinition("eponine", "Eponine", "Female", "English", "VCTK speaker p262", "https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p262_023.wav", "pocket_v1"),
        VoiceDefinition("azelma", "Azelma", "Female", "English", "VCTK speaker p303", "https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p303_023.wav", "pocket_v1")
    )

    // Kokoro v1.0 的预设语音已随该模型下架删除：它的语音表已经改为由
    // assets/models.json 的 voices 字段（Kokoro v1.1-zh）和「语音库」按需下载提供。

    // Celebrity Voices (Downloaded on-demand from HuggingFace)
    // WARNING: Celebrity voice cloning raises ethical/legal concerns. Use responsibly.
    val CELEBRITY_VOICES = listOf(
        // Politicians
        VoiceDefinition("celebrity_trump", "Donald Trump", "Male", "US", "⭐ Former US President", null, "pocket_v1"),
        VoiceDefinition("celebrity_biden", "Joe Biden", "Male", "US", "⭐ US President", null, "pocket_v1"),
        VoiceDefinition("celebrity_obama", "Barack Obama", "Male", "US", "⭐ Former US President", null, "pocket_v1"),
        VoiceDefinition("celebrity_hillary", "Hillary Clinton", "Female", "US", "⭐ Former Secretary of State", null, "pocket_v1"),
        VoiceDefinition("celebrity_kamala", "Kamala Harris", "Female", "US", "⭐ US Vice President", null, "pocket_v1"),
        // Tech Leaders
        VoiceDefinition("celebrity_musk", "Elon Musk", "Male", "US", "⭐ Tech Entrepreneur", null, "pocket_v1"),
        VoiceDefinition("celebrity_bill_gates", "Bill Gates", "Male", "US", "⭐ Microsoft co-founder", null, "pocket_v1"),
        VoiceDefinition("celebrity_zuckerberg", "Mark Zuckerberg", "Male", "US", "⭐ Meta CEO", null, "pocket_v1"),
        VoiceDefinition("celebrity_jensen", "Jensen Huang", "Male", "US", "⭐ NVIDIA CEO", null, "pocket_v1"),
        // Media & Authors
        VoiceDefinition("celebrity_oprah", "Oprah Winfrey", "Female", "US", "⭐ Media Personality", null, "pocket_v1"),
        VoiceDefinition("celebrity_jk_rowling", "J.K. Rowling", "Female", "UK", "⭐ Harry Potter Author", null, "pocket_v1"),
        // Activists & Others
        VoiceDefinition("celebrity_greta", "Greta Thunberg", "Female", "Swedish", "⭐ Climate Activist", null, "pocket_v1"),
        VoiceDefinition("celebrity_tate", "Andrew Tate", "Male", "UK", "⭐ Internet Personality", null, "pocket_v1")
    )
    
    // Helper to get all available voices for a model
    fun getVoicesForModel(modelType: String): List<VoiceDefinition> {
        return when (modelType) {
            "pocket_v1" -> POCKET_VOICES + CELEBRITY_VOICES
            "kitten_nano" -> listOf(VoiceDefinition("kitten_v1", "Kitten", "Female", "US", "Standard Kitten Voice", null, "kitten_nano"))
            else -> emptyList()
        }
    }
    
    // Check if a voice ID requires download (celebrity voices)
    fun requiresDownload(voiceId: String): Boolean {
        return CELEBRITY_VOICES.any { it.id == voiceId }
    }
}
