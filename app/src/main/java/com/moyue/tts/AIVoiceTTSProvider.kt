package com.moyue.app.tts

import com.moyue.app.data.models.TTSProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AIVoiceTTSProvider(
    private val endpoint: String = "https://api.siliconflow.cn/v1",
    private val apiKey: String = "",
    private val model: String = "fnlp/MOSS-TTSD-v0.5",
    private val voice: String = "fnlp/MOSS-TTSD-v0.5:anna",
) : TTSProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null
    private var audioPlayer: android.media.MediaPlayer? = null

    override val type: TTSProviderType get() = TTSProviderType.AI_VOICE
    override val isSpeaking: Boolean get() = audioPlayer?.isPlaying ?: false

    /**
     * Fetch audio bytes for preloading
     */
    suspend fun fetchAudio(text: String, rate: Float = 1.0f): ByteArray? {
        return try {
            val json = JSONObject().apply {
                put("model", model)
                put("input", text)
                put("voice", voice)
                put("response_format", "mp3")
            }
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

    override fun speak(text: String, rate: Float, listener: TTSListener) {
        stop()

        val json = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
        }

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
                if (!call.isCanceled()) listener.onError("AI Voice request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    listener.onError("AI Voice HTTP ${response.code}: ${errorBody.take(200)}")
                    return
                }
                val blob = response.body?.bytes() ?: run { listener.onError("AI Voice returned empty audio"); return }
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
                val tempFile = java.io.File.createTempFile("ai_tts_", ".mp3")
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
