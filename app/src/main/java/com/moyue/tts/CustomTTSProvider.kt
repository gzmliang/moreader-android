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
    private val endpoint: String = "http://192.168.199.101:18083",
    private val apiKey: String = "dummy",
    private val model: String = "moss-tts-nano",
    private val voice: String = "Lingyu",
) : TTSProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null
    private var audioPlayer: android.media.MediaPlayer? = null

    override val type: TTSProviderType get() = TTSProviderType.CUSTOM_TTS
    override val isSpeaking: Boolean get() = audioPlayer?.isPlaying ?: false

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

        val json = buildRequestBody(text)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${endpoint.removeSuffix("/")}/audio/speech")
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        currentCall = client.newCall(request)
        currentCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) listener.onError("Custom TTS request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    listener.onError("Custom TTS HTTP ${response.code}: ${errorBody.take(200)}")
                    return
                }
                val blob = response.body?.bytes() ?: run { listener.onError("Custom TTS returned empty audio"); return }
                listener.onStart()
                playAudio(blob, listener)
            }
        })
    }

    fun playRaw(audioData: ByteArray, listener: TTSListener) {
        stop()
        listener.onStart()
        playAudio(audioData, listener)
    }

    private fun playAudio(audioData: ByteArray, listener: TTSListener) {
        try {
            audioPlayer = android.media.MediaPlayer().apply {
                val tempFile = java.io.File.createTempFile("custom_tts_", ".mp3")
                tempFile.writeBytes(audioData)
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener { listener.onDone() }
                setOnErrorListener { _, what, extra ->
                    listener.onError("Playback error: $what/$extra")
                    true
                }
                prepareAsync()
                setOnPreparedListener { start() }
            }
        } catch (e: Exception) {
            listener.onError("Playback error: ${e.message}")
        }
    }

    override fun stop() {
        currentCall?.cancel()
        audioPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        audioPlayer = null
    }

    override fun destroy() {
        stop()
        client.dispatcher.executorService.shutdown()
    }
}
