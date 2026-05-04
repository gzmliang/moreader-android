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

    private val systemPrompts = mapOf(
        // ===== English input =====
        "en_translate" to """You are a bilingual dictionary assistant.

When input is a single English word:
**音标**：[IPA]
**释义**：中文解释
**词性**：noun/verb/adjective/etc.
**例句**：1 sentence with Chinese translation

When input is a sentence or phrase:
**重点词汇**：key words with Chinese meanings
**整句翻译**：Chinese translation

Output directly, no extra explanation.""",
        // ===== Chinese input =====
        "cn_translate" to """你是专业双语词典助手。

当输入是中文词语时：
**拼音**：[pī yīn]
**释义**：中文解释 + English definition
**词性**：名词/动词/形容词等
**组词**：2个常见搭配
**例句**：1句中文 + English translation

当输入是中文句子时：
**整句翻译**：English translation
**重点词汇**：关键词的中英双语解释

直接输出内容，不要多余解释。""",
        // ===== English explain =====
        "en_explain" to """You are an expert language tutor helping a Chinese-speaking student understand English text.
For the given English text, provide:
1. **中文释义** — A clear Chinese translation/paraphrase of the meaning
2. **语境解析** — Explain the meaning in context
3. **重点词汇** — List key words/phrases with Chinese explanations
4. **用法提示** — Usage tips, collocations, or common expressions
Be concise but thorough. Use Chinese.""",
        // ===== Chinese explain =====
        "cn_explain" to """你是专业的中文词语解析助手。
对于给定的中文文本，请提供：
1. **词语释义** — 该词/句在《现代汉语词典》中的含义
2. **English Definition** — 用简明英语解释
3. **语境解析** — 在上下文中的含义
4. **近义词/反义词** — 相关词语对比
5. **用法提示** — 常见搭配、使用场景
用中文为主、英文为辅进行解释。""",
        // ===== English analyze =====
        "en_analyze" to """You are a grammar analysis expert.
For the given English text, provide:
1. **句子结构分析** — Break down the sentence structure
2. **语法要点** — Explain key grammar rules
3. **核心词汇** — Analyze important vocabulary
4. **双语对照解释** — Both English grammar AND Chinese equivalent
5. **易错提示** — Common mistakes Chinese learners make
Use Chinese for explanations.""",
        // ===== Chinese analyze =====
        "cn_analyze" to """你是中文语法和修辞分析专家。
对于给定的中文文本，请提供：
1. **句子结构分析** — 主谓宾定状补
2. **修辞手法** — 比喻、拟人、排比等
3. **重点词汇** — 关键词语的含义和用法
4. **English Translation** — 整句英文翻译
5. **易错提示** — 常见的中文语法错误
用中文解释，关键术语附英文对照。""",
    )

    suspend fun translate(
        config: LLMConfig, text: String, mode: String = "translate",
        onChunk: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val chinese = isChinese(text)
        val promptKey = "${if (chinese) "cn" else "en"}_$mode"
        val prompt = systemPrompts[promptKey] ?: systemPrompts["en_translate"]!!

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", prompt) })
            put(JSONObject().apply {
                put("role", "user")
                put("content", if (chinese) "请解释以下内容：\"$text\"" else "Please explain: \"$text\"")
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
