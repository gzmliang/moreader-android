package com.moyue.debugtools

import android.content.Context

object DebugBridgeImpl {
    fun attach(context: Context) { DebugReaderHook.register(context) }
}
