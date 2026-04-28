package com.moyue.app.translate

import android.util.Log
import com.moyue.app.data.models.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class TranslationService {

    companion object { private const val TAG = "Translation" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val systemPrompts = mapOf(
        "translate" to "You are a professional translator. Translate the given text to Chinese. Only return the translation, no explanations.",
        "explain" to """You are an expert language tutor helping a Chinese-speaking student understand English text.
For the given English text, provide:
1. **中文释义** — A clear Chinese translation/paraphrase of the meaning
2. **语境解析** — Explain the meaning in context
3. **重点词汇** — List key words/phrases with Chinese explanations
4. **用法提示** — Usage tips, collocations, or common expressions
Be concise but thorough. Use Chinese.""",
        "analyze" to """You are a grammar analysis expert.
For the given English text, provide:
1. **句子结构分析** — Break down the sentence structure
2. **语法要点** — Explain key grammar rules
3. **核心词汇** — Analyze important vocabulary
4. **双语对照解释** — Both English grammar AND Chinese equivalent
5. **易错提示** — Common mistakes Chinese learners make
Use Chinese for explanations.""",
    )

    suspend fun translate(
        config: LLMConfig, text: String, mode: String = "translate",
        onChunk: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val prompt = systemPrompts[mode] ?: systemPrompts["translate"]!!

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", prompt) })
            put(JSONObject().apply {
                put("role", "user")
                put("content", if (mode == "translate") "Translate to Chinese: $text" else "Text: \"$text\"")
            })
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 2000)
            put("stream", true)
        }

        Log.d(TAG, "Request: ${config.endpoint.trimEnd('/')}/chat/completions model=${config.model}")

        val request = Request.Builder()
            .url("${config.endpoint.trimEnd('/')}/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "HTTP ${response.code}: $errBody")
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${errBody.take(200)}"))
            }

            val bodyStream = response.body?.byteStream()
                ?: return@withContext Result.failure(Exception("No response body"))
            val reader = BufferedReader(InputStreamReader(bodyStream, "UTF-8"))
            val fullResult = StringBuilder()
            var gotContent = false

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ")
                if (data == "[DONE]") continue

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.optJSONObject(0)
                        val delta = choice?.optJSONObject("delta")
                        val content = delta?.optString("content", "")
                        if (!content.isNullOrEmpty() && content != "null") {
                            fullResult.append(content)
                            onChunk(content)
                            gotContent = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE parse error: ${e.message}")
                }
            }

            Log.d(TAG, "SSE done, gotContent=$gotContent, length=${fullResult.length}")

            if (!gotContent) {
                // Fallback: try non-streaming response
                // (The response may have come as a single JSON instead of SSE)
                return@withContext Result.failure(Exception("Empty response — 请检查 API Key 和模型名称是否正确"))
            }

            Result.success(fullResult.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            Result.failure(e)
        }
    }
}
