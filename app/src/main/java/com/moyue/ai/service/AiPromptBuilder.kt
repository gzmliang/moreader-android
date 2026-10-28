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
import com.moyue.ai.model.StudyInsightResult
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

    fun isChineseText(sampleText: String, title: String = ""): Boolean {
        val sample = (title + " " + sampleText.take(1500))
        val count = sample.count { it in '\u4e00'..'\u9fff' }
        return count >= 5 || (sample.isNotBlank() && count.toDouble() / sample.length > 0.1) || com.moyue.tts.LanguageVoiceDetector.detectLanguage(sampleText) == "zh"
    }

    private fun resolveSourceLanguage(config: AiConfig, sampleText: String, title: String = ""): String {
        // 铁律：只要书名或正文包含中文，强制锁死为纯正中文模式，绝不受任何旧配置干扰
        if (isChineseText(sampleText, title)) {
            return "Chinese"
        }
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
        val effectiveSourceLang = resolveSourceLanguage(config, text, title)
        val targetLang = config.targetLang.ifBlank { "Chinese" }
        val isNativeChinese = effectiveSourceLang.equals("Chinese", ignoreCase = true)
        val isNativeEnglish = effectiveSourceLang.equals("English", ignoreCase = true)

        val languageMandate = when {
            isNativeChinese -> {
                """
                LANGUAGE DIRECTIVE (CRITICAL - 纯正中文原著最高铁律 - 绝严禁任何英文或拼音):
                - The original book text is authentic CHINESE ($effectiveSourceLang).
                - The target explanation language is also CHINESE ($targetLang).
                - MANDATORY RULE: ALL character names (nameOriginal & nameTranslation), factions, roles, bios (bioOriginal & bioTranslation), structured relationship labels (label & labelTranslation), core narrative dynamics, and timeline events MUST be written 100% in authentic, idiomatic CHINESE!
                - STRICT PROHIBITION: NEVER translate any Chinese character name, sect/faction, martial art, or term into English or Pinyin!
                  * Example: Use "令狐冲" for BOTH "nameOriginal" and "nameTranslation" (ABSOLUTELY NEVER "Linghu Chong").
                  * Example: Use "任盈盈" for BOTH "nameOriginal" and "nameTranslation" (ABSOLUTELY NEVER "Ren Yingying").
                  * Example: Use "岳不群" for BOTH "nameOriginal" and "nameTranslation" (ABSOLUTELY NEVER "Yue Buqun").
                  * Example: Use "华山派" (NEVER "Huashan Sect"), "日月神教" (NEVER "Sun Moon Holy Cult"), "辟邪剑法" (NEVER "Bixie Swordplay").
                  * Example: Use "恩师 / 掌门" (NEVER "Master / Leader"), "大师兄 / 掌门大弟子" (NEVER "Senior Disciple").
                - For all "Original" and "Translation" paired fields (nameOriginal & nameTranslation, bioOriginal & bioTranslation, coreDynamicsOriginal & coreDynamicsTranslation, eventOriginal & eventTranslation), directly fill BOTH fields with authentic, natural Chinese.
                """.trimIndent()
            }
            isNativeEnglish -> {
                """
                LANGUAGE DIRECTIVE (CRITICAL - PURE ENGLISH STRICT MANDATE):
                - The original book text is in ENGLISH ($effectiveSourceLang).
                - MANDATORY RULE: ALL character names (nameOriginal & nameTranslation), factions, roles, bios (bioOriginal & bioTranslation), structured relationship labels (label & labelTranslation), core narrative dynamics, and timeline events MUST be written 100% in authentic, natural ENGLISH!
                - STRICT PROHIBITION: DO NOT use Chinese, Pinyin, or any non-English language anywhere!
                  * Example: Use "Annie" for BOTH "nameOriginal" and "nameTranslation".
                  * Example: Use "Sister & Adventurer" for BOTH "label" and "labelTranslation" (ABSOLUTELY NO Chinese like "妹妹兼冒险伙伴").
                  * Example: Use "Frog Creek / Camelot Travelers" for "faction" (ABSOLUTELY NO Chinese like "蛙溪镇").
                  * Example: Use "Protagonist" or "Adventurer" for "role" (ABSOLUTELY NO Chinese like "主角").
                - For all "Original" and "Translation" paired fields, fill BOTH fields directly in pure, natural English!
                """.trimIndent()
            }
            else -> {
                """
                LANGUAGE DIRECTIVE:
                - The source language is $effectiveSourceLang and the target explanation language is $targetLang.
                - For foreign works (e.g. Japanese, French), keep original names/terms in "Original" fields and provide accurate $targetLang translations in "Translation" fields.
                """.trimIndent()
            }
        }

        val jsonFormatExample = if (isNativeChinese) {
            """
            {
              "coreDynamicsOriginal": "一至两段凝练生动的叙事格局、核心冲突与剧情张力分析（纯正中文）...",
              "coreDynamicsTranslation": "一至两段凝练生动的叙事格局、核心冲突与剧情张力分析（纯正中文）...",
              "characters": [
                {
                  "nameOriginal": "令狐冲",
                  "nameTranslation": "令狐冲",
                  "faction": "华山派",
                  "role": "大弟子 / 主角",
                  "bioOriginal": "华山派大弟子，生性豁达不羁，机缘巧合下习得独孤九剑，卷入五岳剑派与日月神教的纷争。",
                  "bioTranslation": "华山派大弟子，生性豁达不羁，机缘巧合下习得独孤九剑，卷入五岳剑派与日月神教的纷争。",
                  "structuredRelations": [
                    {
                      "category": "parent",
                      "label": "恩师 / 养父",
                      "labelTranslation": "恩师 / 养父",
                      "target": "岳不群"
                    },
                    {
                      "category": "spouse",
                      "label": "生死知己 / 伴侣",
                      "labelTranslation": "生死知己 / 伴侣",
                      "target": "任盈盈"
                    }
                  ],
                  "relationships": ["恩师: 岳不群", "伴侣: 任盈盈"]
                }
              ],
              "timeline": [
                {
                  "stage": "起因 / 开端",
                  "eventOriginal": "福威镖局惨遭灭门，辟邪剑谱引动江湖各大门派暗流涌动...",
                  "eventTranslation": "福威镖局惨遭灭门，辟邪剑谱引动江湖各大门派暗流涌动..."
                },
                {
                  "stage": "冲突与转折",
                  "eventOriginal": "令狐冲思过崖获风清扬传授独孤九剑，重创强敌却见疑于师门...",
                  "eventTranslation": "令狐冲思过崖获风清扬传授独孤九剑，重创强敌却见疑于师门..."
                },
                {
                  "stage": "高潮与结局",
                  "eventOriginal": "黑木崖力战东方不败，五岳剑派合并阴谋败露，令狐冲与任盈盈琴箫和鸣隐退江湖...",
                  "eventTranslation": "黑木崖力战东方不败，五岳剑派合并阴谋败露，令狐冲与任盈盈琴箫和鸣隐退江湖..."
                }
              ]
            }
            """.trimIndent()
        } else if (isNativeEnglish) {
            """
            {
              "coreDynamicsOriginal": "One concise paragraph explaining narrative stakes & tension in English...",
              "coreDynamicsTranslation": "One concise paragraph explaining narrative stakes & tension in English...",
              "characters": [
                {
                  "nameOriginal": "Jon Snow",
                  "nameTranslation": "Jon Snow",
                  "faction": "Night's Watch",
                  "role": "Protagonist / Lord Commander",
                  "bioOriginal": "Raised at Winterfell, Jon joins the Night's Watch to defend the realm, navigating loyalty and honor.",
                  "bioTranslation": "Raised at Winterfell, Jon joins the Night's Watch to defend the realm, navigating loyalty and honor.",
                  "structuredRelations": [
                    {
                      "category": "parent",
                      "label": "Father / Guardian",
                      "labelTranslation": "Father / Guardian",
                      "target": "Ned Stark"
                    },
                    {
                      "category": "ally",
                      "label": "Direwolf Companion",
                      "labelTranslation": "Direwolf Companion",
                      "target": "Ghost"
                    }
                  ],
                  "relationships": ["Father / Guardian: Ned Stark", "Direwolf Companion: Ghost"]
                }
              ],
              "timeline": [
                {
                  "stage": "Opening & Inciting Incident",
                  "eventOriginal": "Jon departs Winterfell and arrives at the Wall...",
                  "eventTranslation": "Jon departs Winterfell and arrives at the Wall..."
                },
                {
                  "stage": "Conflict & Rising Stakes",
                  "eventOriginal": "Beyond the Wall, Jon faces the harsh realities of the wildlings...",
                  "eventTranslation": "Beyond the Wall, Jon faces the harsh realities of the wildlings..."
                },
                {
                  "stage": "Climax & Resolution",
                  "eventOriginal": "The battle for Castle Black proves Jon's leadership...",
                  "eventTranslation": "The battle for Castle Black proves Jon's leadership..."
                }
              ]
            }
            """.trimIndent()
        } else {
            """
            {
              "coreDynamicsOriginal": "One concise paragraph explaining narrative stakes & tension in $effectiveSourceLang...",
              "coreDynamicsTranslation": "Explanation in $targetLang...",
              "characters": [
                {
                  "nameOriginal": "Character Name in $effectiveSourceLang",
                  "nameTranslation": "Character Name in $targetLang",
                  "faction": "House/Sect/Faction/Role (e.g. House Stark, Night's Watch)",
                  "role": "Protagonist / Antagonist / Mentor / Ally",
                  "bioOriginal": "2-3 sentences introducing the character's background, identity, and current situation in $effectiveSourceLang.",
                  "bioTranslation": "Character introduction in $targetLang.",
                  "structuredRelations": [
                    {
                      "category": "parent / spouse / child / sibling / ally / rival / other",
                      "label": "Target's role in $effectiveSourceLang (e.g. Father, Husband, Lord Commander)",
                      "labelTranslation": "Target's role in $targetLang",
                      "target": "Target Character Name"
                    }
                  ],
                  "relationships": ["Father: Ned Stark", "Lord Commander: Jeor Mormont"]
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
                  "eventOriginal": "Major turning point in $effectiveSourceLang...",
                  "eventTranslation": "Turning point explanation in $targetLang..."
                },
                {
                  "stage": "Climax & Resolution / 高潮与结局",
                  "eventOriginal": "Resolution in $effectiveSourceLang...",
                  "eventTranslation": "Resolution explanation in $targetLang..."
                }
              ]
            }
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
            $jsonFormatExample
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
        val effectiveSourceLang = resolveSourceLanguage(config, text, title)
        val targetLang = config.targetLang.ifBlank { "Chinese" }
        val isNativeChinese = effectiveSourceLang.equals("Chinese", ignoreCase = true)
        val isNativeEnglish = effectiveSourceLang.equals("English", ignoreCase = true)

        val languageDirective = when {
            isNativeChinese -> {
                """
                LANGUAGE DIRECTIVE (CRITICAL - 纯正中文原著模式):
                - The text is written in authentic Chinese.
                - All questions, options, and explanations MUST be generated purely and naturally in CHINESE.
                - NEVER translate questions, character names, or options into English or Pinyin.
                """.trimIndent()
            }
            isNativeEnglish -> {
                """
                LANGUAGE DIRECTIVE (CRITICAL - PURE ENGLISH STRICT MANDATE):
                - The text is written in authentic English.
                - All questions, options, and explanations MUST be generated 100% in authentic, natural ENGLISH.
                - "questionOriginal" and "analysisOriginal" MUST be written in English.
                - Leave "questionTranslation" and "analysisTranslation" as empty strings ("").
                - ABSOLUTELY NO Chinese, Pinyin, or other languages anywhere in the output!
                """.trimIndent()
            }
            else -> {
                """
                LANGUAGE DIRECTIVE:
                - Source language is $effectiveSourceLang, explanation language is $targetLang.
                """.trimIndent()
            }
        }

        val difficultyDirective = when (difficulty.lowercase()) {
            "easy" -> """
                DIFFICULTY — EASY (Explicit Fact Recall):
                - Test only information that is DIRECTLY and EXPLICITLY stated in the text.
                - The correct answer should be traceable to a near-direct quote or clear statement in the passage.
                - Questions should focus on: key events that happened, key facts mentioned, who did what.
                - Distractors must be plausible but factually wrong based on the passage — never obviously absurd.
                - For narrative/fiction: focus on story events, character actions, explicit facts.
                - For science/non-fiction: focus on key concepts, definitions, or phenomena explicitly described.
            """.trimIndent()
            "medium" -> """
                DIFFICULTY — MEDIUM (Comprehension & Connection):
                - Test understanding that requires connecting 2–3 pieces of information from different parts of the text.
                - Questions should probe "why" and "how", not just "what".
                - The correct answer requires the reader to interpret meaning, not just locate a quote.
                - Distractors should be partially correct or easy to confuse — only careful readers can distinguish them.
                - For narrative/fiction: focus on character motivation, cause & effect, relationship dynamics.
                - For science/non-fiction: focus on explaining a principle, understanding why something works, or the significance of a finding.
            """.trimIndent()
            else -> """
                DIFFICULTY — HARD (Inference, Reasoning & Evaluation):
                - Test higher-order thinking: inference, prediction, comparison, or critical evaluation.
                - The correct answer CANNOT be found as a direct quote — the reader must reason from the text.
                - Questions should require the reader to synthesize information, evaluate implications, or draw conclusions.
                - Distractors must be subtle and sophisticated — only a reader who truly understood the text can distinguish them.
                - For narrative/fiction: probe theme, character psychology, authorial intent, or "what if" consequences.
                - For science/non-fiction: probe the underlying principle, its real-world application, its limitations, or broader implications.
            """.trimIndent()
        }

        val systemPrompt = """
            You are an expert reading comprehension test designer. Your mission is to verify whether a reader has TRULY UNDERSTOOD the text — not merely memorized surface details.

            CORE PHILOSOPHY:
            Understanding has three layers:
            1. Knowing WHAT happened or was stated (facts) → Easy
            2. Understanding WHY and HOW things connect (comprehension) → Medium
            3. Being able to INFER, REASON, and EVALUATE beyond the text (critical thinking) → Hard

            $difficultyDirective

            $languageDirective

            STRICT PROHIBITIONS — NEVER violate these rules:
            1. NEVER ask about the author's name, book title, chapter title, or any publication metadata.
            2. NEVER repeat the same fact, event, or concept across multiple questions — each question must test a DIFFERENT aspect of the text.
            3. NEVER include obviously absurd, off-topic, or implausible distractors — all options must be grounded in the text's world.
            4. NEVER generate Easy-level recall questions when difficulty is Medium or Hard.
            5. NEVER ask questions whose answers are not supported by the provided text.
            6. SCOPE LOCK (MOST CRITICAL RULE): You MUST generate ALL questions EXCLUSIVELY from the text provided below in the user message. This is the ONLY source of truth.
               - NEVER reference events, characters, chapters, or facts from outside the provided text — even if you have prior knowledge of this book.
               - NEVER mention chapter numbers or events that do not appear in the provided text.
               - Every question, every option, and every analysis explanation MUST be directly traceable to specific content within the provided text.
               - If the provided text covers Chapters 6–8, you MUST NOT reference Chapter 12, Chapter 13, or any content beyond what is given.

            QUESTION DIVERSITY — distribute questions across these types (do not cluster on one type):
            - For narrative/fiction: story events, character motivation, cause & effect, relationship dynamics, theme, consequence
            - For science/non-fiction: key concepts, cause & effect, principle explanation, real-world application, implication

            Generate exactly $count questions. Each question must test a DISTINCT aspect of the text.

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
                  "analysisOriginal": "Explanation citing specific evidence from the text in $effectiveSourceLang...",
                  "analysisTranslation": "Explanation in $targetLang..."
                }
              ]
            }
        """.trimIndent()

        val userPrompt = """
            Title: $title
            Scope (THIS IS THE ONLY TEXT YOU MAY USE — DO NOT go beyond this): $scope

            ===== START OF AUTHORIZED TEXT =====
            $text
            ===== END OF AUTHORIZED TEXT =====

            REMINDER: Every single question and every option MUST be based SOLELY on the text between the markers above.
            DO NOT reference any chapters, events, or characters outside of this text.
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

    fun buildPdfVisionQuizPrompt(
        config: AiConfig,
        bookTitle: String,
        startPage: Int,
        endPage: Int,
        count: Int = 5,
        difficulty: String = "medium",
        customPrompt: String = ""
    ): Pair<String, String> {
        val targetLang = config.targetLang.ifBlank { "Chinese" }
        val isChinese = !targetLang.equals("English", ignoreCase = true)

        val diffDirective = when (difficulty.lowercase()) {
            "easy", "basic" -> if (isChinese) {
                "【难度：基础识记】聚焦故事中直接写出的事实、人物名字、具体发生的事情，答案直接在原文能找到。"
            } else {
                "DIFFICULTY: EASY (Fact Recall). Focus on directly stated facts, character names, and straightforward events."
            }
            "hard", "advanced" -> if (isChinese) {
                "【难度：深度思考】聚焦人物心理动机、故事蕴含的深层道理、因果关系与哲理启示，需结合全文综合推断。"
            } else {
                "DIFFICULTY: HARD (Deep Inference). Focus on character motives, underlying moral lessons, and subtle implications."
            }
            else -> if (isChinese) {
                "【难度：理解关联】考查情节发展的前因后果、关键转折与人物关系，考查读者对故事脉络的连贯理解。"
            } else {
                "DIFFICULTY: MEDIUM (Comprehension & Connection). Focus on cause-and-effect, pivotal turning points, and character dynamics."
            }
        }

        val customDirective = if (customPrompt.isNotBlank()) {
            if (isChinese) {
                "\n【读者/出题教师特别指定要求（最高优先级，务必严格遵从）】\n$customPrompt\n"
            } else {
                "\n[TEACHER / READER SPECIAL DIRECTIVE (HIGHEST PRIORITY)]\n$customPrompt\n"
            }
        } else ""

        val systemPrompt = if (isChinese) {
            """
            你是一位资深的高级阅读理解名师与互动教学专家。
            读者提供了书籍《$bookTitle》第 $startPage 页到第 $endPage 页的高清页面图像。
            这些页面通常包含一个完整的寓言故事、童话篇章或历史典故（可能带有汉语拼音或插图）。

            【核心任务流程】
            步骤 1：仔细阅读图片中的所有正文内容。如果页面带有汉语拼音标注，请忽略拼音直接识读汉字正文；忽略页面杂质噪点或插图。
            步骤 2：提炼这几页中真实讲述的故事核心脉络（时间、地点、人物、起因、经过、结果、心理与关键对话）。
            步骤 3：严格且仅仅依据图片中识别出的具体故事内容，设计 $count 道单项选择题（每题4个选项 A、B、C、D）。

            $diffDirective
            $customDirective

            【题目质量红线】
            1. 题目必须 100% 针对图片中具体的故事细节（比如人物叫什么、做了什么事、说了什么话、得到的道理），绝对严禁空洞泛泛出题！
            2. 正确答案必须唯一且确凿，能在图片文字中找到明确依据。
            3. 干扰项要具备合理迷惑性，但绝不能与原文事实相符。
            4. 语言必须自然纯正，全部使用中文。

            【输出格式必须严格遵循以下 JSON】
            {
              "storyTitle": "识别出的故事篇名",
              "questions": [
                {
                  "id": 1,
                  "questionOriginal": "题目内容...",
                  "options": [
                    "A. 选项A内容",
                    "B. 选项B内容",
                    "C. 选项C内容",
                    "D. 选项D内容"
                  ],
                  "correctAnswer": "A",
                  "analysisOriginal": "详细解析，指出对应图片原文的哪个句子或情节。"
                }
              ]
            }
            """.trimIndent()
        } else {
            """
            You are an expert reading comprehension teacher and curriculum designer.
            You are given high-resolution page images from "$bookTitle", pages $startPage to $endPage.
            These pages typically contain a complete children's story, fable, or book chapter.

            TASKS:
            Step 1: Thoroughly read and transcribe all narrative text from the provided images. Ignore pronunciation marks and visual noise.
            Step 2: Understand the narrative arc and key events that actually happen in these pages.
            Step 3: Strictly based on the transcribed story, create $count high-quality multiple-choice questions (A, B, C, D).

            $diffDirective
            $customDirective

            MANDATORY OUTPUT FORMAT (valid JSON object):
            {
              "storyTitle": "Detected Story Title",
              "questions": [
                {
                  "id": 1,
                  "questionOriginal": "Question text...",
                  "options": ["A. ...", "B. ...", "C. ...", "D. ..."],
                  "correctAnswer": "A",
                  "analysisOriginal": "Detailed explanation citing the specific sentence or event from the pages."
                }
              ]
            }
            """.trimIndent()
        }

        val userPrompt = if (isChinese) {
            "请阅读以上第 $startPage 页至第 $endPage 页的图片，严格按照指令提取故事并生成 $count 道高质量选择题，直接输出 JSON 对象："
        } else {
            "Please read the provided page images ($startPage to $endPage) and generate $count story-based comprehension questions in JSON:"
        }

        return Pair(systemPrompt, userPrompt)
    }

    fun buildStudyMaterialPrompt(
        config: AiConfig,
        title: String,
        text: String
    ): Pair<String, String> {
        val effectiveSourceLang = resolveSourceLanguage(config, text, title)
        val targetLang = config.targetLang.ifBlank { "Chinese" }
        val isNativeChinese = effectiveSourceLang.equals("Chinese", ignoreCase = true)
        val isNativeEnglish = effectiveSourceLang.equals("English", ignoreCase = true)

        val langDirective = when {
            isNativeChinese -> "All outputs (summary and keyPoints) MUST be in authentic, fluent CHINESE."
            isNativeEnglish -> "All outputs (summary and keyPoints) MUST be 100% in authentic, natural ENGLISH."
            else -> "All outputs (summary and keyPoints) MUST be in $targetLang."
        }

        val systemPrompt = """
            You are an expert reading comprehension tutor and curriculum designer.
            The reader wants you to carefully examine and digest a specific portion of a book or document before quiz questions are prepared.
            Your task is to summarize the core theme and extract 3 to 5 distinct, high-value educational key points from the provided text.

            DIRECTIVES:
            1. Strict Fidelity: Every single point must come directly from the text provided. Do not invent or assume outside facts.
            2. High Informational Density: Formulate clear, concise, self-contained key insights (e.g. definitions, key events, cause-and-effect relations, or physical principles).
            3. $langDirective

            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "summary": "1 concise sentence summarizing the main thesis or story arc of this section.",
              "keyPoints": [
                "1. Key point or core concept explanation...",
                "2. Second key point or narrative turning point...",
                "3. Third key point..."
              ]
            }
        """.trimIndent()

        val userPrompt = """
            Title: $title
            Text:
            $text
        """.trimIndent()

        return Pair(systemPrompt, userPrompt)
    }

    fun parseStudyMaterialResponse(jsonString: String): StudyInsightResult {
        return try {
            val cleanJson = extractJson(jsonString)
            val obj = gson.fromJson(cleanJson, JsonObject::class.java)
            val summary = obj.get("summary")?.asString ?: ""
            val points = mutableListOf<String>()
            obj.getAsJsonArray("keyPoints")?.forEach {
                val str = it.asString.trim()
                if (str.isNotBlank()) points.add(str)
            }
            StudyInsightResult(summary = summary, keyPoints = points)
        } catch (e: Exception) {
            StudyInsightResult(
                summary = "",
                keyPoints = emptyList()
            )
        }
    }

    fun buildInteractiveQuizPrompt(
        config: AiConfig,
        title: String,
        text: String,
        keyPoints: List<String>,
        count: Int,
        difficulty: String,
        scope: String,
        userInstruction: String = "",
        avoidQuestions: List<String> = emptyList()
    ): Pair<String, String> {
        val effectiveSourceLang = resolveSourceLanguage(config, text, title)
        val targetLang = config.targetLang.ifBlank { "Chinese" }
        val isNativeChinese = effectiveSourceLang.equals("Chinese", ignoreCase = true)
        val isNativeEnglish = effectiveSourceLang.equals("English", ignoreCase = true)

        val languageDirective = when {
            isNativeChinese -> {
                """
                LANGUAGE DIRECTIVE (CRITICAL - 纯正中文原著模式):
                - The text is written in authentic Chinese.
                - All questions, options, and explanations MUST be generated purely and naturally in CHINESE.
                - NEVER translate questions, character names, or options into English or Pinyin.
                """.trimIndent()
            }
            isNativeEnglish -> {
                """
                LANGUAGE DIRECTIVE (CRITICAL - PURE ENGLISH STRICT MANDATE):
                - The text is written in authentic English.
                - All questions, options, and explanations MUST be generated 100% in authentic, natural ENGLISH.
                - "questionOriginal" and "analysisOriginal" MUST be written in English.
                - Leave "questionTranslation" and "analysisTranslation" as empty strings ("").
                - ABSOLUTELY NO Chinese, Pinyin, or other languages anywhere in the output!
                """.trimIndent()
            }
            else -> {
                """
                LANGUAGE DIRECTIVE:
                - Source language is $effectiveSourceLang, explanation language is $targetLang.
                """.trimIndent()
            }
        }

        val difficultyDirective = when (difficulty.lowercase()) {
            "easy", "basic" -> """
                DIFFICULTY — EASY (Explicit Fact Recall):
                - Test only information that is DIRECTLY and EXPLICITLY stated in the text.
                - The correct answer should be traceable to a near-direct quote or clear statement in the passage.
                - Distractors must be plausible but factually wrong based on the passage.
            """.trimIndent()
            "medium", "intermediate" -> """
                DIFFICULTY — MEDIUM (Comprehension & Connection):
                - Test understanding that requires connecting 2–3 pieces of information from different parts of the text.
                - Questions should probe "why" and "how", not just surface facts.
                - The correct answer requires the reader to interpret meaning or principle.
            """.trimIndent()
            else -> """
                DIFFICULTY — HARD (Inference, Reasoning & Application):
                - Test higher-order thinking: inference, mechanism synthesis, or critical evaluation.
                - The correct answer CANNOT be found as a direct quote — reader must reason from the text.
            """.trimIndent()
        }

        val agreedPointsSection = if (keyPoints.isNotEmpty()) {
            """
            AGREED AND VERIFIED CORE INSIGHTS (ALIGNED WITH THE READER):
            ${keyPoints.mapIndexed { idx, p -> "${idx + 1}. $p" }.joinToString("\n")}
            -> You SHOULD prioritize designing questions that test these verified insights.
            """.trimIndent()
        } else ""

        val customInstructionSection = if (userInstruction.isNotBlank()) {
            """
            SPECIAL READER INSTRUCTION (HIGHEST PRIORITY):
            The reader explicitly asked: "$userInstruction"
            -> You MUST tailor the questions, emphasis, or tone to honor this instruction.
            """.trimIndent()
        } else ""

        val avoidSection = if (avoidQuestions.isNotEmpty()) {
            """
            AVOID DUPLICATING PREVIOUS QUESTIONS:
            The reader has already tested these questions:
            ${avoidQuestions.joinToString("\n") { "- $it" }}
            -> You MUST NOT repeat these questions or test the exact same angles. Produce fresh questions from other aspects of the text.
            """.trimIndent()
        } else ""

        val systemPrompt = """
            You are an expert reading comprehension test designer collaborating directly with the reader.
            Your mission is to formulate $count high-quality multiple choice questions based on the provided text.

            $difficultyDirective

            $languageDirective

            $agreedPointsSection

            $customInstructionSection

            $avoidSection

            STRICT RULES:
            1. SCOPE LOCK: Questions MUST be strictly derived from the provided text. No outside facts or imaginary details.
            2. Multiple Choice Format: Exactly 4 options (A, B, C, D) per question, with only ONE correct answer.
            3. Meaningful Distractors: Distractors must be grounded in the context, not absurd or trivial.
            4. Detailed Explanation: Provide an accurate analysis ("analysisOriginal") justifying the correct answer by pointing to text evidence.

            OUTPUT FORMAT:
            You MUST return a JSON object with this EXACT structure:
            {
              "questions": [
                {
                  "id": 1,
                  "questionOriginal": "Question text in $effectiveSourceLang...",
                  "questionTranslation": "Question text in $targetLang or empty...",
                  "options": [
                    "A. Option 1",
                    "B. Option 2",
                    "C. Option 3",
                    "D. Option 4"
                  ],
                  "correctAnswer": "A",
                  "analysisOriginal": "Detailed explanation in $effectiveSourceLang...",
                  "analysisTranslation": "Explanation translation in $targetLang or empty..."
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
