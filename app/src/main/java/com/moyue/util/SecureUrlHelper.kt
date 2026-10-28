package com.moyue.util

object SecureUrlHelper {
    const val OFFICIAL_EDGE_TTS = "https://liang-studio.duckdns.org/edge-tts"
    private const val LEGACY_EDGE_TTS_1 = "http://p-plus.duckdns.org:5001"
    private const val LEGACY_EDGE_TTS_2 = "http://powerplus.blogsyte.com:5001"

    fun secureEdgeEndpoint(input: String?): String {
        val trimmed = (input ?: "").trim().removeSuffix("/")
        if (trimmed.isEmpty() || trimmed == LEGACY_EDGE_TTS_1 || trimmed == LEGACY_EDGE_TTS_2) {
            return OFFICIAL_EDGE_TTS
        }
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            val hostPart = trimmed.removePrefix("http://").substringBefore('/').substringBefore(':')
            val isPrivate = hostPart == "localhost" || hostPart == "127.0.0.1" ||
                    hostPart.startsWith("192.168.") || hostPart.startsWith("10.") ||
                    hostPart.startsWith("172.")
            if (!isPrivate) {
                return "https://" + trimmed.removePrefix("http://")
            }
        }
        return trimmed
    }
}
