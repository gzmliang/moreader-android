package com.moyue.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * 仅 Debug 包生效（位于 src/debug，正式包不会编译进来）。
 * 让 adb 能驱动阅读器：开始/暂停朗读、跳章、开关横屏等，供模拟器自动化验证。
 * 用法示例：adb shell am broadcast -a com.moyue.DEBUG --es cmd play
 */
object DebugReaderHook {
    const val ACTION = "com.moyue.DEBUG"
    private var handler: ((String, String?) -> Unit)? = null
    fun setHandler(h: (String, String?) -> Unit) { handler = h }

    fun register(context: Context) {
        val app = context.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: return
                val arg = intent.getStringExtra("arg")
                Log.d("DEBUG-HOOK", "cmd=$cmd arg=$arg")
                handler?.invoke(cmd, arg)
            }
        }, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
    }
}
