package com.moyue.app.localai

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Local AI inference engine — drop-in replacement for TranslationService.
 * Uses llama.cpp (CPU-only or Vulkan GPU, Qwen2.5 GGUF).
 * Thread-safe, model persists in app cache.
 *
 * Usage:
 *   LocalAiEngine.init(context)                    // at app start
 *   LocalAiEngine.loadModel(context, uri)           // user selects GGUF file
 *   LocalAiEngine.translate(text, "translate")      // auto-detect EN↔CN
 *   LocalAiEngine.chat(text)                        // general Q&A
 */
object LocalAiEngine {

    private const val TAG = "LocalAiEngine"
    private const val PREFS = "localai_config"
    private const val KEY_MODEL_PATH = "model_path"
    private const val KEY_LOADED = "loaded"
    private const val KEY_GPU_LAYERS = "gpu_layers"

    private val modelDir = "localai_models"
    private var modelHandle: Long = 0
    private var isInitialized = false

    /** Get current GPU layer count (persisted) */
    fun getGpuLayers(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_GPU_LAYERS, 0)
    }

    /** Set GPU layer count and persist */
    fun setGpuLayers(context: Context, layers: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_GPU_LAYERS, layers).apply()
    }

    /** Call once at app start. Restores previously loaded model if available. */
    fun init(context: Context): Boolean {
        if (isInitialized) return modelHandle != 0L
        isInitialized = true

        LlamaJniWrapper.initLogs()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modelPath = prefs.getString(KEY_MODEL_PATH, null)
            ?: return false

        val file = File(modelPath)
        if (!file.exists()) {
            Log.w(TAG, "Cached model path no longer exists: $modelPath")
            return false
        }

        return loadModelSync(context, modelPath)
    }

    /** Load model from a URI (content:// or file://). Copies to app storage, then loads. */
    suspend fun loadModelFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Extract filename
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"

            // Copy to app-private directory (persistent across cache clears)
            val destDir = File(context.filesDir, modelDir)
            destDir.mkdirs()

            // Remove old model files (*.gguf) to save space
            destDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".gguf") && f.name != fileName) {
                    f.delete()
                    Log.d(TAG, "Deleted old model: ${f.name}")
                }
            }

            val destFile = File(destDir, fileName)
            if (destFile.exists()) destFile.delete()

            // Copy
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Cannot open file"))

            val sizeMB = destFile.length() / (1024 * 1024)
            Log.d(TAG, "Copied model: ${destFile.name} (${sizeMB}MB)")

            // Load
            val success = loadModelSync(context, destFile.absolutePath)
            if (success) {
                Result.success("Loaded: ${destFile.name} (${sizeMB}MB)")
            } else {
                Result.failure(Exception("Model load failed. Check logs."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Release model and free memory */
    fun releaseModel(context: Context) {
        if (modelHandle != 0L) {
            LlamaJniWrapper.freeModel(modelHandle)
            modelHandle = 0L
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LOADED, false).apply()
            Log.d(TAG, "Model released")
        }
    }

    /** Reload model with current GPU setting. Use instead of init() after release. */
    fun reloadModel(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modelPath = prefs.getString(KEY_MODEL_PATH, null) ?: return false
        val file = File(modelPath)
        if (!file.exists()) return false
        // Don't reset isInitialized — just reload the model
        return loadModelSync(context, modelPath)
    }

    /** Check if model is loaded and ready */
    fun isReady(): Boolean = modelHandle != 0L

    /** Get model name */
    fun getModelName(context: Context): String {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_PATH, "") ?: ""
        return File(path).name.ifEmpty { "No model loaded" }
    }

    /** Translate text. Auto-detects EN→CN or CN→EN based on input. */
    suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (modelHandle == 0L) {
            return@withContext Result.failure(Exception("Model not loaded"))
        }
        try {
            // Auto-detect language
            val hasChinese = text.any { it in '\u4e00'..'\u9fff' }
            val mode = if (hasChinese) 1 else 0  // 0=EN→CN, 1=CN→EN
            val maxTokens = 2048  // Allow longer translation output
            val result = LlamaJniWrapper.generate(modelHandle, text, mode, maxTokens)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Chat / Q&A mode */
    suspend fun chat(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (modelHandle == 0L) {
            return@withContext Result.failure(Exception("Model not loaded"))
        }
        try {
            val result = LlamaJniWrapper.generate(modelHandle, text, 2, 2048)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Get all logs for debugging */
    fun getLogs(): String = LlamaJniWrapper.getLogs()

    /** Clear the log buffer */
    fun clearLogs() = LlamaJniWrapper.clearLogs()

    // ---- Private ----

    private fun loadModelSync(context: Context, path: String): Boolean {
        try {
            val gpuLayers = getGpuLayers(context)
            modelHandle = LlamaJniWrapper.loadModel(path, 4096, 8, gpuLayers)
            if (modelHandle != 0L) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_MODEL_PATH, path)
                    .putBoolean(KEY_LOADED, true)
                    .apply()
                Log.d(TAG, "Model loaded: $path (GPU=$gpuLayers)")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModelSync failed", e)
        }
        return false
    }
}
