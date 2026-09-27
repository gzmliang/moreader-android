package com.moyue.app.tts

import com.moyue.app.data.models.TTSProviderType
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible TTS provider.
 * Works with any server that implements OpenAI's /audio/speech API:
 * - MOSS-TTS-Nano, CosyVoice, tts-1, etc.
 *
 * API: POST /audio/speech
 * Body: { "model": "moss-tts-nano", "input": "text", "voice": "Lingyu", "response_format": "mp3" }
 * Header: Authorization: Bearer <api_key>
 */
class CustomTTSProvider(
    private val endpoint: String = "",
    private val apiKey: String = "dummy",
    private val model: String = "moss-tts-nano",
    private val voice: String = "Lingyu",
) : TTSProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ★ v1.0.1：在途请求集合 + 播放令牌（generation）—— 迟到的音频一律丢弃，避免两份声音
    private val pendingCalls = mutableSetOf<Call>()
    private var generation = 0L
    private var audioPlayer: android.media.MediaPlayer? = null

    override val type: TTSProviderType get() = TTSProviderType.CUSTOM_TTS
    override val isSpeaking: Boolean get() = audioPlayer?.isPlaying ?: false
    override val currentPositionMs: Long get() = audioPlayer?.currentPosition?.toLong() ?: 0L

    /**
     * Fetch audio bytes for preloading
     */
    suspend fun fetchAudio(text: String, rate: Float = 1.0f): ByteArray? {
        return try {
            val json = buildRequestBody(text)
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${endpoint.removeSuffix("/")}/audio/speech")
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

    private fun buildRequestBody(text: String): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
        }
    }

    override fun speak(text: String, rate: Float, listener: TTSListener) {
        stop()
        val myGen = ++generation   // ★ 本次播放的令牌

        val json = buildRequestBody(text)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${endpoint.removeSuffix("/")}/audio/speech")
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val call = client.newCall(request)
        pendingCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pendingCalls.remove(call)
                if (myGen != generation) return   // ★ 已作废 → 不回调、不播放
                if (!call.isCanceled()) listener.onError("Custom TTS request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                pendingCalls.remove(call)
                if (myGen != generation) {        // ★ 迟到的响应：直接丢弃，绝不开口
                    try { response.close() } catch (e: Exception) {}
                    return
                }
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    listener.onError("Custom TTS HTTP ${response.code}: ${errorBody.take(200)}")
                    return
                }
                val blob = response.body?.bytes() ?: run { listener.onError("Custom TTS returned empty audio"); return }
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
            val tempFile = java.io.File.createTempFile("custom_tts_", ".mp3")
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
