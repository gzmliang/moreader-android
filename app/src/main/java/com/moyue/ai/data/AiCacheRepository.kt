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
                    model = fallbackModel
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

    // Summary Cache (Strict bookId + chapterIndex + scope + ratio key)
    fun getSummary(bookId: String, chapterIndex: Int, scope: String, ratio: Int): AiSummaryResult? {
        val key = "summary_${bookId}_${chapterIndex}_${scope}_${ratio}"
        val json = prefs.getString(key, null) ?: return null
        return try {
            val res = gson.fromJson(json, AiSummaryResult::class.java) ?: return null
            res.copy(
                title = res.title ?: "",
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
        val key = "summary_${summary.bookId}_${summary.chapterIndex}_${summary.scope}_${summary.ratio}"
        prefs.edit().putString(key, gson.toJson(summary)).apply()
    }

    // Plot Cache
    fun getPlot(bookId: String, chapterIndex: Int, scope: String): AiPlotResult? {
        val key = "plot_${bookId}_${chapterIndex}_${scope}"
        val json = prefs.getString(key, null) ?: return null
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
        val key = "plot_${plot.bookId}_${plot.chapterIndex}_${plot.scope}"
        prefs.edit().putString(key, gson.toJson(plot)).apply()
    }

    // Quiz Cache
    fun getQuiz(bookId: String, chapterIndex: Int, scope: String, count: Int, difficulty: String): AiQuizResult? {
        val key = "quiz_${bookId}_${chapterIndex}_${scope}_${count}_${difficulty}"
        val json = prefs.getString(key, null) ?: return null
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
        val key = "quiz_${quiz.bookId}_${quiz.chapterIndex}_${quiz.scope}_${quiz.count}_${quiz.difficulty}"
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
