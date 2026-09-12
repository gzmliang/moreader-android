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
import com.moyue.ai.model.StructuredRelation
import com.moyue.ai.model.SummaryParagraph

object AiPromptBuilder {

    private val gson = Gson()

    fun buildSummaryPrompt(
        config: AiConfig,
        title: String,
        text: String,
        ratio: Int,
        scope: String,
        level: String = "standard",
        mode: String = "bilingual"
    ): Pair<String, String> {
        val wordCount = text.split("\\s+".toRegex()).size
        val targetLength = ((wordCount * ratio) / 100).coerceAtLeast(150)
        val targetLang = config.targetLang.ifBlank { "Chinese" }

        val levelDirective = when (level.lowercase()) {
            "simple" -> """
                VOCABULARY & STYLE DIRECTIVE:
                - Target Audience: Children, young learners, or beginners.
                - Vocabulary: Use simple, clear, and child-friendly words. Avoid archaic expressions, obscure idioms, or dense academic jargon.
                - Sentence Structure: Keep sentences short, vivid, and straightforward. Explain complex plot points and motives in accessible, engaging terms.
            """.trimIndent()
            "advanced" -> """
                VOCABULARY & STYLE DIRECTIVE:
                - Target Audience: Advanced scholars, literature connoisseurs, and critical readers.
                - Vocabulary: Use sophisticated, rich, and intellectually elevated literary and academic vocabulary.
                - Sentence Structure & Insight: Provide deep literary nuance, philosophical insight, and stylistic elegance. Preserve subtext, thematic complexity, and character psychological depth.
            """.trimIndent()
            else -> """
                VOCABULARY & STYLE DIRECTIVE:
                - Target Audience: General readers.
                - Vocabulary: Use balanced, natural, and expressive literary vocabulary suitable for standard reading.
            """.trimIndent()
        }

        val modeInstruction = when (mode) {
            "orig" -> """
                SUMMARY MODE: ORIGINAL LANGUAGE ONLY (原著语言简写)
                - CRITICAL: Condense and write the summary DIRECTLY in the EXACT SAME LANGUAGE as the original text (e.g. if the original text is Chinese, write purely in authentic Chinese; if English, write purely in English).
                - Do NOT translate into English or any other intermediate language. Retain original tone and flavor.
                - In the output JSON, put the condensed text in the "original" field, and leave the "translation" field as empty string "".
            """.trimIndent()
            "target" -> """
                SUMMARY MODE: TARGET LANGUAGE ONLY (仅译文简写)
                - CRITICAL: Condense and write the summary DIRECTLY in the target language: $targetLang.
                - The resulting text must be fluently, accurately, and elegantly written in $targetLang.
                - In the output JSON, put the summary in the "translation" field.
            """.trimIndent()
            else -> """
                SUMMARY MODE: BILINGUAL PARALLEL (双语对照简写)
                - In the "original" field: Condense the text directly in the original source language.
                - In the "translation" field: Provide a faithful, high-quality, and elegant translation of that condensed paragraph into $targetLang.
            """.trimIndent()
        }

        val systemPrompt = """
            You are a world-class literary editor and speed-reading condensation specialist.
            Your task is to condense the provided text to approximately $ratio% of its original depth (target: around $targetLength words).
            Target Explanation / Translation Language: $targetLang.
            
            $levelDirective
            
            $modeInstruction
            
            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "title": "Condensed Title",
              "paragraphs": [
                {
                  "original": "Sentence or paragraph...",
                  "translation": "Corresponding translation or empty string..."
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
        ratio: Int,
        level: String = "standard",
        mode: String = "bilingual"
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
                    var orig = pObj.get("original")?.asString ?: ""
                    val trans = pObj.get("translation")?.asString ?: ""
                    if (mode == "target" && orig.isBlank() && trans.isNotBlank()) {
                        orig = trans
                    }
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
                level = level,
                mode = mode,
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
                level = level,
                mode = mode,
                title = "Summary",
                paragraphs = listOf(SummaryParagraph(original = jsonString, translation = "")),
                rawMarkdown = jsonString
            )
        }
    }

    private fun resolveSourceLanguage(config: AiConfig, sampleText: String): String {
        val configured = config.sourceLang.trim()
        if (configured.isNotBlank() && !configured.equals("Auto", ignoreCase = true)) {
            return configured
        }
        val detected = com.moyue.tts.LanguageVoiceDetector.detectLanguage(sampleText)
        return when (detected) {
            "zh" -> "Chinese"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ru" -> "Russian"
            else -> "English"
        }
    }

    fun buildPlotPrompt(
        config: AiConfig,
        title: String,
        text: String,
        scope: String
    ): Pair<String, String> {
        val effectiveSourceLang = resolveSourceLanguage(config, text)
        val targetLang = config.targetLang.ifBlank { "Chinese" }
        val isNativeChinese = effectiveSourceLang.equals("Chinese", ignoreCase = true)

        val languageMandate = if (isNativeChinese) {
            """
            LANGUAGE DIRECTIVE (CRITICAL - 纯正中文原著模式):
            - The original text is written in authentic Chinese ($effectiveSourceLang).
            - The target explanation language is also $targetLang.
            - Therefore, ALL character names (nameOriginal & nameTranslation), factions, roles, bios, structured relationship labels, and timeline events MUST be directly and fluently written in authentic CHINESE!
            - NEVER translate Chinese character names or terms into English pinyin or English words (e.g. use "令狐冲" directly, NEVER "Linghu Chong"; use "华山派", NEVER "Huashan Sect"; use "师徒/长辈", NEVER "Master/Disciple").
            - For nameTranslation and bioTranslation, you may keep them identical to nameOriginal and bioOriginal, or provide polished contemporary Chinese phrasing.
            """.trimIndent()
        } else {
            """
            LANGUAGE DIRECTIVE:
            - The source language is $effectiveSourceLang and the target explanation language is $targetLang.
            - For foreign works (e.g. English, Japanese), keep original names/terms in "Original" fields and provide accurate $targetLang translations in "Translation" fields.
            """.trimIndent()
        }

        val systemPrompt = """
            You are an expert literary analyst specializing in narrative structure, character dynamics, and dramaturgical arcs.
            Analyze the provided book/chapter text.
            
            $languageMandate

            COMPREHENSIVE CHARACTER EXTRACTION MANDATORY DIRECTIVE:
            - You MUST comprehensively extract and analyze ALL significant, named, and recurring characters in the text.
            - NEVER limit your output to just 2-3 top protagonists! For a book overview, aim for 15 to 30 characters; for a chapter, include all active and mentioned key individuals.
            - Crucially, you MUST explicitly create separate, dedicated character entries for ALL family members, disciples, sect elders, companions, and antagonists.
            - Example: In House Stark or 华山派, do NOT lump individuals into a single string. Provide distinct, rich character entries for each individual!

            CRITICAL RELATIONSHIP DIRECTION & PERSPECTIVE DIRECTIVE (严防角色颠倒与视角混淆 - 最高铁律):
            - In "structuredRelations", the "label" and "labelTranslation" MUST ALWAYS describe the TARGET's role/identity relative to the current character (i.e. "Who is the TARGET to THIS character?").
            - NEVER label the relation with the current character's OWN role toward the target! The UI renders these as "[label]: [target]" under this character's profile.
            - Strict Concrete Rules & Few-Shot Examples:
              * Animal Companions / Pets / Mounts:
                - In Jon Snow's card: target is "Ghost" -> label MUST be "Direwolf Companion" (labelTranslation: "冰原狼伙伴"), NEVER "Master"!
                - In Ghost's card: target is "Jon Snow" -> label MUST be "Master / Companion" (labelTranslation: "主人/伙伴").
              * Superior / Subordinate (Master / Disciple / Commander):
                - In Jon Snow's card: target is "Jeor Mormont" -> label MUST be "Lord Commander" or "Superior" (labelTranslation: "守夜人总司令/长官"), NEVER "Steward"!
                - In 令狐冲's card: target is "岳不群" -> label & labelTranslation MUST be "恩师 / 掌门", NEVER "徒弟 / 大弟子"!
                - In 岳不群's card: target is "令狐冲" -> label & labelTranslation MUST be "大弟子 / 徒弟", NEVER "师父"!
              * Parent / Child Lineage:
                - In Eddard Stark's card: target "Robb Stark" -> label MUST be "Eldest Son / Heir" (labelTranslation: "长子/继承人"), NEVER "Father"!
                - In 岳灵珊's card: target "岳不群" -> label & labelTranslation MUST be "父亲", NEVER "女儿"!
              * Sibling & Couple Relationships:
                - In 岳灵珊's card: target "令狐冲" -> label & labelTranslation MUST be "大师兄 / 青梅竹马", NEVER "小师妹"!

            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "coreDynamicsOriginal": "One concise paragraph explaining narrative stakes & tension in $effectiveSourceLang...",
              "coreDynamicsTranslation": "Explanation in $targetLang...",
              "characters": [
                {
                  "nameOriginal": "Character Name in $effectiveSourceLang",
                  "nameTranslation": "Character Name in $targetLang",
                  "faction": "House/Sect/Faction/Role (e.g. 华山派, 日月神教, House Stark)",
                  "role": "Protagonist / Antagonist / Mentor / Ally / 掌门 / 弟子",
                  "bioOriginal": "2-3 sentences introducing the character's background, identity, and current situation in $effectiveSourceLang.",
                  "bioTranslation": "Character introduction in $targetLang.",
                  "structuredRelations": [
                    {
                      "category": "parent / spouse / child / sibling / ally / rival / other",
                      "label": "Target's role in $effectiveSourceLang (e.g. 恩师, 父亲, 丈夫, Lord Commander)",
                      "labelTranslation": "Target's role in $targetLang (e.g. 恩师, 父亲, 丈夫, 守夜人总司令)",
                      "target": "Target Character Name"
                    }
                  ],
                  "relationships": ["恩师: 岳不群", "冰原狼伙伴: Ghost"]
                }
              ],
              "timeline": [
                {
                  "stage": "Opening / 起因",
                  "eventOriginal": "What happens in $effectiveSourceLang...",
                  "eventTranslation": "Event explanation in $targetLang..."
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

                val structRelList = mutableListOf<StructuredRelation>()
                cObj.getAsJsonArray("structuredRelations")?.forEach { sr ->
                    val sObj = sr.asJsonObject
                    structRelList.add(
                        StructuredRelation(
                            category = sObj.get("category")?.asString ?: "other",
                            label = sObj.get("label")?.asString ?: "",
                            labelTranslation = sObj.get("labelTranslation")?.asString ?: "",
                            target = sObj.get("target")?.asString ?: ""
                        )
                    )
                }

                charsList.add(
                    CharacterCard(
                        nameOriginal = cObj.get("nameOriginal")?.asString ?: "",
                        nameTranslation = cObj.get("nameTranslation")?.asString ?: "",
                        faction = cObj.get("faction")?.asString ?: "",
                        role = cObj.get("role")?.asString ?: "",
                        relationships = relList,
                        bioOriginal = cObj.get("bioOriginal")?.asString ?: "",
                        bioTranslation = cObj.get("bioTranslation")?.asString ?: "",
                        structuredRelations = structRelList
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
        val effectiveSourceLang = resolveSourceLanguage(config, text)
        val targetLang = config.targetLang.ifBlank { "Chinese" }
        val isNativeChinese = effectiveSourceLang.equals("Chinese", ignoreCase = true)

        val languageDirective = if (isNativeChinese) {
            """
            LANGUAGE DIRECTIVE (CRITICAL - 纯正中文原著模式):
            - The text is written in authentic Chinese.
            - All questions, options, and explanations MUST be generated purely and naturally in CHINESE.
            - NEVER translate questions, character names, or options into English or Pinyin.
            """.trimIndent()
        } else {
            """
            LANGUAGE DIRECTIVE:
            - Source language is $effectiveSourceLang, explanation language is $targetLang.
            """.trimIndent()
        }

        val systemPrompt = """
            You are a master reading comprehension test designer.
            Generate $count multiple-choice questions (A, B, C, D) based on the text.
            Difficulty level: $difficulty.
            
            $languageDirective

            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "questions": [
                {
                  "id": 1,
                  "questionOriginal": "Question text in $effectiveSourceLang...",
                  "questionTranslation": "Question text in $targetLang...",
                  "options": [
                    "A. Option 1",
                    "B. Option 2",
                    "C. Option 3",
                    "D. Option 4"
                  ],
                  "correctAnswer": "A",
                  "analysisOriginal": "Explanation in $effectiveSourceLang...",
                  "analysisTranslation": "Explanation in $targetLang..."
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
