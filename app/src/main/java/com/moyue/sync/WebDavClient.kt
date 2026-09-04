package com.moyue.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, "") ?: ""
    fun getUser(): String = prefs.getString(KEY_USER, "") ?: ""
    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""
    fun isConfigured(): Boolean = getServerUrl().isNotBlank() && getUser().isNotBlank()

    fun saveConfig(url: String, user: String, pass: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, url.trim().trimEnd('/'))
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASSWORD, pass)
            .apply()
    }

    fun clearConfig() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USER)
            .remove(KEY_PASSWORD)
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
     * 规范化构建请求的完整 URL，彻底解决多加 /dav 或拼错路径导致的 404
     */
    fun buildFullUrl(subPath: String): String {
        val base = getServerUrl().trimEnd('/')
        if (subPath.isBlank() || subPath == "/") return base
        if (subPath.startsWith("http://") || subPath.startsWith("https://")) return subPath

        val baseUri = try {
            Uri.parse(base)
        } catch (e: Exception) {
            null
        }

        val basePath = (baseUri?.path ?: "").trimEnd('/')
        val cleanSub = if (subPath.startsWith("/")) subPath else "/$subPath"

        return if (basePath.isNotBlank() && cleanSub.startsWith(basePath)) {
            val rest = cleanSub.substring(basePath.length)
            val cleanRest = if (rest.startsWith("/")) rest else "/$rest"
            base + cleanRest
        } else {
            base + cleanSub
        }
    }

    /**
     * 列出目录内容 (PROPFIND Depth: 1)
     * @param subPath 相对路径，例如 "" 或 "/dav/media" 或 "media"
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
     * 上传文件到 WebDAV (PUT)
     */
    suspend fun uploadFile(subPath: String, file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = buildFullUrl(subPath)

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
                            // 解码 href
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
