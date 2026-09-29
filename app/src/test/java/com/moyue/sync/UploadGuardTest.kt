package com.moyue.app.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * PDF 上传「真实进度 + 卡死探测」的字节级验证（不依赖手机、不依赖真实服务器）。
 *
 * 覆盖 2026-09-29 抓包实测到的现场：手机把请求头发过来、传了 94KB 后链路停住，
 * 界面 0% 不动、用户只能靠猜。这里用本地最小 HTTP 服务器把三种情况都跑一遍：
 *   1) 正常上传 → 进度必须一路走到「已传字节 == 总字节」，服务器收到完整正文；
 *   2) 服务器接了连接却不读（接收窗口收 0）→ 看门狗必须在容忍时间内中止，并标记为「卡死」；
 *   3) 服务器读得慢但在读 → 不能误判成卡死。
 */
class UploadGuardTest {

    private enum class Mode { FULL, NEVER_READ, SLOW_READ }

    private class MiniHttpServer(
        private val mode: Mode,
        /** 服务端接收缓冲（字节）；设小一点就能模拟「链路很慢但一直在动」 */
        private val receiveBuffer: Int = 0,
    ) : AutoCloseable {
        val server = ServerSocket(0).apply { if (receiveBuffer > 0) setReceiveBufferSize(receiveBuffer) }
        val bodyBytesRead = AtomicLong(0)
        @Volatile var requestsSeen = 0

        fun start() {
            Thread {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (e: Exception) { return@Thread }
                    val w = Thread { serve(s) }
                    w.isDaemon = true
                    w.start()
                }
            }.apply { isDaemon = true; start() }
        }

        private fun serve(s: Socket) {
            try {
                requestsSeen++
                if (mode == Mode.NEVER_READ) {
                    // 连上了，但一个字都不读：客户端发送缓冲写满后就会卡住
                    Thread.sleep(30_000)
                    return
                }
                val input = s.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return
                    header.append(b.toChar())
                    if (header.length > 65536) return
                }
                val len = Regex("(?i)content-length:\\s*(\\d+)")
                    .find(header.toString())?.groupValues?.get(1)?.toLong() ?: return
                var read = 0L
                val buf = ByteArray(256 * 1024)
                while (read < len) {
                    val n = input.read(buf)
                    if (n < 0) break
                    read += n
                    bodyBytesRead.set(read)
                }
                val resp = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: 12\r\nConnection: close\r\n\r\n{\"job\":\"x\"}").toByteArray()
                s.getOutputStream().write(resp)
                s.getOutputStream().flush()
            } catch (e: Exception) {
                // 客户端取消会直接断开，这里忽略
            } finally {
                runCatching { s.close() }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun tempPdf(mb: Int): File =
        File.createTempFile("guard-test", ".pdf").apply {
            deleteOnExit()
            val chunk = ByteArray(1024 * 1024)
            outputStream().use { out -> repeat(mb) { out.write(chunk) } }
        }

    private fun client(writeTimeoutSec: Long = 120) = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(port: Int, file: File, body: UploadProgressRequestBody): Request =
        Request.Builder()
            .url("http://127.0.0.1:$port/sync/pdf/jobs")
            .post(
                MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, body).build()
            )
            .build()

    @Test
    fun uploadReportsFullProgress() {
        MiniHttpServer(Mode.FULL).use { srv ->
            srv.start()
            val file = tempPdf(3)
            val progress = mutableListOf<Pair<Long, Long>>()
            val body = UploadProgressRequestBody(file.asRequestBody("application/pdf".toMediaType())) { sent, total ->
                synchronized(progress) { progress.add(sent to total) }
            }
            assertEquals(file.length(), body.contentLength())

            client().newCall(request(srv.server.localPort, file, body)).execute().use { resp ->
                assertEquals(200, resp.code)
            }

            synchronized(progress) {
                val last = progress.last()
                assertEquals(file.length(), last.first)
                assertEquals(file.length(), last.second)
                assertTrue("进度应逐步上升", progress.size > 2)
                assertTrue("进度必须单调不减", progress.map { it.first }.zipWithNext().all { it.first <= it.second })
            }
            // 服务器读到的是整个 multipart 正文（文件 + 分段头），所以只会多不会少
            assertTrue("服务器应当收到完整上传", srv.bodyBytesRead.get() >= file.length())
        }
    }

    @Test
    fun watchdogAbortsWhenServerNeverReads() {
        MiniHttpServer(Mode.NEVER_READ).use { srv ->
            srv.start()
            val file = tempPdf(16)
            var call: okhttp3.Call? = null
            val stallTimeout = 2000L
            val watchdog = UploadStallWatchdog(stallTimeout, onAbort = { call?.cancel() }, pollMs = 200L)
            val body = UploadProgressRequestBody(file.asRequestBody("application/pdf".toMediaType())) { _, _ ->
                watchdog.progress()
            }
            call = client().newCall(request(srv.server.localPort, file, body))
            watchdog.start()
            val started = System.currentTimeMillis()
            var failed = false
            try {
                call!!.execute().close()
            } catch (e: Exception) {
                failed = true
            } finally {
                watchdog.stop()
            }
            val elapsed = System.currentTimeMillis() - started

            assertTrue("应当因卡住而抛出异常", failed)
            assertTrue("应当被判定为「卡死」", watchdog.stalled())
            assertTrue("必须在容忍时间附近中止，实际 ${elapsed}ms", elapsed < 20_000)
        }
    }

    @Test
    fun slowButMovingUploadIsNotFlaggedAsStalled() {
        // 接收缓冲只有 32KB → 客户端被小窗口拖着慢慢传，但每一点推进都在报进度：不能算卡死
        MiniHttpServer(Mode.SLOW_READ, receiveBuffer = 32 * 1024).use { srv ->
            srv.start()
            val file = tempPdf(8)
            var call: okhttp3.Call? = null
            val watchdog = UploadStallWatchdog(5000L, onAbort = { call?.cancel() }, pollMs = 200L)
            val ticks = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
            val t0 = System.currentTimeMillis()
            val body = UploadProgressRequestBody(file.asRequestBody("application/pdf".toMediaType())) { sent, _ ->
                watchdog.progress()
                ticks.add(System.currentTimeMillis() - t0 to sent)
            }
            call = client().newCall(request(srv.server.localPort, file, body))
            watchdog.start()
            val ok: Boolean
            try {
                ok = call!!.execute().use { it.code == 200 }
            } finally {
                watchdog.stop()
            }
            val span = (ticks.lastOrNull()?.first ?: 0L) - (ticks.firstOrNull()?.first ?: 0L)
            println("slow-window upload: events=${ticks.size} spanMs=$span ok=$ok stalled=${watchdog.stalled()}")
            assertTrue("小窗口但持续推进的上传应当成功", ok)
            assertFalse("不应被误判为卡死", watchdog.stalled())
            assertTrue("进度事件应当持续到传完，实际 ${ticks.size} 次", ticks.size > 10)
            assertTrue("服务器应当收到完整上传", srv.bodyBytesRead.get() >= file.length())
        }
    }

    /** 对真实服务器（159:5001）做一次冒烟：确认新上传体在真实链路上照样按字节报进度 */
    @Test
    fun smokeAgainstLiveServer() {
        val reachable = runCatching {
            val conn = URL("http://127.0.0.1:5001/sync/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.responseCode == 200
        }.getOrDefault(false)
        assumeTrue("159:5001 服务没跑，跳过冒烟测试", reachable)

        val file = tempPdf(2)
        val maxSent = AtomicLong(0)
        val body = UploadProgressRequestBody(file.asRequestBody("application/pdf".toMediaType())) { sent, _ ->
            maxSent.updateAndGet { maxOf(it, sent) }
        }
        // 未带 token → 服务端会先回 401（早期断流也属于正常，重点是字节真的发出去了）
        runCatching { client(30).newCall(request(5001, file, body)).execute().use { it.code } }
        assertTrue("应当统计到已上传的字节", maxSent.get() > 0)
    }
}
