package com.moyue.app.ui.components
import android.util.Log

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.data.BookRepository
import com.moyue.app.sync.WebDavClient
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

@Composable
fun WebDavBrowserDialog(
    webDavClient: WebDavClient,
    onDismiss: () -> Unit,
    onBookImported: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(webDavClient.getServerUrl()) }
    var user by remember { mutableStateOf(webDavClient.getUser()) }
    var password by remember { mutableStateOf(webDavClient.getPassword()) }
    var showPassword by remember { mutableStateOf(false) }

    var isConfigured by remember { mutableStateOf(webDavClient.isConfigured()) }
    var currentPath by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<WebDavClient.DavItem>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadingPath by remember { mutableStateOf<String?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }

    var defaultUploadDir by remember { mutableStateOf(webDavClient.getDefaultUploadDir()) }

    val baseUri = remember(serverUrl) {
        try { Uri.parse(webDavClient.getServerUrl()) } catch (e: Exception) { null }
    }
    val rootDavPath = remember(baseUri) {
        (baseUri?.path ?: "").trimEnd('/')
    }

    fun isAtRoot(path: String): Boolean {
        val p = path.trimEnd('/')
        return p.isBlank() || p == rootDavPath || p == "/"
    }

    fun refreshList(path: String) {
        isLoading = true
        errorMessage = null
        currentPath = path
        scope.launch {
            webDavClient.listFiles(path).fold(
                onSuccess = { list ->
                    // 过滤掉内部元数据伴侣文件 *.moreader.json
                    items = list.filter { !it.name.endsWith(".moreader.json", ignoreCase = true) }
                    isLoading = false
                },
                onFailure = { e ->
                    errorMessage = e.message ?: "加载失败"
                    isLoading = false
                }
            )
        }
    }

    LaunchedEffect(isConfigured) {
        if (isConfigured) {
            refreshList("")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                val displayTitle = if (isConfigured) {
                    if (isAtRoot(currentPath)) "WebDAV 网盘" else {
                        val decoded = try { URLDecoder.decode(currentPath, "UTF-8") } catch (e: Exception) { currentPath }
                        "..." + decoded.trimEnd('/').substringAfterLast('/')
                    }
                } else "连接 WebDAV 网盘"
                Text(
                    displayTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 帮助按钮
                IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.HelpOutline, contentDescription = "帮助教程", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isConfigured) {
                    // 配置界面
                    Text("支持对接 AList、坚果云、群晖、InfiniCloud 等网盘", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it; errorMessage = null },
                        label = { Text("WebDAV 服务器地址") },
                        placeholder = { Text("如 http://IP:6355/dav") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it; errorMessage = null },
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    errorMessage?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (serverUrl.isBlank() || user.isBlank()) {
                                errorMessage = "请填写服务器地址和账号"
                                return@Button
                            }
                            webDavClient.saveConfig(serverUrl, user, password)
                            isConfigured = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存并连接")
                    }
                } else {
                    // 已配置，文件浏览界面
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isAtRoot(currentPath)) {
                            TextButton(
                                onClick = {
                                    val p = currentPath.trimEnd('/')
                                    val parent = p.substringBeforeLast('/', "")
                                    if (parent.isBlank() || parent == rootDavPath) {
                                        refreshList("")
                                    } else {
                                        refreshList(parent)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.ArrowBack, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("上一级", fontSize = 12.sp)
                            }
                        } else {
                            Text("根目录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 设为默认上传目录按钮
                            val isCurrentDefault = defaultUploadDir.isNotBlank() && defaultUploadDir.trimEnd('/') == currentPath.trimEnd('/')
                            TextButton(
                                onClick = {
                                    webDavClient.setDefaultUploadDir(currentPath)
                                    defaultUploadDir = currentPath
                                    android.widget.Toast.makeText(context, "已将当前目录设为默认上传目录", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(if (isCurrentDefault) Icons.Default.CheckCircle else Icons.Default.DriveFolderUpload, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(if (isCurrentDefault) "已设为上传目录" else "设为上传目录", fontSize = 11.sp)
                            }

                            IconButton(
                                onClick = { refreshList(currentPath) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (isLoading) {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                        }
                    } else if (errorMessage != null) {
                        Column(
                            Modifier.fillMaxWidth().height(160.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { refreshList(currentPath) }) {
                                Text("重试")
                            }
                        }
                    } else {
                        val list = items ?: emptyList()
                        if (list.isEmpty()) {
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                Text("此文件夹为空", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                val sorted = list.sortedWith(
                                    compareByDescending<WebDavClient.DavItem> { it.isDirectory }
                                        .thenBy { it.name.lowercase() }
                                )

                                for (item in sorted) {
                                    val isEpub = item.name.endsWith(".epub", ignoreCase = true)
                                    val isDownloadingThis = downloadingPath == item.path

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clickable(enabled = downloadingPath == null) {
                                                if (item.isDirectory) {
                                                    refreshList(item.path)
                                                } else if (isEpub) {
                                                    downloadingPath = item.path
                                                    scope.launch {
                                                        val tmpFile = File(context.cacheDir, "webdav_${System.currentTimeMillis()}.epub")
                                                        webDavClient.downloadFile(item.path, tmpFile).fold(
                                                            onSuccess = { file ->
                                                                val repo = BookRepository(context)
                                                                val imported = repo.importEpubFile(file)
                                                                val cover = repo.extractCover(imported.id)
                                                                if (cover != null) {
                                                                    repo.updateBookCover(imported.id, cover)
                                                                }
                                                                file.delete()

                                                                // 检查是否存在伴侣元数据文件并恢复书签/高亮/进度
                                                                val metaPath = item.path.removeSuffix(".epub").removeSuffix(".EPUB") + ".moreader.json"
                                                                var restoredBm = 0
                                                                var restoredHl = 0
                                                                webDavClient.getTextFile(metaPath).onSuccess { metaJson ->
                                                                    try {
                                                                        val obj = JSONObject(metaJson)
                                                                        if (obj.has("bookmarks")) {
                                                                            val arr = obj.getJSONArray("bookmarks")
                                                                            val bms = (0 until arr.length()).map { j ->
                                                                                val b = arr.getJSONObject(j)
                                                                                com.moyue.app.data.models.Bookmark(
                                                                                    bookId = imported.id,
                                                                                    chapterIndex = b.optInt("chapter_index", 0),
                                                                                    chapterTitle = b.optString("chapter_title", null),
                                                                                    paragraphIndex = b.optInt("paragraph_index", 0),
                                                                                    paragraphText = b.optString("paragraph_text", null),
                                                                                    progress = b.optDouble("progress", 0.0).toFloat(),
                                                                                    createdAt = b.optLong("created_at", System.currentTimeMillis()),
                                                                                )
                                                                            }
                                                                            repo.importBookmarks(bms)
                                                                            restoredBm = bms.size
                                                                        }
                                                                        if (obj.has("highlights")) {
                                                                            val arr = obj.getJSONArray("highlights")
                                                                            val hls = (0 until arr.length()).map { j ->
                                                                                val h = arr.getJSONObject(j)
                                                                                com.moyue.app.data.models.Highlight(
                                                                                    bookId = imported.id,
                                                                                    chapterIndex = h.optInt("chapter_index", 0),
                                                                                    startParagraph = h.optInt("start_paragraph", 0),
                                                                                    startOffset = h.optInt("start_offset", 0),
                                                                                    endParagraph = h.optInt("end_paragraph", 0),
                                                                                    endOffset = h.optInt("end_offset", 0),
                                                                                    text = h.optString("text", ""),
                                                                                    note = h.optString("note", null),
                                                                                    color = h.optInt("color", 0xFFFFFF00.toInt()),
                                                                                    createdAt = h.optLong("created_at", System.currentTimeMillis()),
                                                                                )
                                                                            }
                                                                            repo.importHighlights(hls)
                                                                            restoredHl = hls.size
                                                                        }
                                                                        if (obj.has("progress") && !obj.isNull("progress")) {
                                                                            val p = obj.getJSONObject("progress")
                                                                            val chIdx = p.optInt("chapter_index", -1)
                                                                            if (chIdx >= 0) {
                                                                                repo.updateProgress(imported.id,
                                                                                    p.optString("chapter_href", null), chIdx,
                                                                                    p.optDouble("percentage", 0.0).toFloat(), null,
                                                                                    p.optInt("paragraph_index", 0), imported.themeId, imported.fontSize)
                                                                            }
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        Log.e("WebDAV", "恢复伴侣数据失败", e)
                                                                    }
                                                                }

                                                                val extraMsg = if (restoredBm > 0 || restoredHl > 0) " (已恢复${restoredBm}书签+${restoredHl}高亮)" else ""
                                                                android.widget.Toast.makeText(context, "已导入: ${imported.title}$extraMsg", android.widget.Toast.LENGTH_SHORT).show()
                                                                onBookImported()
                                                            },
                                                            onFailure = { err ->
                                                                android.widget.Toast.makeText(context, "下载失败: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                                                            }
                                                        )
                                                        downloadingPath = null
                                                    }
                                                }
                                            },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isDownloadingThis) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isDownloadingThis) {
                                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            } else if (item.isDirectory) {
                                                Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                            } else if (isEpub) {
                                                Icon(Icons.Default.Book, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                                            } else {
                                                Icon(Icons.Default.InsertDriveFile, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                            }

                                            Spacer(Modifier.width(8.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    item.name,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = if (item.isDirectory) FontWeight.Medium else FontWeight.Normal,
                                                    color = if (isEpub || item.isDirectory) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                )
                                                if (!item.isDirectory && item.size > 0) {
                                                    val mb = item.size / (1024.0 * 1024.0)
                                                    Text(String.format("%.2f MB", mb), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                                }
                                            }

                                            if (isEpub && !isDownloadingThis) {
                                                Icon(Icons.Default.Download, contentDescription = "下载", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            webDavClient.clearConfig()
                            isConfigured = false
                            items = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("断开 WebDAV", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    // ── 帮助与详细教程弹窗 ──
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("墨阅云端与网盘使用指南", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text("一、两大云端的定位分工", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "1. 墨阅自建云（默认）：专用于多设备间实时同步阅读进度、书签和划线高亮。\n" +
                        "2. WebDAV 网盘：适合作为海量电子书大仓库，支持百度网盘、夸克网盘、阿里云盘、坚果云、群晖 NAS 等。",
                        fontSize = 12.sp, lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("二、什么是 AList？如何对接百度网盘？", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "AList 是一款非常强大的开源网盘聚合挂载神器（官网：alist.nn.ci）。\n\n" +
                        "它可以将您的百度网盘、阿里云盘、夸克网盘集中挂载起来，并一键开启 WebDAV 协议。\n\n" +
                        "• 填写规范：在墨阅地址栏填写「http://服务器IP:端口/dav」（注意后面必须带 /dav）并输入 AList 账号密码即可打通。\n" +
                        "• 如需了解如何搭建 AList，可访问官方文档：https://alist.nn.ci/zh/guide/",
                        fontSize = 12.sp, lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("三、书签与高亮同步保证", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当您选择上传书籍到 WebDAV 网盘时，墨阅不仅会上传 EPUB 原文件，还会自动生成一份同名的伴侣元数据文件（.moreader.json）。\n\n" +
                        "日后在任何手机从网盘下载该书时，墨阅会自动识别并完整还原该书的阅读进度、所有书签和划线笔记！",
                        fontSize = 12.sp, lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("我知道了") }
            }
        )
    }
}
