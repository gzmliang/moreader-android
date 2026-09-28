package com.moyue.app.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moyue.app.R
import com.moyue.app.data.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * PDF → 精读本 后台任务管理器
 *
 * 为什么要有它：扫描本 OCR 一本 160 页的书要 7~8 分钟。以前是「手机发一个 HTTP 请求
 * 死等结果」，大书必然超时。现在改成：
 *   1. 上传 PDF → 服务端建后台任务（立刻返回 job_id，排队执行）；
 *   2. App 每 2.5 秒查一次进度（真实百分比 + 页数 + 预计剩余时间）；
 *   3. 用户可以关掉弹窗继续看书 —— 转换在服务器上照跑；
 *   4. 跑完自动下载 EPUB 并入库，弹通知 / Toast 告诉用户「可以去看了」；
 *   5. App 被杀掉再打开，会自动凭服务端任务列表接上，不会白等一场。
 *
 * 状态挂在进程级 object 上（不是 ViewModel），所以切页面、转屏都不会丢。
 */
object PdfImportManager {

    enum class Phase { PENDING, UPLOADING, CONVERTING, DOWNLOADING, IMPORTING, DONE, FAILED, CANCELED }

    data class Entry(
        val localId: String,
        val fileName: String,
        val title: String,
        val phase: Phase,
        val jobId: String? = null,
        val percent: Int = 0,
        val stage: String = "",
        val pagesDone: Int = 0,
        val pagesTotal: Int = 0,
        val etaSeconds: Int = 0,
        val errorCode: String? = null,
        val message: String? = null,
        val bookId: String? = null,
        val chapters: Int = 0,
        val chars: Int = 0,
    ) {
        val isFinished: Boolean
            get() = phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELED
    }

    data class State(
        val entries: List<Entry> = emptyList(),
        val dialogVisible: Boolean = false,
        val focusLocalId: String? = null,
    ) {
        /** 当前正在处理的一条（本地队列是串行的） */
        val active: Entry? get() = entries.firstOrNull { !it.isFinished }

        /** 弹窗要展示的一条：用户点开的那条 > 正在跑的 > 最后一条 */
        val focus: Entry?
            get() = entries.firstOrNull { it.localId == focusLocalId }
                ?: active
                ?: entries.lastOrNull()

        val queuedCount: Int get() = entries.count { !it.isFinished }
        val busy: Boolean get() = active != null
    }

    private const val PREF = "moreader_pdf_job"
    private const val KEY_IMPORTED = "imported_job_ids"
    private const val KEY_JOBS = "pending_jobs"           // JSON: [{"jobId","file","title"}]
    private const val MAX_ENTRIES = 5
    private const val POLL_MS = 2500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var contextRef: Context? = null
    private var repository: BookRepository? = null
    /** 本地串行闸门：多选好几本时也一本一本来，不把服务器 OCR 压垮 */
    private val localQueue = Mutex()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 文件名 → 可读书名。
     *
     * 实测 z-library / libgen 下载的扫描本文件名长这样：
     * `isbn_9787530751046_Author_Unknown_z_library_sk,_1lib_sk,_z_li.pdf`，
     * 直接当书名很难看，所以把站点水印词 / ISBN / 后缀名这类 token 剔掉。
     * 万一剔光了（整串全是水印），宁可回到原名也不要交一个空名给服务端。
     */
    private val PDF_NAME_JUNK = setOf(
        "isbn", "author", "unknown", "z", "library", "zlib", "zlibrary", "1lib", "libgen",
        "sk", "si", "com", "net", "org", "www", "pdf", "epub", "mobi", "azw3",
        "djvu", "txt", "scan", "scanned",
    )

    private fun humanizePdfTitle(rawName: String): String {
        val base = rawName.removeSuffix(".pdf").removeSuffix(".PDF").trim()
        val kept = base.split(Regex("[_\\-\\s,]+")).filter { tk ->
            val low = tk.lowercase()
            low.isNotBlank() && low !in PDF_NAME_JUNK && !Regex("^\\d{9,13}$").matches(low)
        }
        val joined = kept.joinToString(" ").trim().trim(',').trim()
        // 剔完只剩一两个字母（如 z-library 文件名尾巴的 "li"）→ 不如用回原名
        return if (joined.length >= 3) joined else base
    }

    // ── 对外操作 ──────────────────────────────────────────

    /** 用户选了一批 PDF：逐个排队转换（串行，避免同时拖垮服务器 OCR） */
    fun submit(context: Context, uris: List<Uri>, displayNames: List<String>) {
        val ctx = context.applicationContext
        contextRef = ctx
        if (repository == null) repository = BookRepository(ctx)
        val newEntries = uris.mapIndexed { i, uri ->
            Entry(
                localId = UUID.randomUUID().toString(),
                fileName = displayNames.getOrElse(i) { "document.pdf" },
                title = humanizePdfTitle(displayNames.getOrElse(i) { "document.pdf" }),
                phase = Phase.PENDING,
            )
        }
        _state.value = _state.value.let { st ->
            st.copy(
                entries = (st.entries.filter { !it.isFinished } + newEntries).takeLast(MAX_ENTRIES),
                dialogVisible = true,
                focusLocalId = st.focusLocalId ?: newEntries.firstOrNull()?.localId,
            )
        }
        // 本地串行：一次只跑一条（runLocal 内部有队列闸门）
        for ((i, e) in newEntries.withIndex()) {
            scope.launch { runLocal(ctx, e.localId, uris[i], e.title) }
        }
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(dialogVisible = false)
    }

    fun openDialog(localId: String? = null) {
        _state.value = _state.value.copy(dialogVisible = true, focusLocalId = localId ?: _state.value.focusLocalId)
    }

    /** 用户主动取消（正在上传/转换/入库的那条） */
    fun cancel(localId: String) {
        val ctx = contextRef ?: return
        val entry = _state.value.entries.firstOrNull { it.localId == localId } ?: return
        scope.launch {
            entry.jobId?.let { SyncClient(ctx).cancelPdfJob(it) }
            update(localId) { it.copy(phase = Phase.CANCELED, message = null) }
            forgetPending(ctx, entry.jobId)
        }
    }

    /** 清掉已完成/失败/取消的记录（用户点「关闭」时） */
    fun clearFinished() {
        _state.value = _state.value.copy(
            entries = _state.value.entries.filter { !it.isFinished },
            dialogVisible = false,
            focusLocalId = null,
        )
    }

    /**
     * App 启动时调用：接上服务端还没跑完的任务；把「App 关着的时候跑完」的成品补下载入库。
     */
    fun resume(context: Context) {
        val ctx = context.applicationContext
        contextRef = ctx
        if (repository == null) repository = BookRepository(ctx)
        scope.launch {
            val client = SyncClient(ctx)
            if (!client.isLoggedIn()) return@launch
            val jobs = client.listActivePdfJobs().getOrNull() ?: emptyList()
            for (j in jobs) {
                if (_state.value.entries.any { it.jobId == j.id }) continue
                val entry = Entry(
                    localId = UUID.randomUUID().toString(),
                    fileName = j.filename.ifBlank { j.title },
                    title = j.title.ifBlank { j.filename },
                    phase = Phase.CONVERTING,
                    jobId = j.id,
                    percent = j.percent,
                    stage = j.stage,
                    pagesDone = j.pagesDone,
                    pagesTotal = j.pagesTotal,
                )
                _state.value = _state.value.copy(entries = _state.value.entries + entry)
                pollJob(ctx, entry.localId, client, j.id)
            }
            collectFinished(ctx, client)
        }
    }

    // ── 内部实现 ──────────────────────────────────────────

    private suspend fun runLocal(ctx: Context, localId: String, uri: Uri, title: String) {
        val client = SyncClient(ctx)
        localQueue.withLock {
            if (_state.value.entries.firstOrNull { it.localId == localId }?.phase == Phase.CANCELED) return
            try {
                processOne(ctx, client, localId, uri, title)
            } catch (e: Exception) {
                android.util.Log.e("PdfImport", "PDF 后台导入失败", e)
                update(localId) {
                    it.copy(phase = Phase.FAILED, errorCode = "failed", message = e.message)
                }
                notifyResult(ctx, _state.value.entries.firstOrNull { it.localId == localId })
            }
        }
    }

    private suspend fun processOne(
        ctx: Context,
        client: SyncClient,
        localId: String,
        uri: Uri,
        title: String,
    ) {
        if (!client.isLoggedIn()) {
            update(localId) { it.copy(phase = Phase.FAILED, errorCode = "login_required") }
            return
        }
        // 1) 拷到自己的缓存文件（content:// 不能直接当文件上传）
        update(localId) { it.copy(phase = Phase.UPLOADING, percent = 0) }
        val entry0 = _state.value.entries.firstOrNull { it.localId == localId } ?: return
        val safeName = entry0.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val srcFile = File(ctx.cacheDir, "import_$safeName")
        val copied = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                srcFile.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("无法读取所选文件")
        }
        if (copied.isFailure) {
            update(localId) {
                it.copy(phase = Phase.FAILED, errorCode = "failed", message = copied.exceptionOrNull()?.message)
            }
            notifyResult(ctx, _state.value.entries.firstOrNull { it.localId == localId })
            return
        }

        // 2) 投递后台任务
        val created = client.createPdfJob(srcFile, title = title.ifBlank { null })
        runCatching { srcFile.delete() }
        val job = created.getOrElse { e ->
            update(localId) {
                it.copy(
                    phase = Phase.FAILED,
                    errorCode = (e as? SyncClient.PdfConvertException)?.code ?: "failed",
                    message = e.message,
                )
            }
            notifyResult(ctx, _state.value.entries.firstOrNull { it.localId == localId })
            return
        }
        update(localId) {
            it.copy(phase = Phase.CONVERTING, jobId = job.id, percent = job.percent,
                stage = job.stage, pagesDone = job.pagesDone, pagesTotal = job.pagesTotal, title = job.title.ifBlank { it.title })
        }
        savePending(ctx, job.id, entry0.fileName, title, localId)

        // 3) 轮询到结束
        val finalJob = pollJob(ctx, localId, client, job.id) ?: return
        if (finalJob.status == "canceled") {
            update(localId) { it.copy(phase = Phase.CANCELED) }
            forgetPending(ctx, job.id)
            return
        }
        if (finalJob.status != "done") {
            update(localId) {
                it.copy(
                    phase = Phase.FAILED,
                    errorCode = finalJob.code.ifBlank { "failed" },
                    message = finalJob.message,
                )
            }
            forgetPending(ctx, job.id)
            notifyResult(ctx, _state.value.entries.firstOrNull { it.localId == localId })
            return
        }
        downloadAndImport(ctx, client, localId, finalJob)
    }

    /** 轮询任务直到 finished；返回最终状态（出错返回 null） */
    private suspend fun pollJob(
        ctx: Context,
        localId: String,
        client: SyncClient,
        jobId: String,
    ): SyncClient.PdfJobInfo? {
        var consecutiveErrors = 0
        while (true) {
            delay(POLL_MS)
            val st = client.getPdfJob(jobId)
            val job = st.getOrNull()
            if (job == null) {
                consecutiveErrors++
                // 允许网络短暂抖动：连续 40 次（约 100 秒）失败才判定失败
                if (consecutiveErrors >= 40) {
                    update(localId) {
                        it.copy(phase = Phase.FAILED, errorCode = "network",
                            message = st.exceptionOrNull()?.message)
                    }
                    notifyResult(ctx, _state.value.entries.firstOrNull { it.localId == localId })
                    return null
                }
                continue
            }
            consecutiveErrors = 0
            update(localId) {
                it.copy(
                    phase = Phase.CONVERTING,
                    percent = job.percent,
                    stage = job.stage,
                    pagesDone = job.pagesDone,
                    pagesTotal = job.pagesTotal,
                    etaSeconds = job.etaSeconds,
                    title = job.title.ifBlank { it.title },
                    errorCode = if (job.status == "failed") job.code.ifBlank { "failed" } else it.errorCode,
                    message = if (job.status == "failed") job.message else it.message,
                )
            }
            if (job.isFinished) return job
        }
    }

    /** 下载 EPUB → 写入本地书库 → 抽封面 → 通知 */
    private suspend fun downloadAndImport(
        ctx: Context,
        client: SyncClient,
        localId: String,
        job: SyncClient.PdfJobInfo,
    ) {
        update(localId) { it.copy(phase = Phase.DOWNLOADING, percent = 97) }
        val bytes = client.downloadPdfJobResult(job.id).getOrElse { e ->
            update(localId) {
                it.copy(phase = Phase.FAILED,
                    errorCode = (e as? SyncClient.PdfConvertException)?.code ?: "failed",
                    message = e.message)
            }
            notifyResult(ctx, _state.value.entries.firstOrNull { it.localId == localId })
            return
        }
        update(localId) { it.copy(phase = Phase.IMPORTING, percent = 99) }
        try {
            val repo = repository ?: BookRepository(ctx).also { repository = it }
            val epubFile = File(ctx.cacheDir, "converted_${System.currentTimeMillis()}.epub")
            epubFile.writeBytes(bytes)
            val localBook = repo.importEpubFile(epubFile)
            runCatching {
                val coverPath = repo.extractCover(localBook.id)
                if (coverPath != null) repo.updateBookCover(localBook.id, coverPath)
            }
            runCatching { epubFile.delete() }
            update(localId) {
                it.copy(phase = Phase.DONE, percent = 100, bookId = localBook.id,
                    title = localBook.title, chapters = job.chapters, chars = job.chars)
            }
            markImported(ctx, job.id)
        } catch (e: Exception) {
            android.util.Log.e("PdfImport", "入库失败", e)
            update(localId) {
                it.copy(phase = Phase.FAILED, errorCode = "import_failed", message = e.message)
            }
        }
        forgetPending(ctx, job.id)
        notifyResult(ctx, _state.value.entries.firstOrNull { it.localId == localId })
    }

    /** App 启动/回到前台时，把「App 不在的时候跑完」的任务补下载入库 */
    private suspend fun collectFinished(ctx: Context, client: SyncClient) {
        val jobs = client.listRecentPdfJobs().getOrNull() ?: return
        for (j in jobs) {
            if (j.status != "done") continue
            if (_state.value.entries.any { it.jobId == j.id }) continue
            if (isImported(ctx, j.id)) continue
            val entry = Entry(
                localId = UUID.randomUUID().toString(),
                fileName = j.filename.ifBlank { j.title },
                title = j.title.ifBlank { j.filename },
                phase = Phase.CONVERTING,
                jobId = j.id,
                percent = 100,
                stage = "done",
            )
            _state.value = _state.value.copy(entries = _state.value.entries + entry)
            downloadAndImport(ctx, client, entry.localId, j)
        }
    }

    private fun update(localId: String, block: (Entry) -> Entry) {
        _state.value = _state.value.let { st ->
            st.copy(entries = st.entries.map { if (it.localId == localId) block(it) else it })
        }
    }

    // ── 完成通知 ──────────────────────────────────────────

    private fun notifyResult(ctx: Context, entry: Entry?) {
        if (entry == null) return
        val text = when (entry.phase) {
            Phase.DONE -> ctx.getString(R.string.pdf_notify_done_body, entry.title)
            Phase.FAILED -> ctx.getString(R.string.pdf_notify_failed_body, entry.title)
            else -> return
        }
        runCatching {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.pdf_notify_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pending = launch?.let {
                PendingIntent.getActivity(
                    ctx, entry.localId.hashCode(), it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(ctx.getString(R.string.pdf_convert_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .apply { pending?.let { setContentIntent(it) } }
                .build()
            // 没有通知权限时这里会静默失败（不弹也无所谓，回到书架能看到新书）
            try {
                NotificationManagerCompat.from(ctx).notify(entry.localId.hashCode(), notif)
            } catch (se: SecurityException) {
                android.util.Log.w("PdfImport", "无通知权限，已跳过通知", se)
            }
        }
    }

    private const val CHANNEL_ID = "moreader_pdf_job"

    // ── 持久化（防进程被杀） ──────────────────────────────

    private fun savePending(ctx: Context, jobId: String, fileName: String, title: String, localId: String) {
        val p = prefs(ctx)
        val arr = runCatching { org.json.JSONArray(p.getString(KEY_JOBS, "[]")) }.getOrElse { org.json.JSONArray() }
        arr.put(org.json.JSONObject().apply {
            put("jobId", jobId); put("file", fileName); put("title", title); put("localId", localId)
        })
        p.edit().putString(KEY_JOBS, arr.toString()).apply()
    }

    private fun forgetPending(ctx: Context, jobId: String?) {
        if (jobId == null) return
        val p = prefs(ctx)
        val arr = runCatching { org.json.JSONArray(p.getString(KEY_JOBS, "[]")) }.getOrElse { org.json.JSONArray() }
        val out = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("jobId") != jobId) out.put(o)
        }
        p.edit().putString(KEY_JOBS, out.toString()).apply()
    }

    private fun markImported(ctx: Context, jobId: String) {
        val p = prefs(ctx)
        val arr = runCatching { org.json.JSONArray(p.getString(KEY_IMPORTED, "[]")) }.getOrElse { org.json.JSONArray() }
        arr.put(jobId)
        while (arr.length() > 50) {
            val trimmed = org.json.JSONArray()
            for (i in arr.length() - 50 until arr.length()) trimmed.put(arr.get(i))
            p.edit().putString(KEY_IMPORTED, trimmed.toString()).apply()
            return
        }
        p.edit().putString(KEY_IMPORTED, arr.toString()).apply()
    }

    private fun isImported(ctx: Context, jobId: String): Boolean {
        val arr = runCatching { org.json.JSONArray(prefs(ctx).getString(KEY_IMPORTED, "[]")) }
            .getOrElse { org.json.JSONArray() }
        for (i in 0 until arr.length()) if (arr.optString(i) == jobId) return true
        return false
    }
}
