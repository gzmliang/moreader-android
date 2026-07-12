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

    companion object {
        private val SENTENCE_REGEX = Regex("(?<=[.!?。！？；;])\\s*")
        fun hasMultipleSentences(text: String): Boolean {
            return text.split(SENTENCE_REGEX).count { it.isNotBlank() } > 1
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .proxy(java.net.Proxy.NO_PROXY)  // Edge TTS 是局域网服务，不走代理
        .build()

    private var currentCall: Call? = null
    private var audioPlayer: android.media.MediaPlayer? = null

    override val type: TTSProviderType get() = TTSProviderType.EDGE_TTS
    override val isSpeaking: Boolean get() = audioPlayer?.isPlaying ?: false
    override val currentPositionMs: Long get() = audioPlayer?.currentPosition?.toLong() ?: 0L

    private fun makeRatePct(rate: Float): String =
        if (rate >= 1.0f) "+${((rate - 1.0f) * 100).toInt()}%"
        else "-${((1.0f - rate) * 100).toInt()}%"

    private fun makeJsonBody(text: String, rate: Float): RequestBody {
        val json = JSONObject().apply {
            put("text", text)
            put("voice", voice)
            put("rate", makeRatePct(rate))
            put("pitch", pitch)
        }
        return json.toString().toRequestBody("application/json".toMediaType())
    }

    private fun newRequest(path: String, body: RequestBody): Request =
        Request.Builder()
            .url("${endpoint.removeSuffix("/")}/$path")
            .post(body)
            .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
            .build()

    /**
     * Fetch audio bytes for a text segment (can be called for preloading).
     * Returns PreloadResult with audio + boundaries (if multi-sentence).
     */
    suspend fun fetchAudio(text: String, rate: Float = 1.0f): PreloadResult? {
        return try {
            val needsBounds = hasMultipleSentences(text)
            val apiPath = if (needsBounds) "tts_with_boundaries" else "tts"
            val body = makeJsonBody(text, rate)
            val response = client.newCall(newRequest(apiPath, body)).execute()
            if (!response.isSuccessful) return null
            val audio = response.body?.bytes() ?: return null
            val boundaries = if (needsBounds) {
                parseWordBoundaries(response.header("X-Word-Boundaries") ?: "")
            } else emptyList()
            PreloadResult(audio, boundaries)
        } catch (e: Exception) { null }
    }

    /**
     * Fetch audio with word boundaries forced (always /tts_with_boundaries).
     * Used for sub-segments where single-sentence might otherwise skip boundaries.
     */
    suspend fun fetchAudioWithBoundaries(text: String, rate: Float = 1.0f): PreloadResult? {
        return try {
            val body = makeJsonBody(text, rate)
            val response = client.newCall(newRequest("tts_with_boundaries", body)).execute()
            if (!response.isSuccessful) return null
            val audio = response.body?.bytes() ?: return null
            val boundaries = parseWordBoundaries(response.header("X-Word-Boundaries") ?: "")
            PreloadResult(audio, boundaries)
        } catch (e: Exception) { null }
    }

    /**
     * Lightweight fetch: only get word boundaries (no audio).
     * Used for async boundary backfill when preload missed them.
     */
    suspend fun fetchBoundariesOnly(text: String, rate: Float): List<WordBoundary> {
        return try {
            val body = makeJsonBody(text, rate)
            val response = client.newCall(newRequest("tts_boundaries_only", body)).execute()
            if (!response.isSuccessful) return emptyList()
            val json = response.body?.string() ?: "{}"
            val arr = org.json.JSONObject(json).optJSONArray("words") ?: return emptyList()
            parseWordBoundaries(arr.toString())
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Speak with word boundary support.
     * Multi-sentence → /tts_with_boundaries (precise)
     * Single-sentence → /tts (fast, ~1.5s)
     */
    override fun speak(text: String, rate: Float, listener: TTSListener) {
        stop()

        val needsBounds = hasMultipleSentences(text)
        val apiPath = if (needsBounds) "tts_with_boundaries" else "tts"
        val body = makeJsonBody(text, rate)

        currentCall = client.newCall(newRequest(apiPath, body))
        currentCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) listener.onError("Edge TTS connection failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) { listener.onError("Edge TTS HTTP ${response.code}"); return }
                val blob = response.body?.bytes() ?: run { listener.onError("Empty response"); return }

                // Parse word boundaries BEFORE onStart (时序要求!)
                if (needsBounds) {
                    val wb = parseWordBoundaries(response.header("X-Word-Boundaries") ?: "")
                    if (wb.isNotEmpty()) listener.onWordBoundaries(wb)
                }
                listener.onStart()
                playAudio(blob, listener)
            }
        })
    }

    /**
     * Play pre-downloaded audio bytes directly (no HTTP request).
     * Used for preloaded segments from audioCache.
     */
    override fun playRaw(audioData: ByteArray, listener: TTSListener) {
        stop()
        listener.onStart()
        playAudio(audioData, listener)
    }

    private var currentTempFile: java.io.File? = null

    private fun playAudio(audioData: ByteArray, listener: TTSListener) {
        try {
            val tempFile = java.io.File.createTempFile("tts_", ".mp3")
            tempFile.writeBytes(audioData)
            currentTempFile = tempFile
            audioPlayer = android.media.MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    listener.onDone()
                    tempFile.delete()
                    currentTempFile = null
                }
                setOnErrorListener { _, what, extra ->
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

    override fun stop() {
        currentCall?.cancel()
        audioPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        audioPlayer = null
        currentTempFile?.delete()
        currentTempFile = null
    }

    override fun destroy() {
        stop()
        client.dispatcher.executorService.shutdown()
    }
}
