package com.moyue.app.data.models

data class LLMConfig(
    val provider: String = "custom",
    val apiKey: String = "",
    val endpoint: String = "",
    val model: String = ""
)
