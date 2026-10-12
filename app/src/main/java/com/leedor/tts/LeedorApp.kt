package com.leedor.tts

import android.app.Application
import android.util.Log
import com.leedor.tts.data.ModelRepository

/**
 * 侍读郎的 Application。
 *
 * 目前只做一件事：把 Application context 交给 [ModelRepository]。
 * 模型定义来自 `assets/models.json`，是**进程级**的只读数据，
 * 需要一个 context 才能读取；而 `PrefsManager.isHidden()` 这类调用点拿不到 context，
 * 所以必须在进程启动时就把 context 存好。
 *
 * 之所以不在 Activity 里初始化：本应用的主要入口其实是 TTS 服务
 * （系统可能在用户从未打开过 App 的情况下直接绑定它），
 * Application.onCreate 是唯一能覆盖所有入口的位置。
 */
class LeedorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModelRepository.init(this)
        Log.i("LeedorApp", "ModelRepository 已初始化，模型索引来自 assets/models.json")
        // 收拾旧版本留下的文件：带 ConvInteger 的 int8 模型，以及错位的语音目录
        ModelRepository.migrateLegacyStorage(this)
    }
}
