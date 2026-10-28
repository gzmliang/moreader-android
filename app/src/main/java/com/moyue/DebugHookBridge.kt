package com.moyue

import android.content.Context

/** 调试入口挂载点：只在 debuggable 应用中通过反射调用 debugtools；正式包找不到该类，直接跳过。 */
object DebugHookBridge {
    fun onCreate(context: Context) {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        try {
            val c = Class.forName("com.moyue.debugtools.DebugBridgeImpl")
            val inst = c.getField("INSTANCE").get(null)
            c.getMethod("attach", Context::class.java).invoke(inst, context)
        } catch (t: Throwable) { android.util.Log.e("DEBUG-HOOK", "attach failed", t) }
    }
}
