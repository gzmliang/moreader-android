package com.moyue.app.localai

object LlamaJniWrapper {
    init { System.loadLibrary("llama-local-ai") }

    external fun initLogs()
    external fun getLogs(): String
    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun freeModel(handle: Long)

    /** mode: 0=EN→CN, 1=CN→EN, 2=Chat. Returns "result\n\n---\nstats" */
    external fun generate(handle: Long, text: String, mode: Int, maxTokens: Int): String
}
