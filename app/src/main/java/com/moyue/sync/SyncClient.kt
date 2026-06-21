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
        private const val DEFAULT_SERVER = "http://powerplus.blogsyte.com:5001"

        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── 认证状态 ──────────────────────────────────────

    fun getServerUrl(): String = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)
    fun isLoggedIn(): Boolean = getToken() != null

    fun saveLogin(token: String, email: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EMAIL)
            .apply()
    }

    // ── HTTP 辅助 ─────────────────────────────────────

    private suspend fun api(
        method: String,
        path: String,
        body: String? = null,
        auth: Boolean = true,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = getServerUrl().trimEnd('/') + path
            val req = Request.Builder().url(url).method(method, body?.toRequestBody(JSON_MEDIA))
            if (auth) {
                val token = getToken()
                if (token == null) return@withContext Result.failure(Exception("未登录"))
                req.addHeader("Authorization", "Bearer $token")
            }
            val resp = client.newCall(req.build()).execute()
            val respBody = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                Result.success(respBody)
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $respBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── 登录 ──────────────────────────────────────────

    suspend fun login(email: String, password: String): Result<String> {
        val body = """{"email":"$email","password":"$password"}"""
        val result = api("POST", "/sync/auth/login", body, auth = false)
        return result.map { json ->
            val obj = JSONObject(json)
            val token = obj.getString("token")
            saveLogin(token, email)
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
                )
            }
        }
    }

    // ── 上传书籍 ──────────────────────────────────────
    suspend fun uploadBook(bookId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val book = com.moyue.app.data.BookDatabase.getInstance(context)
                .bookDao().getBook(bookId) ?: return@withContext Result.failure(Exception("找不到书籍"))
            val file = File(book.filePath)
            if (!file.exists()) return@withContext Result.failure(Exception("文件不存在"))

            val url = "${getServerUrl().trimEnd('/')}/sync/books/upload"
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
                Result.success(serverId)
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $respBody"))
            }
        } catch (e: Exception) {
            Log.e("Sync", "上传失败", e)
            Result.failure(e)
        }
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
        try {
            val url = "${getServerUrl().trimEnd('/')}/sync/books/$bookId/download"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer ${getToken() ?: ""}")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            }
            val body = resp.body ?: return@withContext Result.failure(Exception("空响应"))
            val bytes = body.bytes()
            FileOutputStream(destFile).use { it.write(bytes) }
            android.util.Log.i("Sync", "下载书籍: ${destFile.name} (${bytes.size} bytes)")
            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
