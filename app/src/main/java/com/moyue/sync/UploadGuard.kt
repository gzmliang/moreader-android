package com.moyue.app.sync

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 上传体包装：边传边回调真实进度（已传字节 / 总字节）。
 *
 * 以前 App 在「上传中」阶段进度条永远停在 0%（写死的），用户根本分不清
 * 是「在慢慢传」还是「已经卡死」——2026-09-29 抓包实测：手机把请求头发过来、
 * 传了 94KB 后链路停住，界面上看不出任何变化，只能靠猜。
 */
class UploadProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress(sent, total)
            }
        }
        onProgress(0L, total)
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

/**
 * 上传看门狗：只要「还有字节在推进」就一路放行；一旦卡住超过 [stallTimeoutMs]
 * 没有任何字节推进，就回调 [onAbort]（用来 cancel 掉 OkHttp 的 Call），
 * 让上层换线路/重连再来一次，而不是傻等 10 分钟。
 *
 * 时间来源和轮询间隔都可注入，方便用 JVM 单测覆盖，不依赖真实网络。
 */
class UploadStallWatchdog(
    private val stallTimeoutMs: Long,
    private val onAbort: () -> Unit,
    private val pollMs: Long = 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lastProgressAt = AtomicLong(0L)
    private val stalledFlag = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    /** 每次有字节推进时调用（由 [UploadProgressRequestBody] 回调驱动） */
    fun progress() {
        lastProgressAt.set(now())
    }

    /** 本次上传是否是因为「卡死」被中止的（用于决定要不要重试/换线） */
    fun stalled(): Boolean = stalledFlag.get()

    fun start() {
        progress()
        if (thread != null || finished.get()) return
        thread = Thread({
            while (!finished.get()) {
                try {
                    Thread.sleep(pollMs)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (finished.get()) return@Thread
                val idle = now() - lastProgressAt.get()
                if (idle > stallTimeoutMs) {
                    if (stalledFlag.compareAndSet(false, true)) {
                        runCatching { onAbort() }
                    }
                    return@Thread
                }
            }
        }, "pdf-upload-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        finished.set(true)
        thread?.interrupt()
        thread = null
    }

    companion object {
        /** 默认容忍时长：卡住 20 秒没有一字节推进就判定链路掉线 */
        const val DEFAULT_STALL_TIMEOUT_MS = 20_000L
    }
}
