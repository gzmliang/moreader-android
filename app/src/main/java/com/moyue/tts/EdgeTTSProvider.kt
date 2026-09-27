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
    val voice: String = "zh-CN-XiaoxiaoNeural",
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
        .build()

    // ★ v1.0.1：在途请求集合 + 播放令牌（generation）。
    //   病根：以前只存「最后一个」请求，暂停时只能取消最后一个，前面泄漏的请求回来照样开口 → 两个声音。
    //   现在：每次播放领一个自增令牌，响应回来先对号；号不对就当场丢弃，绝不播放。
    private val pendingCalls = mutableSetOf<Call>()
    private var generation = 0L
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

    private fun getCandidateEndpoints(): List<String> {
        val clean = endpoint.removeSuffix("/")
        val defaultServer = "http://p-plus.duckdns.org:5001"
        val fallbackServer = "http://powerplus.blogsyte.com:5001"
        return if (clean == defaultServer || clean == fallbackServer) {
            listOf(clean, if (clean == defaultServer) fallbackServer else defaultServer)
        } else {
            listOf(clean)
        }
    }

    private fun executeWithFallback(path: String, body: RequestBody): Response? {
        for (ep in getCandidateEndpoints()) {
            try {
                val req = Request.Builder()
                    .url("$ep/$path")
                    .post(body)
                    .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) return resp
            } catch (e: Exception) {
                // try next candidate
            }
        }
        return null
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
            val response = executeWithFallback(apiPath, body) ?: return null
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
            val response = executeWithFallback("tts_with_boundaries", body) ?: return null
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
            val response = executeWithFallback("tts_boundaries_only", body) ?: return emptyList()
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

        val candidates = getCandidateEndpoints()
        val myGen = ++generation
        fun tryCall(idx: Int) {
            if (myGen != generation) return   // ★ 本次播放已被取消/取代 → 停止重试
            if (idx >= candidates.size) {
                listener.onError("Edge TTS all endpoints failed")
                return
            }
            val ep = candidates[idx]
            val req = Request.Builder()
                .url("$ep/$apiPath")
                .post(body)
                .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
                .build()
            val call = client.newCall(req)
            pendingCalls.add(call)
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    pendingCalls.remove(c)
                    if (myGen != generation) return   // ★ 已作废 → 不回调、不播放
                    if (!c.isCanceled()) {
                        if (idx + 1 < candidates.size) {
                            tryCall(idx + 1)
                        } else {
                            listener.onError("Edge TTS connection failed: ${e.message}")
                        }
                    }
                }
                override fun onResponse(c: Call, response: Response) {
                    pendingCalls.remove(c)
                    if (myGen != generation) {      // ★ 迟到的响应：直接丢弃，绝不开口
                        try { response.close() } catch (e: Exception) {}
                        return
                    }
                    if (!response.isSuccessful) {
                        if (idx + 1 < candidates.size) {
                            tryCall(idx + 1)
                        } else {
                            listener.onError("Edge TTS HTTP ${response.code}")
                        }
                        return
                    }
                    val blob = response.body?.bytes() ?: run {
                        if (idx + 1 < candidates.size) {
                            tryCall(idx + 1)
                        } else {
                            listener.onError("Empty response")
                        }
                        return
                    }

                    // Parse word boundaries BEFORE onStart (时序要求!)
                    if (needsBounds) {
                        val wb = parseWordBoundaries(response.header("X-Word-Boundaries") ?: "")
                        if (wb.isNotEmpty()) listener.onWordBoundaries(wb)
                    }
                    listener.onStart()
                    playAudio(blob, listener, myGen)
                }
            })
        }
        tryCall(0)
    }

    /**
     * Play pre-downloaded audio bytes directly (no HTTP request).
     * Used for preloaded segments from audioCache.
     */
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
            val tempFile = java.io.File.createTempFile("tts_", ".mp3")
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
        generation++                       // ★ 让所有在途请求作废（它们的响应回来会被丢弃）
        pendingCalls.forEach { try { it.cancel() } catch (e: Exception) {} }
        pendingCalls.clear()
        stopPlaybackOnly()
    }

    override fun destroy() {
        stop()
        client.dispatcher.executorService.shutdown()
    }
}
