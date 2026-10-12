package com.leedor.tts.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型 / 语音下载的**主机选择与回退**。
 *
 * 背景：`huggingface.co` 在部分网络环境下**完全不可达**（连接超时，不是 404），
 * 而应用里所有 Piper 语音、Pocket-TTS 模型、名人语音的 URL 都写死在
 * `huggingface.co` 上，导致「点下载没反应 / 一直失败」。
 *
 * 这里统一处理：
 *  1. 把 `huggingface.co` 的 URL 展开成多个候选（镜像优先）
 *  2. 逐个尝试，第一个成功即用
 *  3. 缓存探测结果，避免每次下载都重新探测
 *
 * 非 HuggingFace 的 URL（例如 GitHub Releases）原样透传，不受影响。
 */
object DownloadHosts {

    private const val TAG = "DownloadHosts"

    const val OFFICIAL = "https://huggingface.co"
    const val MIRROR = "https://hf-mirror.com"

    /** 偏好里的取值：自动 / 只用官方 / 只用镜像 */
    const val MODE_AUTO = "auto"
    const val MODE_OFFICIAL = "official"
    const val MODE_MIRROR = "mirror"

    /** 探测结果缓存（毫秒）。 */
    private const val PROBE_TTL_MS = 5 * 60 * 1000L

    @Volatile private var cachedHost: String? = null
    @Volatile private var cachedAt: Long = 0L

    /**
     * 候选主机顺序。
     *
     * 默认**镜像优先**：官方源一旦不可达会卡满连接超时，把它放前面体验很差。
     * 镜像可达时直接命中；镜像也挂了才回落到官方。
     */
    fun candidateHosts(mode: String): List<String> = when (mode) {
        MODE_OFFICIAL -> listOf(OFFICIAL)
        MODE_MIRROR -> listOf(MIRROR)
        else -> listOf(MIRROR, OFFICIAL)
    }

    /** 该 URL 是否指向 HuggingFace（需要做镜像替换）。 */
    private fun isHuggingFace(url: String): Boolean =
        url.startsWith(OFFICIAL) || url.startsWith(MIRROR)

    /**
     * 把一个 URL 展开成按优先级排列的候选列表。
     *
     * `https://huggingface.co/a/b` 在 auto 模式下会变成
     * `[https://hf-mirror.com/a/b, https://huggingface.co/a/b]`。
     * 非 HF 域名（GitHub 等）原样返回单元素列表。
     */
    fun expand(url: String, mode: String = MODE_AUTO): List<String> {
        if (!isHuggingFace(url)) return listOf(url)

        // 取出 huggingface.co / hf-mirror.com 之后的路径部分
        val path = url.removePrefix(OFFICIAL).removePrefix(MIRROR)
        return candidateHosts(mode).map { host -> host + path }
    }

    /**
     * 探测哪个主机可用，返回可用的主机前缀；都不可用返回 null。
     * 结果会缓存 [PROBE_TTL_MS]。
     */
    suspend fun resolveHost(context: Context, forceRefresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val mode = PrefsManager(context).downloadHostMode
            if (mode != MODE_AUTO) return@withContext candidateHosts(mode).first()

            val now = System.currentTimeMillis()
            val cached = cachedHost
            if (!forceRefresh && cached != null && now - cachedAt < PROBE_TTL_MS) {
                return@withContext cached
            }

            for (host in candidateHosts(mode)) {
                if (probe(host)) {
                    cachedHost = host
                    cachedAt = now
                    Log.i(TAG, "使用下载主机: $host")
                    return@withContext host
                }
                Log.w(TAG, "主机不可达: $host")
            }

            cachedHost = null
            cachedAt = 0L
            Log.e(TAG, "所有下载主机都不可达")
            null
        }

    /** 清掉探测缓存（用户在设置里改了模式时调用）。 */
    fun invalidate() {
        cachedHost = null
        cachedAt = 0L
    }

    /**
     * 解析出实际可用的 URL：先探测出可用主机，再替换域名。
     *
     * 用于 [android.app.DownloadManager] 这类**没法中途重试**的下载方式 ——
     * 必须先把 URL 定下来再入队。
     * 非 HF 域名原样返回；探测不到可用主机时也原样返回（交给系统去失败并报错）。
     */
    suspend fun pickUrl(context: Context, url: String): String {
        if (!isHuggingFace(url)) return url
        val host = resolveHost(context) ?: return url
        val path = url.removePrefix(OFFICIAL).removePrefix(MIRROR)
        return host + path
    }

    /** 探测单个主机是否可达。超时给短一点，避免卡住。 */
    private fun probe(host: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(host).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Leedor-TTS/1.0")
            }
            val code = conn.responseCode
            code in 200..399
        } catch (t: Throwable) {
            Log.w(TAG, "探测 $host 失败: ${t.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
