package com.moyue.app.localai

object LlamaJniWrapper {
    @Volatile
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("llama-local-ai")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLoaded

    external fun initLogs()
    external fun getLogs(): String
    external fun clearLogs()
    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    external fun freeModel(handle: Long)

    /** mode: 0=EN→CN, 1=CN→EN, 2=Chat. Returns "result\n\n---\nstats" */
    external fun generate(handle: Long, text: String, mode: Int, maxTokens: Int): String
}
