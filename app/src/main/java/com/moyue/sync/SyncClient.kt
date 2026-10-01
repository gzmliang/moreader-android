package com.moyue.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 墨阅云端书库 + 进度同步客户端
 */
class SyncClient(private val context: Context) {

    companion object {
        private const val PREF_NAME = "moreader_sync"
        private const val KEY_SERVER = "sync_server"
        private const val KEY_TOKEN = "sync_token"
        private const val KEY_EMAIL = "sync_email"
        private const val KEY_PASSWORD = "sync_password"
        private const val DEFAULT_SERVER = "http://p-plus.duckdns.org:5001"
        private const val FALLBACK_SERVER = "http://powerplus.blogsyte.com:5001"

        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun getCandidateServerUrls(): List<String> {
        val current = getServerUrl().trimEnd('/')
        return if (current == DEFAULT_SERVER || current == FALLBACK_SERVER) {
            listOf(DEFAULT_SERVER, FALLBACK_SERVER)
        } else {
            listOf(current)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 大文件专用（PDF 上传 / 转换等待可达数十分钟，普通 30s 超时不够用） */
    private val longClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        // 写超时只管「单次写阻塞」：正常上传里每一小段都在动，不会误杀；
        // 服务端不读（接收窗口收 0）时 60 秒内必失败，交给看门狗/换线逻辑救。
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── 认证状态 ──────────────────────────────────────

    fun getServerUrl(): String = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)
    fun getSavedPassword(): String? = prefs.getString(KEY_PASSWORD, null)
    fun isLoggedIn(): Boolean = getToken() != null || (!getEmail().isNullOrBlank() && !getSavedPassword().isNullOrBlank())

    fun saveLogin(token: String, email: String, password: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email)
        if (password != null) {
            editor.putString(KEY_PASSWORD, password)
        }
        editor.apply()
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EMAIL)
            .remove(KEY_PASSWORD)
            .apply()
    }

    // ── HTTP 辅助 ─────────────────────────────────────

    private suspend fun api(
        method: String,
        path: String,
        body: String? = null,
        auth: Boolean = true,
        canRetryAuth: Boolean = true,
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        for (server in getCandidateServerUrls()) {
            try {
                val url = server.trimEnd('/') + path
                val req = Request.Builder().url(url).method(method, body?.toRequestBody(JSON_MEDIA))
                if (auth) {
                    var token = getToken()
                    if (token == null) {
                        // 尝试用保存的密码静默重新登录
                        val email = getEmail()
                        val pass = getSavedPassword()
                        if (!email.isNullOrBlank() && !pass.isNullOrBlank()) {
                            val reloginResult = login(email, pass)
                            token = reloginResult.getOrNull()
                        }
                    }
                    if (token == null) return@withContext Result.failure(Exception("未登录"))
                    req.addHeader("Authorization", "Bearer $token")
                }
                val resp = client.newCall(req.build()).execute()
                val respBody = resp.body?.string() ?: ""

                // 如果遇到 401 Unauthorized 且支持重试，尝试静默重新登录一次
                if (resp.code == 401 && auth && canRetryAuth) {
                    val email = getEmail()
                    val pass = getSavedPassword()
                    if (!email.isNullOrBlank() && !pass.isNullOrBlank()) {
                        val relogin = login(email, pass)
                        if (relogin.isSuccess) {
                            return@withContext api(method, path, body, auth, canRetryAuth = false)
                        }
                    }
                }

                if (resp.isSuccessful) {
                    return@withContext Result.success(respBody)
                } else if (resp.code in 500..599) {
                    lastException = Exception("HTTP ${resp.code}: $respBody")
                    continue
                } else {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $respBody"))
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        Result.failure(lastException ?: Exception("Network request failed"))
    }

    // ── 登录 ──────────────────────────────────────────

    suspend fun login(email: String, password: String): Result<String> {
        val body = """{"email":"$email","password":"$password"}"""
        val result = api("POST", "/sync/auth/login", body, auth = false, canRetryAuth = false)
        return result.map { json ->
            val obj = JSONObject(json)
            val token = obj.getString("token")
            saveLogin(token, email, password)
            token
        }
    }

    // ── 书库 ──────────────────────────────────────────

    data class BookInfo(
        val id: Int,
        val title: String,
        val author: String,
        val fileSize: Long,
        val createdAt: String,
        val hasCover: Boolean = false,
    )

    suspend fun listBooks(): Result<List<BookInfo>> {
        return api("GET", "/sync/books").map { json ->
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                BookInfo(
                    id = b.getInt("id"),
                    title = b.optString("title", ""),
                    author = b.optString("author", ""),
                    fileSize = b.optLong("file_size", 0),
                    createdAt = b.optString("created_at", ""),
                    hasCover = b.optBoolean("has_cover", false),
                )
            }
        }
    }

    /** 云端书库封面小图（带本地磁盘缓存，命中即秒开） */
    fun coverCacheFile(bookId: Int): File =
        File(File(context.cacheDir, "cloud_covers").apply { mkdirs() }, "$bookId.jpg")

    suspend fun fetchBookCover(bookId: Int, force: Boolean = false): ByteArray? = withContext(Dispatchers.IO) {
        val cacheFile = coverCacheFile(bookId)
        if (!force && cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext runCatching { cacheFile.readBytes() }.getOrNull()
        }
        var lastException: Exception? = null
        for (server in getCandidateServerUrls()) {
            try {
                val url = "${server.trimEnd('/')}/sync/books/$bookId/cover"
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        runCatching { cacheFile.writeBytes(bytes) }
                        return@withContext bytes
                    }
                    return@withContext null
                }
                if (resp.code in 500..599) {
                    lastException = Exception("HTTP ${resp.code}")
                    continue
                }
                return@withContext null
            } catch (e: Exception) {
                lastException = e
            }
        }
        Log.w("Sync", "取封面失败 book=$bookId", lastException)
        null
    }

    class PdfConvertException(val code: String, val serverDetail: String) : Exception(serverDetail)

    /** 后台转换任务状态（服务端 pdf_jobs） */
    data class PdfJobInfo(
        val id: String,
        val status: String,      // queued / running / done / failed / canceled
        val stage: String,       // queued / probe / extract / ocr / build / done
        val percent: Int,
        val pagesDone: Int,
        val pagesTotal: Int,
        val etaSeconds: Int,
        val message: String,
        val code: String,
        val filename: String,
        val title: String,
        val author: String,
        val chapters: Int,
        val paragraphs: Int,
        val chars: Int,
        val source: String,
        /** AI 纠错结果摘要（L1/L2/L3；0 表示没做或没清到东西） */
        val llmLevel: Int = 0,
        val llmCutLines: Int = 0,
        val llmOcrFixes: Int = 0,
        /** 没清掉的：AI 提了但本地拿不出证据，最后没动的处数（用来提示升档重跑） */
        val llmUncleared: Int = 0,
    ) {
        val isFinished: Boolean get() = status == "done" || status == "failed" || status == "canceled"

        companion object {
            fun from(o: JSONObject) = PdfJobInfo(
                id = o.optString("id", ""),
                status = o.optString("status", "queued"),
                stage = o.optString("stage", ""),
                percent = o.optInt("percent", 0),
                pagesDone = o.optInt("pages_done", 0),
                pagesTotal = o.optInt("pages_total", 0),
                etaSeconds = o.optInt("eta_seconds", 0),
                message = o.optString("message", ""),
                code = o.optString("code", ""),
                filename = o.optString("filename", ""),
                title = o.optString("title", ""),
                author = o.optString("author", ""),
                chapters = o.optInt("chapters", 0),
                paragraphs = o.optInt("paragraphs", 0),
                chars = o.optInt("chars", 0),
                source = o.optString("source", ""),
                llmLevel = o.optInt("llm_level", 0),
                llmCutLines = o.optInt("llm_cut_lines", 0),
                llmOcrFixes = o.optInt("llm_ocr_fixes", 0),
                llmUncleared = o.optInt("llm_uncleared", 0),
            )
        }
    }

    private fun parseJob(body: String): PdfJobInfo =
        PdfJobInfo.from(JSONObject(body).getJSONObject("job"))

    /**
     * 投递 PDF 转换后台任务（大书不再受 600 秒等待限制）。
     * 返回任务状态，后续用 [getPdfJob] 轮询，完成后用 [downloadPdfJobResult] 取 EPUB。
     *
     * @param onProgress 真实上传进度回调（已传字节, 总字节），在 OkHttp 写线程上触发。
     * @param stallTimeoutMs 上传中途「连续多久没有任何字节推进」就判定卡死（见 [UploadStallWatchdog]）。
     */
    suspend fun createPdfJob(
        pdfFile: File,
        title: String? = null,
        author: String? = null,
        llmLevel: Int = 0,
        fixOcr: Boolean = false,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
        stallTimeoutMs: Long = UploadStallWatchdog.DEFAULT_STALL_TIMEOUT_MS,
    ): Result<PdfJobInfo> = withContext(Dispatchers.IO) {
        if (!pdfFile.exists()) return@withContext Result.failure(PdfConvertException("missing", "文件不存在"))
        if (!isLoggedIn()) return@withContext Result.failure(PdfConvertException("login_required", "未登录"))

        var lastException: Exception? = null
        var lastWasStall = false
        for (server in getCandidateServerUrls()) {
            // 同一条线路最多试 2 次：上传中途卡死多半是链路抖动（家庭回环 NAT / 手机省电断流），
            // 原样再来一次往往就过去了；两条线路合计最多 4 次，之后才判失败。
            var attempt = 0
            while (attempt < 2) {
                attempt++
                var call: Call? = null
                val watchdog = UploadStallWatchdog(stallTimeoutMs, onAbort = { call?.cancel() })
                try {
                    val url = "${server.trimEnd('/')}/sync/pdf/jobs"
                    val fileBody = pdfFile.asRequestBody("application/pdf".toMediaType())
                    val watchedBody = UploadProgressRequestBody(fileBody) { sent, total ->
                        watchdog.progress()
                        onProgress?.invoke(sent, total)
                    }
                    val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", pdfFile.name, watchedBody)
                    if (!title.isNullOrBlank()) builder.addFormDataPart("title", title)
                    if (!author.isNullOrBlank()) builder.addFormDataPart("author", author)
                    // AI 纠错分级（用户在导入时选）：0=不做 1=L1只审标题 2=L2通读正文 3=L3精读复核
                    // 同时发 llm_chapters（=level>=1）兼容还没升级的服务器
                    builder.addFormDataPart("llm_level", llmLevel.coerceIn(0, 3).toString())
                    builder.addFormDataPart("llm_chapters", if (llmLevel >= 1) "1" else "0")
                    // OCR 修字：单独开关（默认关），只有 L2/L3 才有意义
                    builder.addFormDataPart("fix_ocr", if (fixOcr && llmLevel >= 2) "1" else "0")
                    val req = Request.Builder().url(url)
                        .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                        .post(builder.build())
                        .build()
                    call = longClient.newCall(req)
                    watchdog.start()
                    val resp = call!!.execute()
                    val bodyText = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        return@withContext Result.success(parseJob(bodyText))
                    }
                    val code = runCatching { JSONObject(bodyText).optString("code", "") }.getOrDefault("")
                    val detail = runCatching { JSONObject(bodyText).optString("detail", bodyText) }.getOrDefault(bodyText)
                    if (resp.code == 401) {
                        return@withContext Result.failure(PdfConvertException("login_required", detail))
                    }
                    if (resp.code in 400..499) {
                        return@withContext Result.failure(PdfConvertException(code.ifBlank { "failed" }, detail))
                    }
                    // 5xx：服务端自己的问题，换线路试，不必在同一条线上重来
                    lastException = PdfConvertException(code, "HTTP ${resp.code}: $detail")
                    lastWasStall = false
                    break
                } catch (e: Exception) {
                    lastException = e
                    lastWasStall = watchdog.stalled()
                } finally {
                    watchdog.stop()
                }
            }
        }
        Result.failure(
            when {
                lastWasStall -> PdfConvertException("network_stalled", "upload stalled")
                lastException is PdfConvertException -> lastException
                // 纯网络异常（连不上/读写超时）就给通用网络错误码，不把底层英文报错丢给用户看
                lastException != null -> PdfConvertException("network", lastException!!.message ?: "network")
                else -> PdfConvertException("network", "network unavailable")
            }
        )
    }

    /** 查后台任务进度（App 每 2~3 秒轮询一次） */
    suspend fun getPdfJob(jobId: String): Result<PdfJobInfo> {
        return api("GET", "/sync/pdf/jobs/$jobId", canRetryAuth = true).map { parseJob(it) }
    }

    /** 拉取未完成的任务列表（App 重启后接上没跑完的任务） */
    suspend fun listActivePdfJobs(): Result<List<PdfJobInfo>> {
        return api("GET", "/sync/pdf/jobs?active=1&limit=5").map { json ->
            val arr = JSONObject(json).getJSONArray("jobs")
            (0 until arr.length()).map { PdfJobInfo.from(arr.getJSONObject(it)) }
        }
    }

    /** 最近的任务（含已完成，用于「App 不在时跑完」的补下载） */
    suspend fun listRecentPdfJobs(): Result<List<PdfJobInfo>> {
        return api("GET", "/sync/pdf/jobs?limit=6").map { json ->
            val arr = JSONObject(json).getJSONArray("jobs")
            (0 until arr.length()).map { PdfJobInfo.from(arr.getJSONObject(it)) }
        }
    }

    /** 取消后台任务 */
    suspend fun cancelPdfJob(jobId: String): Result<Unit> {
        return api("POST", "/sync/pdf/jobs/$jobId/cancel").map { }
    }

    /** 下载转换好的 EPUB（任务未完成时服务端返回 409 + code=not_ready） */
    suspend fun downloadPdfJobResult(jobId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        for (server in getCandidateServerUrls()) {
            try {
                val url = "${server.trimEnd('/')}/sync/pdf/jobs/$jobId/result"
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                    .get()
                    .build()
                val resp = longClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        return@withContext Result.failure(PdfConvertException("empty", "转换结果为空"))
                    }
                    return@withContext Result.success(bytes)
                }
                val bodyText = resp.body?.string() ?: ""
                val code = runCatching { JSONObject(bodyText).optString("code", "") }.getOrDefault("")
                val detail = runCatching { JSONObject(bodyText).optString("detail", bodyText) }.getOrDefault(bodyText)
                if (resp.code == 409) {
                    return@withContext Result.failure(PdfConvertException("not_ready", detail))
                }
                if (resp.code == 401) {
                    return@withContext Result.failure(PdfConvertException("login_required", detail))
                }
                if (resp.code in 400..499) {
                    return@withContext Result.failure(PdfConvertException(code.ifBlank { "failed" }, detail))
                }
                lastException = PdfConvertException(code, "HTTP ${resp.code}: $detail")
            } catch (e: Exception) {
                lastException = e
            }
        }
        Result.failure(lastException ?: PdfConvertException("failed", "网络请求失败"))
    }

    data class PdfConvertResult(
        val epub: ByteArray,
        val pages: Int,
        val chapters: Int,
        val paragraphs: Int,
        val chars: Int,
        val title: String,
        val author: String,
    )

    /**
     * TXT → 墨阅流式精读本 EPUB（服务端转换：编码自动识别 + 智能分章）
     * 失败时若服务端给出可识别错误码，抛 PdfConvertException(code = not_text/empty/too_large/login_required)
     */
    suspend fun convertTxtToEpub(
        txtFile: File,
        title: String? = null,
        author: String? = null,
    ): Result<PdfConvertResult> = withContext(Dispatchers.IO) {
        if (!txtFile.exists()) return@withContext Result.failure(PdfConvertException("missing", "文件不存在"))
        if (!isLoggedIn()) return@withContext Result.failure(PdfConvertException("login_required", "未登录"))

        var lastException: Exception? = null
        for (server in getCandidateServerUrls()) {
            try {
                val url = "${server.trimEnd('/')}/sync/txt/convert"
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", txtFile.name,
                        txtFile.asRequestBody("text/plain".toMediaType()),
                    )
                if (!title.isNullOrBlank()) builder.addFormDataPart("title", title)
                if (!author.isNullOrBlank()) builder.addFormDataPart("author", author)
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                    .post(builder.build())
                    .build()
                val resp = longClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        return@withContext Result.failure(PdfConvertException("empty", "转换结果为空"))
                    }
                    val decodedTitle = runCatching {
                        String(android.util.Base64.decode(resp.header("X-Moreader-Title") ?: "", android.util.Base64.DEFAULT))
                    }.getOrDefault(title ?: txtFile.nameWithoutExtension)
                    val decodedAuthor = runCatching {
                        String(android.util.Base64.decode(resp.header("X-Moreader-Author") ?: "", android.util.Base64.DEFAULT))
                    }.getOrDefault(author ?: "")
                    return@withContext Result.success(
                        PdfConvertResult(
                            epub = bytes,
                            pages = 0,
                            chapters = resp.header("X-Moreader-Chapters")?.toIntOrNull() ?: 0,
                            paragraphs = resp.header("X-Moreader-Paragraphs")?.toIntOrNull() ?: 0,
                            chars = resp.header("X-Moreader-Chars")?.toIntOrNull() ?: 0,
                            title = decodedTitle,
                            author = decodedAuthor,
                        )
                    )
                }
                val bodyText = resp.body?.string() ?: ""
                val code = runCatching { JSONObject(bodyText).optString("code", "") }.getOrDefault("")
                val detail = runCatching { JSONObject(bodyText).optString("detail", bodyText) }.getOrDefault(bodyText)
                if (resp.code == 401) {
                    return@withContext Result.failure(PdfConvertException("login_required", detail))
                }
                if (resp.code in 400..499) {
                    return@withContext Result.failure(
                        PdfConvertException(code.ifBlank { "failed" }, detail)
                    )
                }
                lastException = PdfConvertException(code, "HTTP ${resp.code}: $detail")
            } catch (e: Exception) {
                lastException = e
            }
        }
        Result.failure(lastException ?: PdfConvertException("failed", "网络请求失败"))
    }

    /**
     * PDF → 墨阅流式精读本 EPUB（服务端转换）
     * 失败时若服务端给出可识别错误码，抛 PdfConvertException(code = scanned/too_large/empty)
     */
    suspend fun convertPdfToEpub(
        pdfFile: File,
        title: String? = null,
        author: String? = null,
    ): Result<PdfConvertResult> = withContext(Dispatchers.IO) {
        if (!pdfFile.exists()) return@withContext Result.failure(PdfConvertException("missing", "文件不存在"))
        if (!isLoggedIn()) return@withContext Result.failure(PdfConvertException("login_required", "未登录"))

        var lastException: Exception? = null
        for (server in getCandidateServerUrls()) {
            try {
                val url = "${server.trimEnd('/')}/sync/pdf/convert"
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", pdfFile.name,
                        pdfFile.asRequestBody("application/pdf".toMediaType()),
                    )
                if (!title.isNullOrBlank()) builder.addFormDataPart("title", title)
                if (!author.isNullOrBlank()) builder.addFormDataPart("author", author)
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                    .post(builder.build())
                    .build()
                val resp = longClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        return@withContext Result.failure(PdfConvertException("empty", "转换结果为空"))
                    }
                    val decodedTitle = runCatching {
                        String(android.util.Base64.decode(resp.header("X-Moreader-Title") ?: "", android.util.Base64.DEFAULT))
                    }.getOrDefault(title ?: pdfFile.nameWithoutExtension)
                    val decodedAuthor = runCatching {
                        String(android.util.Base64.decode(resp.header("X-Moreader-Author") ?: "", android.util.Base64.DEFAULT))
                    }.getOrDefault(author ?: "")
                    return@withContext Result.success(
                        PdfConvertResult(
                            epub = bytes,
                            pages = resp.header("X-Moreader-Pages")?.toIntOrNull() ?: 0,
                            chapters = resp.header("X-Moreader-Chapters")?.toIntOrNull() ?: 0,
                            paragraphs = resp.header("X-Moreader-Paragraphs")?.toIntOrNull() ?: 0,
                            chars = resp.header("X-Moreader-Chars")?.toIntOrNull() ?: 0,
                            title = decodedTitle,
                            author = decodedAuthor,
                        )
                    )
                }
                val bodyText = resp.body?.string() ?: ""
                val code = runCatching { JSONObject(bodyText).optString("code", "") }.getOrDefault("")
                val detail = runCatching { JSONObject(bodyText).optString("detail", bodyText) }.getOrDefault(bodyText)
                if (resp.code == 401) {
                    return@withContext Result.failure(PdfConvertException("login_required", detail))
                }
                if (resp.code in 400..499) {
                    // 业务性失败（扫描版 / 超大 / 无文字），无需换服务器重试
                    return@withContext Result.failure(
                        PdfConvertException(code.ifBlank { "failed" }, detail)
                    )
                }
                lastException = PdfConvertException(code, "HTTP ${resp.code}: $detail")
            } catch (e: Exception) {
                lastException = e
            }
        }
        Result.failure(lastException ?: PdfConvertException("failed", "网络请求失败"))
    }

    // ── 上传书籍 ──────────────────────────────────────
    suspend fun uploadBook(bookId: String): Result<Int> = withContext(Dispatchers.IO) {
        val book = com.moyue.app.data.BookDatabase.getInstance(context)
            .bookDao().getBook(bookId) ?: return@withContext Result.failure(Exception("找不到书籍"))
        val file = File(book.filePath)
        if (!file.exists()) return@withContext Result.failure(Exception("文件不存在"))

        var lastException: Exception? = null
        for (server in getCandidateServerUrls()) {
            try {
                val url = "${server.trimEnd('/')}/sync/books/upload"
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "${book.title}.epub",
                        file.asRequestBody("application/epub+zip".toMediaType()))
                    .addFormDataPart("title", book.title)
                    .addFormDataPart("author", book.author)
                    .build()
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                    .post(body)
                    .build()
                val resp = client.newCall(req).execute()
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val serverId = JSONObject(respBody).optInt("id", -1)
                    Log.i("Sync", "上传成功: ${book.title} (server_id=$serverId)")
                    return@withContext Result.success(serverId)
                } else if (resp.code in 500..599) {
                    lastException = Exception("HTTP ${resp.code}: $respBody")
                    continue
                } else {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $respBody"))
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        Log.e("Sync", "上传失败", lastException)
        Result.failure(lastException ?: Exception("上传失败"))
    }

    /** 上传书籍 + 推送元数据（书签+高亮+进度）一步完成 */
    suspend fun uploadBookWithMetadata(bookId: String, repo: com.moyue.app.data.BookRepository): Result<String> {
        val serverIdResult = uploadBook(bookId)
        if (serverIdResult.isFailure) return Result.failure(serverIdResult.exceptionOrNull()!!)
        val serverId = serverIdResult.getOrThrow()
        val book = com.moyue.app.data.BookDatabase.getInstance(context)
            .bookDao().getBook(bookId) ?: return Result.success("已上传（元数据跳过：找不到本地书）")
        val bms = repo.getBookmarksOnce(bookId).map { bm ->
            BookmarkSync(bm.chapterIndex, bm.chapterTitle, bm.paragraphIndex, bm.paragraphText, bm.progress, bm.createdAt)
        }
        val hls = repo.getHighlightsOnce(bookId).map { hl ->
            HighlightSync(hl.chapterIndex, hl.startParagraph, hl.startOffset, hl.endParagraph, hl.endOffset, hl.text, hl.note, hl.color, hl.createdAt)
        }
        return pushBookMetadata(serverId, book, bms, hls).map { "已上传（含元数据）" }
    }

    suspend fun downloadBook(bookId: Int, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        for (server in getCandidateServerUrls()) {
            try {
                val url = "${server.trimEnd('/')}/sync/books/$bookId/download"
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    lastException = Exception("HTTP ${resp.code}")
                    continue
                }
                val body = resp.body ?: continue
                val bytes = body.bytes()
                FileOutputStream(destFile).use { it.write(bytes) }
                android.util.Log.i("Sync", "下载书籍: ${destFile.name} (${bytes.size} bytes)")
                return@withContext Result.success(destFile)
            } catch (e: Exception) {
                lastException = e
            }
        }
        Result.failure(lastException ?: Exception("下载失败"))
    }

    suspend fun deleteCloudBook(bookId: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${getServerUrl().trimEnd('/')}/sync/books/$bookId"
            val req = Request.Builder().url(url)
                .delete()
                .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${resp.code}: ${resp.body?.string() ?: ""}")
                )
            }
            val body = resp.body!!.string()
            val obj = JSONObject(body)
            Result.success(obj.optString("detail", "已删除"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── 同步元数据（书签+高亮+进度） ────────────────────

    data class BookmarkSync(
        val chapterIndex: Int,
        val chapterTitle: String?,
        val paragraphIndex: Int,
        val paragraphText: String?,
        val progress: Float,
        val createdAt: Long,
    )

    data class HighlightSync(
        val chapterIndex: Int,
        val startParagraph: Int,
        val startOffset: Int,
        val endParagraph: Int,
        val endOffset: Int,
        val text: String,
        val note: String?,
        val color: Int,
        val createdAt: Long,
    )

    /** 上传单本书的元数据到服务器（上传书籍后调用） */
    suspend fun pushBookMetadata(serverBookId: Int, book: com.moyue.app.data.models.Book,
                                 bookmarks: List<BookmarkSync>, highlights: List<HighlightSync>): Result<String> {
        val body = buildMetadataBody(book, bookmarks, highlights)
        return api("POST", "/sync/books/$serverBookId/metadata", body)
    }

    /** 从服务器拉取单本书的元数据 */
    suspend fun pullBookMetadata(serverBookId: Int): Result<String> {
        return api("GET", "/sync/books/$serverBookId/metadata")
    }

    /** 全量推送：将所有本地书的进度+书签+高亮推到服务器（按标题+作者匹配） */
    suspend fun pushAllMetadata(repo: com.moyue.app.data.BookRepository): Result<String> {
        val books = repo.getAllBooksOnce()
        val items = JSONArray()
        for (book in books) {
            val bms = repo.getBookmarksOnce(book.id).map { bm ->
                BookmarkSync(bm.chapterIndex, bm.chapterTitle, bm.paragraphIndex, bm.paragraphText, bm.progress, bm.createdAt)
            }
            val hls = repo.getHighlightsOnce(book.id).map { hl ->
                HighlightSync(hl.chapterIndex, hl.startParagraph, hl.startOffset, hl.endParagraph, hl.endOffset, hl.text, hl.note, hl.color, hl.createdAt)
            }
            items.put(buildPushItem(book, bms, hls))
        }
        val body = JSONObject().apply { put("books", items) }.toString()
        return api("POST", "/sync/push", body)
    }

    /** 上传到云端：本地数据覆盖云端（DELETE-INSERT 语义，删了的就删了） */
    suspend fun uploadToCloud(repo: com.moyue.app.data.BookRepository): Result<String> {
        val pushResult = pushAllMetadata(repo)
        return pushResult.map { "上传完成" }
    }

    /** 从云端下载：云端数据完全覆盖本地（书签+高亮+进度，进度取大值） */
    suspend fun downloadFromCloud(repo: com.moyue.app.data.BookRepository): Result<String> {
        // 1) 拉取云端数据
        val pullResult = api("GET", "/sync/pull")
        if (pullResult.isFailure) return pullResult.map { "" }
        val pullJson = pullResult.getOrThrow()
        val obj = JSONObject(pullJson)
        val items = obj.optJSONArray("books")

        if (items == null || items.length() == 0) {
            return Result.success("下载完成：云端无数据")  // internal, shown as syncResult
        }

        // 2) 读取本地所有书
        val localBooks = repo.getAllBooksOnce()
        var progressUpdated = 0
        var bmCount = 0
        var hlCount = 0

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val title = item.optString("title", "")
            if (title.isBlank()) continue
            val localBook = localBooks.find { it.title == title }
            if (localBook == null) continue

            // ── 进度：取大值 ──
            if (item.has("progress") && !item.isNull("progress")) {
                val p = item.getJSONObject("progress")
                val cloudPct = p.optDouble("percentage", 0.0).toFloat()
                if (cloudPct > localBook.currentProgress) {
                    repo.updateProgress(localBook.id,
                        p.optString("chapter_href", null),
                        p.optInt("chapter_index", 0),
                        cloudPct, null,
                        p.optInt("paragraph_index", 0),
                        localBook.themeId, localBook.fontSize)
                    progressUpdated++
                }
            }

            // ── 书签：删除全部本地 → 写入全部云端 ──
            val oldBms = repo.getBookmarksOnce(localBook.id)
            for (bm in oldBms) {
                repo.deleteBookmark(bm.id)
            }
            if (item.has("bookmarks")) {
                val bmsArr = item.getJSONArray("bookmarks")
                val newBms = mutableListOf<com.moyue.app.data.models.Bookmark>()
                for (j in 0 until bmsArr.length()) {
                    val b = bmsArr.getJSONObject(j)
                    newBms.add(com.moyue.app.data.models.Bookmark(
                        bookId = localBook.id,
                        chapterIndex = b.optInt("chapter_index", 0),
                        chapterTitle = b.optString("chapter_title", null),
                        paragraphIndex = b.optInt("paragraph_index", 0),
                        paragraphText = b.optString("paragraph_text", ""),
                        progress = b.optDouble("progress", 0.0).toFloat(),
                        createdAt = b.optLong("created_at", System.currentTimeMillis()),
                    ))
                }
                if (newBms.isNotEmpty()) {
                    repo.importBookmarks(newBms)
                    bmCount += newBms.size
                }
            }

            // ── 高亮：删除全部本地 → 写入全部云端 ──
            val oldHls = repo.getHighlightsOnce(localBook.id)
            for (hl in oldHls) {
                repo.deleteHighlightById(hl.id)
            }
            if (item.has("highlights")) {
                val hlsArr = item.getJSONArray("highlights")
                val newHls = mutableListOf<com.moyue.app.data.models.Highlight>()
                for (j in 0 until hlsArr.length()) {
                    val h = hlsArr.getJSONObject(j)
                    newHls.add(com.moyue.app.data.models.Highlight(
                        bookId = localBook.id,
                        chapterIndex = h.optInt("chapter_index", 0),
                        startParagraph = h.optInt("start_paragraph", 0),
                        startOffset = h.optInt("start_offset", 0),
                        endParagraph = h.optInt("end_paragraph", 0),
                        endOffset = h.optInt("end_offset", 0),
                        text = h.optString("text", ""),
                        note = h.optString("note", null),
                        color = h.optInt("color", 0xFFFFFF00.toInt()),
                        createdAt = h.optLong("created_at", System.currentTimeMillis()),
                    ))
                }
                if (newHls.isNotEmpty()) {
                    repo.importHighlights(newHls)
                    hlCount += newHls.size
                }
            }
        }

        val parts = mutableListOf("下载完成")
        if (progressUpdated > 0) parts.add("${progressUpdated}本进度已更新")
        if (bmCount > 0) parts.add("${bmCount}条书签")
        if (hlCount > 0) parts.add("${hlCount}条高亮")
        return Result.success(parts.joinToString("，"))
    }

    // ── 构建 JSON 辅助 ─────────────────────────────────

    private fun buildMetadataBody(book: com.moyue.app.data.models.Book,
                                   bookmarks: List<BookmarkSync>, highlights: List<HighlightSync>): String {
        return buildPushItem(book, bookmarks, highlights).toString()
    }

    private fun buildPushItem(book: com.moyue.app.data.models.Book,
                               bookmarks: List<BookmarkSync>, highlights: List<HighlightSync>): JSONObject {
        return JSONObject().apply {
            put("title", book.title)
            put("author", book.author)
            put("progress", JSONObject().apply {
                put("chapter_index", book.currentChapterIndex)
                put("chapter_href", book.currentChapterHref ?: "")
                put("paragraph_index", book.currentParagraphIndex)
                put("percentage", book.currentProgress)
            })
            put("bookmarks", JSONArray(bookmarks.map { bm ->
                JSONObject().apply {
                    put("chapter_index", bm.chapterIndex)
                    put("chapter_title", bm.chapterTitle ?: "")
                    put("paragraph_index", bm.paragraphIndex)
                    put("paragraph_text", bm.paragraphText ?: "")
                    put("progress", bm.progress)
                    put("created_at", bm.createdAt)
                }
            }))
            put("highlights", JSONArray(highlights.map { hl ->
                JSONObject().apply {
                    put("chapter_index", hl.chapterIndex)
                    put("start_paragraph", hl.startParagraph)
                    put("start_offset", hl.startOffset)
                    put("end_paragraph", hl.endParagraph)
                    put("end_offset", hl.endOffset)
                    put("text", hl.text)
                    put("note", hl.note ?: "")
                    put("color", hl.color)
                    put("created_at", hl.createdAt)
                }
            }))
        }
    }
}
