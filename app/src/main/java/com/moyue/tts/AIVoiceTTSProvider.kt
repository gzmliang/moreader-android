package com.moyue.app.tts

import com.moyue.app.data.models.TTSProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AIVoiceTTSProvider(
    endpoint: String = DEFAULT_ENDPOINT,
    private val apiKey: String = "",
    private val model: String = "fnlp/MOSS-TTSD-v0.5",
    private val voice: String = "fnlp/MOSS-TTSD-v0.5:anna",
) : TTSProvider {

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.siliconflow.cn/v1"

        /**
         * 规范化 Base URL：空/无 scheme/非法地址一律返回 null，由调用方转成友好错误。
         * ★ 历史坑：此处若直接拿空串拼 "/audio/speech"，Request.Builder 会抛
         *   IllegalArgumentException: Expected URL scheme... 直接崩掉整个 App。
         */
        fun normalizeBaseUrl(raw: String): String? {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return null
            // 只有路径（如 "/audio/speech"）不是可用服务地址
            if (t.startsWith("/")) return null
            val withScheme = when {
                t.startsWith("http://", true) || t.startsWith("https://", true) -> t
                t.startsWith("//") -> "https:$t"
                else -> "https://$t"
            }
            return try {
                val u = withScheme.toHttpUrlOrNull()
                val validHost = u != null && (u.host.contains(".") ||
                        u.host.equals("localhost", true) || u.host.contains(":"))
                if (u == null || u.host.isBlank() || !validHost) null else withScheme
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 已规范化的服务根地址；null 表示用户没填或填错 */
    private val baseUrl: String? = normalizeBaseUrl(endpoint)
    private val missingUrlMsg = "AI Voice Base URL is not set. Please fill it in TTS settings."

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ★ v1.0.1：在途请求集合 + 播放令牌（generation）—— 迟到的音频一律丢弃，避免两份声音
    private val pendingCalls = mutableSetOf<Call>()
    private var generation = 0L
    private var audioPlayer: android.media.MediaPlayer? = null

    override val type: TTSProviderType get() = TTSProviderType.AI_VOICE
    override val isSpeaking: Boolean get() = audioPlayer?.isPlaying ?: false
    override val currentPositionMs: Long get() = audioPlayer?.currentPosition?.toLong() ?: 0L

    /**
     * Fetch audio bytes for preloading
     */
    suspend fun fetchAudio(text: String, rate: Float = 1.0f): ByteArray? {
        val base = baseUrl ?: return null
        return try {
            val json = JSONObject().apply {
                put("model", model)
                put("input", text)
                put("voice", voice)
                put("response_format", "mp3")
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/audio/speech")
                .post(body)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) null else response.body?.bytes()
        } catch (e: Exception) {
            null
        }
    }

    override fun speak(text: String, rate: Float, listener: TTSListener) {
        stop()
        val myGen = ++generation   // ★ 本次播放的令牌
        val base = baseUrl ?: run {
            listener.onError(missingUrlMsg)
            return
        }

        val json = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = try {
            Request.Builder()
                .url("$base/audio/speech")
                .post(body)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
        } catch (e: Exception) {
            listener.onError("AI Voice bad Base URL: ${e.message}")
            return
        }

        val call = try { client.newCall(request) } catch (e: Exception) {
            listener.onError("AI Voice request failed: ${e.message}")
            return
        }
        pendingCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pendingCalls.remove(call)
                if (myGen != generation) return   // ★ 已作废 → 不回调、不播放
                if (!call.isCanceled()) listener.onError("AI Voice request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                pendingCalls.remove(call)
                if (myGen != generation) {        // ★ 迟到的响应：直接丢弃，绝不开口
                    try { response.close() } catch (e: Exception) {}
                    return
                }
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    listener.onError("AI Voice HTTP ${response.code}: ${errorBody.take(200)}")
                    return
                }
                val blob = response.body?.bytes() ?: run { listener.onError("AI Voice returned empty audio"); return }
                listener.onStart()
                playAudio(blob, listener, myGen)
            }
        })
    }

    override fun playRaw(audioData: ByteArray, listener: TTSListener) {
        val myGen = ++generation
        stopPlaybackOnly()
        if (myGen != generation) return
        listener.onStart()
        playAudio(audioData, listener, myGen)
    }

    private var currentTempFile: java.io.File? = null

    private fun playAudio(audioData: ByteArray, listener: TTSListener, myGen: Long) {
        if (myGen != generation) return   // ★ 播放前最后一刻再对一次号
        try {
            val tempFile = java.io.File.createTempFile("ai_tts_", ".mp3")
            tempFile.writeBytes(audioData)
            currentTempFile = tempFile
            audioPlayer = android.media.MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    if (myGen != generation) return@setOnCompletionListener
                    listener.onDone()
                    tempFile.delete()
                    currentTempFile = null
                }
                setOnErrorListener { _, what, extra ->
                    if (myGen != generation) return@setOnErrorListener true
                    listener.onError("Playback error: $what/$extra")
                    tempFile.delete()
                    currentTempFile = null
                    true
                }
                prepareAsync()
                setOnPreparedListener { start() }
            }
        } catch (e: Exception) {
            listener.onError("Playback error: ${e.message}")
        }
    }

    override fun pause(): Boolean {
        val player = audioPlayer ?: return false
        return try {
            if (player.isPlaying) {
                player.pause()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun resume(): Boolean {
        val player = audioPlayer ?: return false
        return try {
            player.start()
            player.isPlaying
        } catch (e: Exception) {
            false
        }
    }

    /** 只停播放器 + 清临时文件（不动令牌，供 playRaw 复用） */
    private fun stopPlaybackOnly() {
        audioPlayer?.apply {
            try { if (isPlaying) stop() } catch (e: Exception) {}
            try { release() } catch (e: Exception) {}
        }
        audioPlayer = null
        currentTempFile?.delete()
        currentTempFile = null
    }

    override fun stop() {
        generation++                       // ★ 让所有在途请求作废
        pendingCalls.forEach { try { it.cancel() } catch (e: Exception) {} }
        pendingCalls.clear()
        stopPlaybackOnly()
    }

    override fun destroy() {
        stop()
        client.dispatcher.executorService.shutdown()
    }
}
