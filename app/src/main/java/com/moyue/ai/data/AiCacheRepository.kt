package com.moyue.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiPlotResult
import com.moyue.ai.model.AiQuizResult
import com.moyue.ai.model.AiSummaryResult
import com.moyue.ai.model.QuizReportRecord

class AiCacheRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("moreader_ai_prefs", Context.MODE_PRIVATE)
    private val appPrefs: SharedPreferences = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Config
    fun getAiConfig(): AiConfig {
        val json = prefs.getString("ai_config_json", null)
        val config = if (json != null) {
            try {
                gson.fromJson(json, AiConfig::class.java)
            } catch (e: Exception) {
                AiConfig()
            }
        } else {
            AiConfig()
        }

        // 统一模型互通：如果 AI 伴读未配置 Key，但翻译设置里已配置了 Key，则无缝继承翻译设置
        if (config.apiKey.isBlank()) {
            val transProvider = appPrefs.getString("llm_provider", "custom") ?: "custom"
            val transApiKey = appPrefs.getString("llm_apikey", "") ?: ""
            val transEndpoint = appPrefs.getString("llm_endpoint", "") ?: ""
            val transModel = appPrefs.getString("llm_model", "") ?: ""
            val transTargetLang = appPrefs.getString("llm_target_lang", "Chinese") ?: "Chinese"
            if (transApiKey.isNotBlank()) {
                val mappedProvider = when (transProvider.lowercase()) {
                    "deepseek" -> "DeepSeek"
                    "siliconflow" -> "SiliconFlow"
                    "openrouter" -> "OpenRouter"
                    "openai" -> "OpenAI"
                    else -> "Custom"
                }
                val fallbackBaseUrl = if (transEndpoint.isNotBlank()) transEndpoint else when (mappedProvider) {
                    "DeepSeek" -> "https://api.deepseek.com/v1"
                    "SiliconFlow" -> "https://api.siliconflow.cn/v1"
                    "OpenRouter" -> "https://openrouter.ai/api/v1"
                    else -> "https://api.openai.com/v1"
                }
                val fallbackModel = if (transModel.isNotBlank()) transModel else when (mappedProvider) {
                    "DeepSeek" -> "deepseek-chat"
                    "SiliconFlow" -> "Qwen/Qwen2.5-72B-Instruct"
                    else -> "gpt-4o-mini"
                }
                return config.copy(
                    provider = mappedProvider,
                    baseUrl = fallbackBaseUrl,
                    apiKey = transApiKey,
                    model = fallbackModel,
                    targetLang = if (config.targetLang.isNotBlank()) config.targetLang else transTargetLang
                )
            }
        }
        return config
    }

    fun saveAiConfig(config: AiConfig) {
        prefs.edit().putString("ai_config_json", gson.toJson(config)).apply()
        // 双向同步写入翻译设置，保证一次配置处处生效
        appPrefs.edit()
            .putString("llm_provider", config.provider.lowercase())
            .putString("llm_apikey", config.apiKey)
            .putString("llm_endpoint", config.cleanBaseUrl())
            .putString("llm_model", config.model)
            .putString("llm_target_lang", config.targetLang)
            .apply()
    }

    // E-Ink Mode state (synchronized across app)
    fun isEinkMode(): Boolean = prefs.getBoolean("ai_eink_mode", false) || appPrefs.getBoolean("eink_mode", false)
    fun setEinkMode(enabled: Boolean) {
        prefs.edit().putBoolean("ai_eink_mode", enabled).apply()
        appPrefs.edit().putBoolean("eink_mode", enabled).apply()
    }

    // Text Size (sp)
    fun getSummaryTextSize(): Float = prefs.getFloat("ai_summary_text_size", 16f)
    fun setSummaryTextSize(size: Float) = prefs.edit().putFloat("ai_summary_text_size", size).apply()

    // Global AI Language Display Mode ("bilingual", "orig", "target")
    fun getLanguageDisplayMode(): String = prefs.getString("ai_language_display_mode", "bilingual") ?: "bilingual"
    fun setLanguageDisplayMode(mode: String) = prefs.edit().putString("ai_language_display_mode", mode).apply()

    // Summary Cache (Strict bookId + scope + ratio + level + mode key)
    fun getSummary(bookId: String, chapterIndex: Int, scope: String, ratio: Int, level: String = "standard", mode: String = "bilingual"): AiSummaryResult? {
        val primaryKey = if (scope == "book") {
            "summary_${bookId}_book_${ratio}_${level}_${mode}"
        } else {
            "summary_${bookId}_ch_${chapterIndex}_${ratio}_${level}_${mode}"
        }
        var json = prefs.getString(primaryKey, null)
        if (json == null) {
            val fallbackKey = if (scope == "book") {
                "summary_${bookId}_book_${ratio}_${level}"
            } else {
                "summary_${bookId}_ch_${chapterIndex}_${ratio}_${level}"
            }
            json = prefs.getString(fallbackKey, null)
        }
        if (json == null && level == "standard") {
            // Fallback for legacy cache format
            val legacyKey = "summary_${bookId}_${chapterIndex}_${scope}_${ratio}"
            json = prefs.getString(legacyKey, null)
        }
        if (json == null) return null
        return try {
            val res = gson.fromJson(json, AiSummaryResult::class.java) ?: return null
            res.copy(
                title = res.title ?: "",
                level = if (res.level.isNullOrBlank()) level else res.level,
                mode = if (res.mode.isNullOrBlank()) mode else res.mode,
                paragraphs = (res.paragraphs ?: emptyList()).map { p ->
                    p.copy(
                        original = p.original ?: "",
                        translation = p.translation ?: ""
                    )
                },
                rawMarkdown = res.rawMarkdown ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveSummary(summary: AiSummaryResult) {
        val mode = if (summary.mode.isNullOrBlank()) "bilingual" else summary.mode
        val key = if (summary.scope == "book") {
            "summary_${summary.bookId}_book_${summary.ratio}_${summary.level}_${mode}"
        } else {
            "summary_${summary.bookId}_ch_${summary.chapterIndex}_${summary.ratio}_${summary.level}_${mode}"
        }
        prefs.edit().putString(key, gson.toJson(summary)).apply()
    }

    // Plot Cache
    fun getPlot(bookId: String, chapterIndex: Int, scope: String): AiPlotResult? {
        val primaryKey = if (scope == "book") {
            "plot_${bookId}_book"
        } else {
            "plot_${bookId}_ch_${chapterIndex}"
        }
        var json = prefs.getString(primaryKey, null)
        if (json == null && scope != "book") {
            val legacyKey = "plot_${bookId}_${chapterIndex}_${scope}"
            json = prefs.getString(legacyKey, null)
        }
        if (json == null) return null
        return try {
            val res = gson.fromJson(json, AiPlotResult::class.java) ?: return null
            res.copy(
                coreDynamicsOriginal = res.coreDynamicsOriginal ?: "",
                coreDynamicsTranslation = res.coreDynamicsTranslation ?: "",
                characters = res.characters ?: emptyList(),
                timeline = res.timeline ?: emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun savePlot(plot: AiPlotResult) {
        val key = if (plot.scope == "book") {
            "plot_${plot.bookId}_book"
        } else {
            "plot_${plot.bookId}_ch_${plot.chapterIndex}"
        }
        prefs.edit().putString(key, gson.toJson(plot)).apply()
    }

    // Quiz Cache
    fun getQuiz(bookId: String, chapterIndex: Int, scope: String, count: Int, difficulty: String): AiQuizResult? {
        val primaryKey = if (scope == "book") {
            "quiz_${bookId}_book_${count}_${difficulty}"
        } else {
            "quiz_${bookId}_ch_${chapterIndex}_${count}_${difficulty}"
        }
        var json = prefs.getString(primaryKey, null)
        if (json == null && scope != "book") {
            val legacyKey = "quiz_${bookId}_${chapterIndex}_${scope}_${count}_${difficulty}"
            json = prefs.getString(legacyKey, null)
        }
        if (json == null) return null
        return try {
            val res = gson.fromJson(json, AiQuizResult::class.java) ?: return null
            res.copy(
                questions = res.questions ?: emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveQuiz(quiz: AiQuizResult) {
        val key = if (quiz.scope == "book") {
            "quiz_${quiz.bookId}_book_${quiz.count}_${quiz.difficulty}"
        } else {
            "quiz_${quiz.bookId}_ch_${quiz.chapterIndex}_${quiz.count}_${quiz.difficulty}"
        }
        prefs.edit().putString(key, gson.toJson(quiz)).apply()
    }

    // Quiz Reports History (per bookId, max 50 items)
    fun getQuizReports(bookId: String): List<QuizReportRecord> {
        val key = "reports_$bookId"
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<QuizReportRecord>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addQuizReport(report: QuizReportRecord) {
        val current = getQuizReports(report.bookId).toMutableList()
        current.add(0, report)
        if (current.size > 50) {
            current.removeAt(current.size - 1)
        }
        val key = "reports_${report.bookId}"
        prefs.edit().putString(key, gson.toJson(current)).apply()
    }
}
