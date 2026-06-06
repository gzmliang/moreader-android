package com.moyue.app.tts

import com.moyue.app.data.models.TTSProviderType
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class EdgeTTSProvider(
    private val endpoint: String,
    private val voice: String = "zh-CN-XiaoxiaoNeural",
    private val rate: String = "+0%",
    private val pitch: String = "+0Hz",
    private val apiKey: String = "",
) : TTSProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null
    private var audioPlayer: android.media.MediaPlayer? = null

    override val type: TTSProviderType get() = TTSProviderType.EDGE_TTS
    override val isSpeaking: Boolean get() = audioPlayer?.isPlaying ?: false

    /**
     * Fetch audio bytes for a text segment (preloading — fast /tts endpoint).
     * After calling, use getLastBoundaries() which will be empty (populated
     * separately via fetchBoundariesOnly during post-preload pass).
     */
    suspend fun fetchAudio(text: String, rate: Float = 1.0f): ByteArray? {
        return try {
            val ratePercent = if (rate >= 1.0f) "+${((rate - 1.0f) * 100).toInt()}%" else "-${((1.0f - rate) * 100).toInt()}%"
            val json = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("rate", ratePercent)
                put("pitch", pitch)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${endpoint.removeSuffix("/")}/tts")
                .post(body)
                .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) null else response.body?.bytes()
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch word boundaries only (lightweight JSON, no audio).
     *  Used for post-preload pass to populate boundariesCache without re-downloading audio. */
    suspend fun fetchBoundariesOnly(text: String, rate: Float = 1.0f): List<WordBoundary> {
        return try {
            val ratePercent = if (rate >= 1.0f) "+${((rate - 1.0f) * 100).toInt()}%" else "-${((1.0f - rate) * 100).toInt()}%"
            val json = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("rate", ratePercent)
                put("pitch", pitch)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${endpoint.removeSuffix("/")}/tts_boundaries_only")
                .post(body)
                .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) emptyList()
            else {
                val jsonObj = JSONObject(response.body?.string() ?: "{}")
                val arr = jsonObj.optJSONArray("words") ?: return emptyList()
                parseWordBoundaries(arr.toString())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Download audio + word boundaries and play via MediaPlayer.
     * rate: 0.5-2.0, mapped to Edge TTS rate format: -50% to +100%
     */
    override fun speak(text: String, rate: Float, listener: TTSListener) {
        stop()

        // Convert float rate (0.5~2.0) to Edge TTS percentage format
        val ratePercent = if (rate >= 1.0f) {
            "+${((rate - 1.0f) * 100).toInt()}%"
        } else {
            "-${((1.0f - rate) * 100).toInt()}%"
        }

        val json = JSONObject().apply {
            put("text", text)
            put("voice", voice)
            put("rate", ratePercent)
            put("pitch", pitch)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${endpoint.removeSuffix("/")}/tts_with_boundaries")
            .post(body)
            .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
            .build()

        currentCall = client.newCall(request)
        currentCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) listener.onError("Edge TTS connection failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) { listener.onError("Edge TTS HTTP ${response.code}"); return }
                val blob = response.body?.bytes() ?: run { listener.onError("Empty response"); return }

                // Parse word boundaries from response header
                val wbHeader = response.header("X-Word-Boundaries") ?: ""
                val boundaries = parseWordBoundaries(wbHeader)
                if (boundaries.isNotEmpty()) {
                    listener.onWordBoundaries(boundaries)
                }

                listener.onStart()
                playAudio(blob, listener)
            }
        })
    }

    /**
     * Play pre-downloaded audio bytes directly (no HTTP request). Used for preloaded segments.
     */
    fun playRaw(audioData: ByteArray, listener: TTSListener) {
        stop()
        listener.onStart()
        playAudio(audioData, listener)
    }

    private fun playAudio(audioData: ByteArray, listener: TTSListener) {
        try {
            audioPlayer = android.media.MediaPlayer().apply {
                val tempFile = java.io.File.createTempFile("tts_", ".mp3")
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
