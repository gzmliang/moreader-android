package com.moyue.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端，支持对接坚果云、AList、InfiniCloud、群晖等标准 WebDAV 服务
 */
class WebDavClient(private val context: Context) {

    companion object {
        private const val TAG = "WebDavClient"
        private const val PREF_NAME = "moreader_webdav"
        private const val KEY_SERVER_URL = "webdav_server_url"
        private const val KEY_USER = "webdav_user"
        private const val KEY_PASSWORD = "webdav_password"
        private const val KEY_DEFAULT_UPLOAD_DIR = "webdav_default_upload_dir"
        private const val KEY_DEFAULT_CLOUD_TARGET = "sync_default_cloud_target" // "MOYUE" or "WEBDAV"
        private const val KEY_PRESET = "webdav_preset" // "alist", "jianguo", "custom"
    }

    data class DavItem(
        val name: String,
        val path: String,       // 完整相对或绝对路径
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: String = "",
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getPreset(): String = prefs.getString(KEY_PRESET, "alist") ?: "alist"
    fun setPreset(preset: String) {
        prefs.edit().putString(KEY_PRESET, preset).apply()
    }

    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, "") ?: ""
    fun getUser(): String = prefs.getString(KEY_USER, "") ?: ""
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""
    fun isConfigured(): Boolean = getServerUrl().isNotBlank() && getUser().isNotBlank()

    fun getDefaultUploadDir(): String = prefs.getString(KEY_DEFAULT_UPLOAD_DIR, "") ?: ""
    fun setDefaultUploadDir(dir: String) {
        prefs.edit().putString(KEY_DEFAULT_UPLOAD_DIR, dir).apply()
    }

    /** 默认上传目标：MOYUE (自建云) 或 WEBDAV (网盘) */
    fun getDefaultCloudTarget(): String = prefs.getString(KEY_DEFAULT_CLOUD_TARGET, "MOYUE") ?: "MOYUE"
    fun setDefaultCloudTarget(target: String) {
        prefs.edit().putString(KEY_DEFAULT_CLOUD_TARGET, target).apply()
    }

    fun saveConfig(url: String, user: String, pass: String, preset: String = "alist", defaultDir: String = "") {
        prefs.edit()
            .putString(KEY_SERVER_URL, url.trim().trimEnd('/'))
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASSWORD, pass)
            .putString(KEY_PRESET, preset)
            .putString(KEY_DEFAULT_UPLOAD_DIR, defaultDir.trim())
            .putString(KEY_DEFAULT_CLOUD_TARGET, "WEBDAV")
            .apply()
    }

    fun clearConfig() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USER)
            .remove(KEY_PASSWORD)
            .remove(KEY_DEFAULT_UPLOAD_DIR)
            .remove(KEY_PRESET)
            .apply()
    }

    private fun getAuthHeader(): String {
        val user = getUser()
        val pass = getPassword()
        val credentials = "$user:$pass"
        val basic = android.util.Base64.encodeToString(credentials.toByteArray(), android.util.Base64.NO_WRAP)
        return "Basic $basic"
    }

    /**
     * 规范化构建请求的完整 URL
     */
    fun buildFullUrl(subPath: String): String {
        val base = getServerUrl().trimEnd('/')
        if (base.isBlank()) return ""
        if (subPath.isBlank() || subPath == "/") return base
        if (subPath.startsWith("http://") || subPath.startsWith("https://")) return subPath

        val baseUri = try {
            Uri.parse(base)
        } catch (e: Exception) {
            null
        }

        val basePath = (baseUri?.path ?: "").trimEnd('/')
        val cleanSub = if (subPath.startsWith("/")) subPath else "/$subPath"

        val rawCombined = if (basePath.isNotBlank() && cleanSub.startsWith(basePath)) {
            val rest = cleanSub.substring(basePath.length)
            val cleanRest = if (rest.startsWith("/")) rest else "/$rest"
            base + cleanRest
        } else {
            base + cleanSub
        }

        return try {
            val uri = Uri.parse(rawCombined)
            val scheme = uri.scheme ?: "http"
            val authority = uri.authority ?: ""
            val path = uri.path ?: ""
            val encodedPath = Uri.encode(path, "/")
            "$scheme://$authority$encodedPath"
        } catch (e: Exception) {
            rawCombined
        }
    }

    /**
     * 测试 WebDAV 连通性与鉴权
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext Result.failure(Exception("WebDAV 未配置"))
            val targetDir = getDefaultUploadDir()
            val url = buildFullUrl(targetDir)

            val req = Request.Builder()
                .url(url)
                .method("PROPFIND", "".toRequestBody(null))
                .addHeader("Authorization", getAuthHeader())
                .addHeader("Depth", "0")
                .build()

            val resp = client.newCall(req).execute()
            when (resp.code) {
                207, 200 -> Result.success("OK")
                401 -> Result.failure(Exception("HTTP 401"))
                404 -> Result.failure(Exception("HTTP 404"))
                else -> Result.failure(Exception("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed", e)
            Result.failure(e)
        }
    }

    /**
     * 删除 WebDAV 上的文件 (DELETE)
     */
    suspend fun deleteFile(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = buildFullUrl(remotePath)
            val req = Request.Builder()
                .url(targetUrl)
                .delete()
                .addHeader("Authorization", getAuthHeader())
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful || resp.code == 204 || resp.code == 404) {
                Result.success(true)
            } else {
                Result.failure(Exception("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV deleteFile failed", e)
            Result.failure(e)
        }
    }

    /**
     * 从 WebDAV 删除书籍及其伴侣元数据 JSON
     */
    suspend fun deleteBookWithMetadata(remoteEpubPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val epubRes = deleteFile(remoteEpubPath)
            val metaPath = remoteEpubPath.removeSuffix(".epub").removeSuffix(".EPUB") + ".moreader.json"
            deleteFile(metaPath) // 静默删除伴侣文件
            epubRes
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 WebDAV 下载书籍并完整恢复阅读进度、书签和高亮笔记
     */
    suspend fun downloadBookAndRestore(
        remoteItem: DavItem,
        repo: BookRepository
    ): Result<Book> = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "webdav_dl_${System.currentTimeMillis()}_${remoteItem.name}")
        try {
            val dlRes = downloadFile(remoteItem.path, tempFile)
            if (dlRes.isFailure) {
                return@withContext Result.failure(dlRes.exceptionOrNull() ?: Exception("下载失败"))
            }

            val imported = repo.importBook(Uri.fromFile(tempFile))

            // 立即提取封面并更新
            val coverPath = repo.extractCover(imported.id)
            if (coverPath != null) {
                repo.updateBookCover(imported.id, coverPath)
            }

            // 尝试读取同名伴侣文件并恢复
            val metaPath = remoteItem.path.removeSuffix(".epub").removeSuffix(".EPUB") + ".moreader.json"
            getTextFile(metaPath).onSuccess { metaJson ->
                applyCompanionMetadata(repo, imported.id, metaJson)
            }

            val finalBook = repo.getBook(imported.id) ?: imported
            Result.success(finalBook)
        } catch (e: Exception) {
            Log.e(TAG, "downloadBookAndRestore failed", e)
            Result.failure(e)
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    /**
     * 将伴侣元数据 JSON 恢复至本地书籍
     */
    suspend fun applyCompanionMetadata(repo: BookRepository, localBookId: String, jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val bookmarks = mutableListOf<com.moyue.app.data.models.Bookmark>()
            if (obj.has("bookmarks")) {
                val arr = obj.getJSONArray("bookmarks")
                for (j in 0 until arr.length()) {
                    val b = arr.getJSONObject(j)
                    bookmarks.add(
                        com.moyue.app.data.models.Bookmark(
                            bookId = localBookId,
                            chapterIndex = b.optInt("chapter_index", 0),
                            chapterTitle = b.optString("chapter_title", null),
                            paragraphIndex = b.optInt("paragraph_index", 0),
                            paragraphText = b.optString("paragraph_text", null),
                            progress = b.optDouble("progress", 0.0).toFloat(),
                            createdAt = b.optLong("created_at", System.currentTimeMillis()),
                        )
                    )
                }
            }
            if (bookmarks.isNotEmpty()) {
                repo.importBookmarks(bookmarks)
            }

            val highlights = mutableListOf<com.moyue.app.data.models.Highlight>()
            if (obj.has("highlights")) {
                val arr = obj.getJSONArray("highlights")
                for (j in 0 until arr.length()) {
                    val h = arr.getJSONObject(j)
                    highlights.add(
                        com.moyue.app.data.models.Highlight(
                            bookId = localBookId,
                            chapterIndex = h.optInt("chapter_index", 0),
                            startParagraph = h.optInt("start_paragraph", 0),
                            startOffset = h.optInt("start_offset", 0),
                            endParagraph = h.optInt("end_paragraph", 0),
                            endOffset = h.optInt("end_offset", 0),
                            text = h.optString("text", ""),
                            note = h.optString("note", null).takeIf { it?.isNotBlank() == true },
                            color = h.optInt("color", 0xFFFFFF00.toInt()),
                            createdAt = h.optLong("created_at", System.currentTimeMillis()),
                        )
                    )
                }
            }
            if (highlights.isNotEmpty()) {
                repo.importHighlights(highlights)
            }

            if (obj.has("progress") && !obj.isNull("progress")) {
                val p = obj.getJSONObject("progress")
                val chIdx = p.optInt("chapter_index", -1)
                if (chIdx >= 0) {
                    val localBook = repo.getBook(localBookId) ?: return
                    repo.updateProgress(
                        localBookId,
                        p.optString("chapter_href", null),
                        chIdx,
                        p.optDouble("percentage", 0.0).toFloat(),
                        null,
                        p.optInt("paragraph_index", 0),
                        localBook.themeId,
                        localBook.fontSize
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyCompanionMetadata failed", e)
        }
    }

    /**
     * 列出目录内容 (PROPFIND Depth: 1)
     */
    suspend fun listFiles(subPath: String = ""): Result<List<DavItem>> = withContext(Dispatchers.IO) {
        try {
            val base = getServerUrl()
            if (base.isBlank()) return@withContext Result.failure(Exception("WebDAV 未配置"))

            val url = buildFullUrl(subPath)

            val req = Request.Builder()
                .url(url)
                .method("PROPFIND", "".toRequestBody(null))
                .addHeader("Authorization", getAuthHeader())
                .addHeader("Depth", "1")
                .build()

            val resp = client.newCall(req).execute()
            val code = resp.code
            val xml = resp.body?.string() ?: ""

            if (code == 207 || code == 200) {
                val items = parsePropFindResponse(xml, url)
                Result.success(items)
            } else if (code == 401) {
                Result.failure(Exception("WebDAV 认证失败，请检查账号密码"))
            } else {
                Result.failure(Exception("WebDAV 请求失败: HTTP $code"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV listFiles failed", e)
            Result.failure(e)
        }
    }

    /**
     * 下载文件到本地
     */
    suspend fun downloadFile(remoteItemPath: String, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = buildFullUrl(remoteItemPath)

            val req = Request.Builder()
                .url(fullUrl)
                .get()
                .addHeader("Authorization", getAuthHeader())
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("下载失败: HTTP " + resp.code))
            }

            val body = resp.body ?: return@withContext Result.failure(Exception("响应为空"))
            FileOutputStream(destFile).use { out ->
                body.byteStream().copyTo(out)
            }
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV download failed", e)
            Result.failure(e)
        }
    }

    /**
     * 获取 WebDAV 上的文本内容（如伴侣元数据 JSON）
     */
    suspend fun getTextFile(remoteItemPath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = buildFullUrl(remoteItemPath)
            val req = Request.Builder()
                .url(fullUrl)
                .get()
                .addHeader("Authorization", getAuthHeader())
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                Result.success(resp.body?.string() ?: "")
            } else {
                Result.failure(Exception("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 上传二进制文件到 WebDAV (PUT)
     */
    suspend fun uploadFile(remotePath: String, file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = buildFullUrl(remotePath)
            val req = Request.Builder()
                .url(targetUrl)
                .put(file.asRequestBody())
                .addHeader("Authorization", getAuthHeader())
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful || resp.code == 201 || resp.code == 204) {
                Result.success("上传成功")
            } else {
                Result.failure(Exception("上传失败 HTTP " + resp.code))
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV upload failed", e)
            Result.failure(e)
        }
    }

    /**
     * 上传文本内容（如伴侣元数据 JSON）到 WebDAV (PUT)
     */
    suspend fun uploadText(remotePath: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = buildFullUrl(remotePath)
            val req = Request.Builder()
                .url(targetUrl)
                .put(content.toRequestBody())
                .addHeader("Authorization", getAuthHeader())
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful || resp.code == 201 || resp.code == 204) {
                Result.success("上传成功")
            } else {
                Result.failure(Exception("HTTP " + resp.code))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 将电子书及所有元数据（书签、高亮、进度）打包上传至 WebDAV 指定目录
     */
    suspend fun uploadBookWithMetadata(
        bookId: String,
        repo: BookRepository,
        destDirectory: String = getDefaultUploadDir()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val book = repo.getBook(bookId) ?: return@withContext Result.failure(Exception("找不到本地书籍"))
            val localFile = File(book.filePath)
            if (!localFile.exists()) return@withContext Result.failure(Exception("EPUB 文件不存在"))

            val dir = if (destDirectory.isBlank()) "" else destDirectory.trimEnd('/')
            val epubRemotePath = if (dir.isBlank()) "${book.title}.epub" else "$dir/${book.title}.epub"
            val metaRemotePath = if (dir.isBlank()) "${book.title}.moreader.json" else "$dir/${book.title}.moreader.json"

            // 1. 上传 EPUB 文件
            val epubRes = uploadFile(epubRemotePath, localFile)
            if (epubRes.isFailure) {
                return@withContext Result.failure(epubRes.exceptionOrNull() ?: Exception("上传 EPUB 失败"))
            }

            // 2. 生成伴侣元数据 JSON
            val bookmarks = repo.getBookmarksOnce(bookId)
            val highlights = repo.getHighlightsOnce(bookId)

            val metaJson = JSONObject().apply {
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

            // 3. 上传伴侣元数据 JSON
            uploadText(metaRemotePath, metaJson.toString(2))

            val countMsg = "${bookmarks.size}个书签，${highlights.size}处高亮"
            Result.success("已上传至 WebDAV ($countMsg)")
        } catch (e: Exception) {
            Log.e(TAG, "uploadBookWithMetadata failed", e)
            Result.failure(e)
        }
    }

    /**
     * 解析 WebDAV PROPFIND XML 结果
     */
    private fun parsePropFindResponse(xml: String, requestUrl: String): List<DavItem> {
        val list = mutableListOf<DavItem>()
        try {
            val reqPath = try {
                val parsedPath = Uri.parse(requestUrl).path ?: ""
                URLDecoder.decode(parsedPath, "UTF-8").trimEnd('/')
            } catch (e: Exception) {
                ""
            }

            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentHref = ""
            var currentName = ""
            var currentSize = 0L
            var currentMod = ""
            var isCollection = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.lowercase() ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tag) {
                            "response" -> {
                                currentHref = ""
                                currentName = ""
                                currentSize = 0L
                                currentMod = ""
                                isCollection = false
                            }
                            "href" -> {
                                currentHref = parser.nextText().trim()
                            }
                            "displayname" -> {
                                currentName = parser.nextText().trim()
                            }
                            "getcontentlength" -> {
                                val s = parser.nextText().trim()
                                currentSize = s.toLongOrNull() ?: 0L
                            }
                            "getlastmodified" -> {
                                currentMod = parser.nextText().trim()
                            }
                            "collection" -> {
                                isCollection = true
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag == "response") {
                            val decodedHref = try {
                                URLDecoder.decode(currentHref, "UTF-8")
                            } catch (e: Exception) {
                                currentHref
                            }
                            val rawName = if (currentName.isNotBlank()) currentName else {
                                decodedHref.trimEnd('/').substringAfterLast('/')
                            }
                            if (rawName.isNotBlank()) {
                                val itemPath = decodedHref.trimEnd('/')
                                val isSelf = itemPath.equals(reqPath, ignoreCase = true) ||
                                        itemPath.isEmpty() ||
                                        itemPath == "/"

                                if (!isSelf) {
                                    list.add(
                                        DavItem(
                                            name = rawName,
                                            path = decodedHref,
                                            isDirectory = isCollection,
                                            size = currentSize,
                                            lastModified = currentMod,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePropFindResponse error", e)
        }
        return list
    }
}
