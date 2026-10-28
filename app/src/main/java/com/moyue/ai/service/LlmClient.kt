package com.moyue.ai.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.moyue.ai.model.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LlmClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun chatCompletion(
        config: AiConfig,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.5,
        responseJson: Boolean = false,
        imageBitmaps: List<android.graphics.Bitmap> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${config.cleanBaseUrl()}/chat/completions"
            val root = JsonObject().apply {
                addProperty("model", config.model.ifBlank { "gpt-4o-mini" })
                addProperty("temperature", temperature)
                if (responseJson) {
                    val formatObj = JsonObject()
                    formatObj.addProperty("type", "json_object")
                    add("response_format", formatObj)
                }

                val messages = JsonArray().apply {
                    if (systemPrompt.isNotBlank()) {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", systemPrompt)
                        })
                    }
                    val userMsg = JsonObject()
                    userMsg.addProperty("role", "user")

                    if (imageBitmaps.isEmpty()) {
                        userMsg.addProperty("content", userPrompt)
                    } else {
                        // 带有图片的多模态视觉消息 (OpenAI Vision standard)
                        val contentArray = JsonArray()
                        val textPart = JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", userPrompt)
                        }
                        contentArray.add(textPart)

                        for (bmp in imageBitmaps) {
                            if (bmp.isRecycled) continue
                            val baos = java.io.ByteArrayOutputStream()
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                            val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                            val imgPart = JsonObject().apply {
                                addProperty("type", "image_url")
                                val urlObj = JsonObject().apply {
                                    addProperty("url", "data:image/jpeg;base64,$b64")
                                }
                                add("image_url", urlObj)
                            }
                            contentArray.add(imgPart)
                        }
                        userMsg.add("content", contentArray)
                    }
                    add(userMsg)
                }
                add("messages", messages)
            }

            val requestBody = gson.toJson(root).toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)

            if (config.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val bodyString = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: $bodyString")
                )
            }

            val jsonResponse = gson.fromJson(bodyString, JsonObject::class.java)
            val choices = jsonResponse.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return@withContext Result.failure(Exception("Empty choices in response"))
            }

            val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
            val content = messageObj.get("content")?.asString ?: ""
            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testConnection(config: AiConfig): Result<String> = withContext(Dispatchers.IO) {
        chatCompletion(
            config = config,
            systemPrompt = "You are a helpful assistant.",
            userPrompt = "Hello! Please reply 'OK' if you receive this message.",
            temperature = 0.1
        )
    }
}
