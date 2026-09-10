package com.moyue.ai.model

data class CharacterCard(
    val nameOriginal: String,
    val nameTranslation: String,
    val faction: String,
    val role: String,
    val relationships: List<String> = emptyList()
)

data class PlotStage(
    val stage: String, // e.g. "Opening / 起因"
    val eventOriginal: String,
    val eventTranslation: String
)

data class AiPlotResult(
    val bookId: String,
    val chapterIndex: Int,
    val scope: String,
    val coreDynamicsOriginal: String = "",
    val coreDynamicsTranslation: String = "",
    val characters: List<CharacterCard> = emptyList(),
    val timeline: List<PlotStage> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)
