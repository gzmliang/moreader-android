package com.moyue.ai.model

data class AiConfig(
    val provider: String = "DeepSeek",
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val sourceLang: String = "Auto",
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
