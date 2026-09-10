package com.moyue.ai.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiPlotResult
import com.moyue.ai.model.AiQuizResult
import com.moyue.ai.model.AiSummaryResult
import com.moyue.ai.model.CharacterCard
import com.moyue.ai.model.PlotStage
import com.moyue.ai.model.QuizQuestion
import com.moyue.ai.model.SummaryParagraph

object AiPromptBuilder {

    private val gson = Gson()

    fun buildSummaryPrompt(
        config: AiConfig,
        title: String,
        text: String,
        ratio: Int,
        scope: String
    ): Pair<String, String> {
        val wordCount = text.split("\\s+".toRegex()).size
        val targetLength = ((wordCount * ratio) / 100).coerceAtLeast(150)

        val systemPrompt = """
            You are a world-class literary editor and speed-reading condensation specialist.
            Your task is to condense the provided text to approximately $ratio% of its original depth (target: around $targetLength words).
            The source language is ${config.sourceLang} and the target explanation language is ${config.targetLang}.
            
            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "title": "Condensed Title",
              "paragraphs": [
                {
                  "original": "Sentence or paragraph in ${config.sourceLang}...",
                  "translation": "Corresponding sentence or paragraph in ${config.targetLang}..."
                }
              ]
            }
            Keep the tone engaging, precise, and retain all critical narrative milestones, arguments, and core themes.
        """.trimIndent()

        val userPrompt = """
            Title: $title
            Scope: $scope
            Original Text:
            $text
        """.trimIndent()

        return Pair(systemPrompt, userPrompt)
    }

    fun parseSummaryResponse(
        jsonString: String,
        bookId: String,
        chapterIndex: Int,
        scope: String,
        ratio: Int
    ): AiSummaryResult {
        return try {
            val cleanJson = extractJson(jsonString)
            val obj = gson.fromJson(cleanJson, JsonObject::class.java)
            val title = obj.get("title")?.asString ?: ""
            val paragraphsArray = obj.getAsJsonArray("paragraphs")
            val list = mutableListOf<SummaryParagraph>()
            if (paragraphsArray != null) {
                for (item in paragraphsArray) {
                    val pObj = item.asJsonObject
                    val orig = pObj.get("original")?.asString ?: ""
                    val trans = pObj.get("translation")?.asString ?: ""
                    if (orig.isNotBlank() || trans.isNotBlank()) {
                        list.add(SummaryParagraph(original = orig, translation = trans))
                    }
                }
            }
            AiSummaryResult(
                bookId = bookId,
                chapterIndex = chapterIndex,
                scope = scope,
                ratio = ratio,
                title = title,
                paragraphs = list,
                rawMarkdown = jsonString
            )
        } catch (e: Exception) {
            // Fallback parsing
            AiSummaryResult(
                bookId = bookId,
                chapterIndex = chapterIndex,
                scope = scope,
                ratio = ratio,
                title = "Summary",
                paragraphs = listOf(SummaryParagraph(original = jsonString, translation = "")),
                rawMarkdown = jsonString
            )
        }
    }

    fun buildPlotPrompt(
        config: AiConfig,
        title: String,
        text: String,
        scope: String
    ): Pair<String, String> {
        val systemPrompt = """
            You are an expert literary analyst specializing in narrative structure, character dynamics, and dramaturgical arcs.
            Analyze the provided book/chapter text.
            The source language is ${config.sourceLang} and the target explanation language is ${config.targetLang}.

            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "coreDynamicsOriginal": "One concise paragraph explaining narrative stakes & tension in ${config.sourceLang}...",
              "coreDynamicsTranslation": "Translation in ${config.targetLang}...",
              "characters": [
                {
                  "nameOriginal": "Character Name in ${config.sourceLang}",
                  "nameTranslation": "Character Name in ${config.targetLang}",
                  "faction": "House/Faction/Role",
                  "role": "Protagonist / Antagonist / Mentor / Ally",
                  "relationships": ["Ally to X", "Rival to Y"]
                }
              ],
              "timeline": [
                {
                  "stage": "Opening / 起因",
                  "eventOriginal": "What happens in ${config.sourceLang}...",
                  "eventTranslation": "Event explanation in ${config.targetLang}..."
                },
                {
                  "stage": "Conflict & Turning Point / 冲突与转折",
                  "eventOriginal": "...",
                  "eventTranslation": "..."
                },
                {
                  "stage": "Climax & Resolution / 高潮与结局",
                  "eventOriginal": "...",
                  "eventTranslation": "..."
                }
              ]
            }
        """.trimIndent()

        val userPrompt = """
            Title: $title
            Scope: $scope
            Text:
            $text
        """.trimIndent()

        return Pair(systemPrompt, userPrompt)
    }

    fun parsePlotResponse(
        jsonString: String,
        bookId: String,
        chapterIndex: Int,
        scope: String
    ): AiPlotResult {
        return try {
            val cleanJson = extractJson(jsonString)
            val obj = gson.fromJson(cleanJson, JsonObject::class.java)
            val coreOrig = obj.get("coreDynamicsOriginal")?.asString ?: ""
            val coreTrans = obj.get("coreDynamicsTranslation")?.asString ?: ""

            val charsList = mutableListOf<CharacterCard>()
            obj.getAsJsonArray("characters")?.forEach { item ->
                val cObj = item.asJsonObject
                val relList = mutableListOf<String>()
                cObj.getAsJsonArray("relationships")?.forEach { r -> relList.add(r.asString) }
                charsList.add(
                    CharacterCard(
                        nameOriginal = cObj.get("nameOriginal")?.asString ?: "",
                        nameTranslation = cObj.get("nameTranslation")?.asString ?: "",
                        faction = cObj.get("faction")?.asString ?: "",
                        role = cObj.get("role")?.asString ?: "",
                        relationships = relList
                    )
                )
            }

            val timelineList = mutableListOf<PlotStage>()
            obj.getAsJsonArray("timeline")?.forEach { item ->
                val tObj = item.asJsonObject
                timelineList.add(
                    PlotStage(
                        stage = tObj.get("stage")?.asString ?: "",
                        eventOriginal = tObj.get("eventOriginal")?.asString ?: "",
                        eventTranslation = tObj.get("eventTranslation")?.asString ?: ""
                    )
                )
            }

            AiPlotResult(
                bookId = bookId,
                chapterIndex = chapterIndex,
                scope = scope,
                coreDynamicsOriginal = coreOrig,
                coreDynamicsTranslation = coreTrans,
                characters = charsList,
                timeline = timelineList
            )
        } catch (e: Exception) {
            AiPlotResult(
                bookId = bookId,
                chapterIndex = chapterIndex,
                scope = scope,
                coreDynamicsOriginal = jsonString
            )
        }
    }

    fun buildQuizPrompt(
        config: AiConfig,
        title: String,
        text: String,
        count: Int,
        difficulty: String,
        scope: String
    ): Pair<String, String> {
        val systemPrompt = """
            You are a master reading comprehension test designer.
            Generate $count multiple-choice questions (A, B, C, D) based on the text.
            Difficulty level: $difficulty.
            Source language is ${config.sourceLang}, explanation language is ${config.targetLang}.

            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "questions": [
                {
                  "id": 1,
                  "questionOriginal": "Question text in ${config.sourceLang}...",
                  "questionTranslation": "Question text in ${config.targetLang}...",
                  "options": [
                    "A. Option 1",
                    "B. Option 2",
                    "C. Option 3",
                    "D. Option 4"
                  ],
                  "correctAnswer": "A",
                  "analysisOriginal": "Explanation in ${config.sourceLang}...",
                  "analysisTranslation": "Explanation in ${config.targetLang}..."
                }
              ]
            }
        """.trimIndent()

        val userPrompt = """
            Title: $title
            Scope: $scope
            Text:
            $text
        """.trimIndent()

        return Pair(systemPrompt, userPrompt)
    }

    fun parseQuizResponse(
        jsonString: String,
        bookId: String,
        chapterIndex: Int,
        scope: String,
        count: Int,
        difficulty: String
    ): AiQuizResult {
        return try {
            val cleanJson = extractJson(jsonString)
            val obj = gson.fromJson(cleanJson, JsonObject::class.java)
            val qList = mutableListOf<QuizQuestion>()
            obj.getAsJsonArray("questions")?.forEach { item ->
                val qObj = item.asJsonObject
                val optList = mutableListOf<String>()
                qObj.getAsJsonArray("options")?.forEach { opt -> optList.add(opt.asString) }
                qList.add(
                    QuizQuestion(
                        id = qObj.get("id")?.asInt ?: (qList.size + 1),
                        questionOriginal = qObj.get("questionOriginal")?.asString ?: "",
                        questionTranslation = qObj.get("questionTranslation")?.asString ?: "",
                        options = optList,
                        correctAnswer = qObj.get("correctAnswer")?.asString ?: "A",
                        analysisOriginal = qObj.get("analysisOriginal")?.asString ?: "",
                        analysisTranslation = qObj.get("analysisTranslation")?.asString ?: ""
                    )
                )
            }
            AiQuizResult(
                bookId = bookId,
                chapterIndex = chapterIndex,
                scope = scope,
                count = count,
                difficulty = difficulty,
                questions = qList
            )
        } catch (e: Exception) {
            AiQuizResult(
                bookId = bookId,
                chapterIndex = chapterIndex,
                scope = scope,
                count = count,
                difficulty = difficulty
            )
        }
    }

    private fun extractJson(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("```json")) {
            str = str.substring(7)
        } else if (str.startsWith("```")) {
            str = str.substring(3)
        }
        if (str.endsWith("```")) {
            str = str.substring(0, str.length - 3)
        }
        str = str.trim()
        val firstBrace = str.indexOf('{')
        val lastBrace = str.lastIndexOf('}')
        return if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            str.substring(firstBrace, lastBrace + 1)
        } else {
            str
        }
    }
}
