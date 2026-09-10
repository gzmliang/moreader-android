package com.moyue.ai.model

data class AiConfig(
    val provider: String = "OpenAI",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val sourceLang: String = "English",
    val targetLang: String = "Chinese"
) {
    fun cleanBaseUrl(): String {
        var url = baseUrl.trim()
        if (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
        if (url.endsWith("/chat/completions")) {
            url = url.removeSuffix("/chat/completions")
        }
        return url
    }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()
}
