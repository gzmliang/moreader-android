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

    /** Detect if text is primarily Chinese */
    private fun isChinese(text: String): Boolean {
        return text.any { it in '\u4e00'..'\u9fff' }
    }

    private fun buildSystemPrompt(targetLang: String, mode: String): String {
        val lang = if (targetLang.isBlank()) "Chinese" else targetLang
        return when (mode) {
            "dictionary" -> """You are an authoritative multilingual dictionary assistant.
For the input text, provide a comprehensive explanation in $lang:
- **Pronunciation / Phonetics** (if applicable)
- **Definition & Part of Speech** (in $lang)
- **Key Collocations / Idioms** (in $lang)
- **Illustrative Example** (with $lang translation)
Output clean markdown directly without conversational preambles.""".trimIndent()
            "analyze" -> """You are an expert literary and grammatical analyst.
Analyze the input text in depth using $lang:
1. **Sentence Structure & Grammar Rules**
2. **Key Vocabulary & Nuance** (in $lang)
3. **Accurate & Elegant Translation** into $lang
4. **Usage Insights / Common Pitfalls**
Output clean markdown directly without chit-chat.""".trimIndent()
            else -> """You are a world-class literary translator and reading assistant.
Your mission is to accurately, fluently, and naturally translate any input text into $lang.

Guidelines:
1. When input is a single word or phrase:
- **Translation / Definition** in $lang
- **Part of Speech & Pronunciation** (if applicable)
- **1 Example Sentence** with $lang translation
2. When input is a sentence or paragraph:
- **Complete Translation** into $lang (preserving original tone and style)
- **Key Vocabulary & Nuances** briefly explained in $lang

If the input is already primarily in $lang:
- Provide concise definitions, nuances, and contextual usage directly in $lang without translating to other languages.

Output directly without introductory filler.""".trimIndent()
        }
    }

    suspend fun translate(
        config: LLMConfig, text: String, mode: String = "translate",
        onChunk: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val prompt = buildSystemPrompt(config.targetLang, mode)

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", prompt) })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Input: \"$text\"")
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
                return@withContext Result.failure(Exception("Empty response — please check API Key and model configuration"))
            }

            Result.success(fullResult.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            Result.failure(e)
        }
    }
}
