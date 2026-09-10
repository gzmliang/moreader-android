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
        return if (json != null) {
            try {
                gson.fromJson(json, AiConfig::class.java)
            } catch (e: Exception) {
                AiConfig()
            }
        } else {
            AiConfig()
        }
    }

    fun saveAiConfig(config: AiConfig) {
        prefs.edit().putString("ai_config_json", gson.toJson(config)).apply()
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
            gson.fromJson(json, AiSummaryResult::class.java)
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
            gson.fromJson(json, AiPlotResult::class.java)
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
            gson.fromJson(json, AiQuizResult::class.java)
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
