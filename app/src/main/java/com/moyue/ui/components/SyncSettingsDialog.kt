package com.moyue.app.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.sync.SyncClient
import com.moyue.app.sync.WebDavClient
import com.moyue.app.data.BookRepository
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SyncSettingsDialog(
    syncClient: SyncClient,
    onDismiss: () -> Unit,
    onUpload: ((onResult: (String) -> Unit) -> Unit)? = null,
    onDownload: ((onResult: (String) -> Unit) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val webDavClient = remember { WebDavClient(context) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(syncClient.getServerUrl()) }
    var showPassword by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var syncResult by remember { mutableStateOf<String?>(null) }
    var cloudBooks by remember { mutableStateOf<List<SyncClient.BookInfo>?>(null) }
    var isLoadingCloud by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf<Int?>(null) }
    var isDeleting by remember { mutableStateOf<Int?>(null) }
    var confirmDeleteBookId by remember { mutableStateOf<Int?>(null) }
    var cloudSearchQuery by remember { mutableStateOf("") }
    var loggedInVersion by remember { mutableStateOf(0) }
    var showHelpDialog by remember { mutableStateOf(false) }

    var defaultCloudTarget by remember { mutableStateOf(webDavClient.getDefaultCloudTarget()) }

    val localIsLoggedIn by remember { derivedStateOf { loggedInVersion >= 0 && syncClient.isLoggedIn() } }
    val localLoggedEmail by remember { derivedStateOf { syncClient.getEmail() } }
    val filteredCloudBooks = cloudBooks?.let { list ->
        if (cloudSearchQuery.isBlank()) list
        else list.filter { book ->
            book.title.contains(cloudSearchQuery, ignoreCase = true) ||
            book.author.contains(cloudSearchQuery, ignoreCase = true)
        }
    }

    val isLoggedIn = localIsLoggedIn
    val loggedEmail = localLoggedEmail

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("云同步与上传设置", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.HelpOutline, contentDescription = "帮助", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                // ── 默认云端上传目标选择 ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("默认书籍上传目标", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = defaultCloudTarget == "MOYUE",
                                onClick = {
                                    defaultCloudTarget = "MOYUE"
                                    webDavClient.setDefaultCloudTarget("MOYUE")
                                }
                            )
                            Text("墨阅自建云 (同步进度)", fontSize = 12.sp, modifier = Modifier.clickable {
                                defaultCloudTarget = "MOYUE"
                                webDavClient.setDefaultCloudTarget("MOYUE")
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = defaultCloudTarget == "WEBDAV",
                                onClick = {
                                    defaultCloudTarget = "WEBDAV"
                                    webDavClient.setDefaultCloudTarget("WEBDAV")
                                }
                            )
                            Text("WebDAV 网盘 (带书签)", fontSize = 12.sp, modifier = Modifier.clickable {
                                defaultCloudTarget = "WEBDAV"
                                webDavClient.setDefaultCloudTarget("WEBDAV")
                            })
                        }
                        if (defaultCloudTarget == "WEBDAV") {
                            val curUploadDir = webDavClient.getDefaultUploadDir()
                            Text(
                                if (curUploadDir.isBlank()) "当前目录：网盘根目录 (可在WebDAV浏览界面修改)" else "当前目录：$curUploadDir",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                if (isLoggedIn) {
                    // ── 已登录状态 ──
                    Icon(Icons.Default.CheckCircle, null,
                        modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_logged_in), fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(loggedEmail ?: "",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)

                    Spacer(Modifier.height(12.dp))

                    // 上传到云端
                    Button(
                        onClick = {
                            syncResult = context.getString(com.moyue.app.R.string.sync_uploading_status)
                            if (onUpload != null) {
                                onUpload { msg ->
                                    syncResult = msg
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_btn))
                    }

                    Spacer(Modifier.height(8.dp))

                    // 从云端下载
                    var showDownloadConfirm by remember { mutableStateOf(false) }
                    if (showDownloadConfirm) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_download_confirm_msg), fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            showDownloadConfirm = false
                                            syncResult = context.getString(com.moyue.app.R.string.sync_downloading_status)
                                            if (onDownload != null) {
                                                onDownload { msg ->
                                                    syncResult = msg
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary),
                                    ) {
                                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_download_confirm_btn), fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { showDownloadConfirm = false },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showDownloadConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_download_btn))
                        }
                    }

                    syncResult?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(msg, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }

                    Spacer(Modifier.height(10.dp))

                    // 查看云端书库
                    OutlinedButton(
                        onClick = {
                            isLoadingCloud = true
                            cloudBooks = null
                            scope.launch {
                                syncClient.listBooks().fold(
                                    onSuccess = { books ->
                                        cloudBooks = books
                                        isLoadingCloud = false
                                    },
                                    onFailure = { e ->
                                        cloudBooks = null
                                        isLoadingCloud = false
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoadingCloud,
                    ) {
                        if (isLoadingCloud) {
                            CircularProgressIndicator(Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(Icons.Default.Cloud, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isLoadingCloud) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_loading) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_view_cloud))
                    }

                    cloudBooks?.let { list ->
                        Spacer(Modifier.height(8.dp))
                        if (list.isEmpty()) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_cloud_shelf_empty), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            OutlinedTextField(
                                value = cloudSearchQuery,
                                onValueChange = { cloudSearchQuery = it },
                                placeholder = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_search_hint), fontSize = 13.sp) },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                                trailingIcon = {
                                    if (cloudSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { cloudSearchQuery = "" }) {
                                            Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                shape = RoundedCornerShape(8.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            val filteredList = filteredCloudBooks ?: list
                            val totalCount = list.size
                            val shownCount = filteredList.size
                            val hasFilter = cloudSearchQuery.isNotBlank()
                            Text(
                                if (hasFilter) "🔍 找到 $shownCount/$totalCount 本"
                                else "📚 共 $totalCount 本（点击下载）:",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            if (hasFilter && filteredList.isEmpty()) {
                                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_no_match), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            } else {
                                Column(modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                                    filteredList.forEach { book ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            .clickable(enabled = isDownloading == null) {
                                                isDownloading = book.id
                                                scope.launch {
                                                    val tmpFile = File(context.cacheDir, "download_${book.id}.epub")
                                                    syncClient.downloadBook(book.id, tmpFile).fold(
                                                        onSuccess = { file ->
                                                            val repo = BookRepository(context)
                                                            val localBook = repo.importEpubFile(file)
                                                            val coverPath = repo.extractCover(localBook.id)
                                                            if (coverPath != null) {
                                                                repo.updateBookCover(localBook.id, coverPath)
                                                            }
                                                            file.delete()
                                                            var restoredBm = 0
                                                            var restoredHl = 0
                                                            syncClient.pullBookMetadata(book.id).onSuccess { metaJson ->
                                                                try {
                                                                    val obj = org.json.JSONObject(metaJson)
                                                                    if (obj.has("bookmarks")) {
                                                                        val arr = obj.getJSONArray("bookmarks")
                                                                        val bms = (0 until arr.length()).map { j ->
                                                                            val b = arr.getJSONObject(j)
                                                                            com.moyue.app.data.models.Bookmark(
                                                                                bookId = localBook.id,
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
                                                                            )
                                                                        }
                                                                        repo.importHighlights(hls)
                                                                        restoredHl = hls.size
                                                                    }
                                                                    if (obj.has("progress") && !obj.isNull("progress")) {
                                                                        val p = obj.getJSONObject("progress")
                                                                        val chIdx = p.optInt("chapter_index", -1)
                                                                        if (chIdx >= 0) {
                                                                            repo.updateProgress(localBook.id,
                                                                                p.optString("chapter_href", null), chIdx,
                                                                                p.optDouble("percentage", 0.0).toFloat(), null,
                                                                                p.optInt("paragraph_index", 0), localBook.themeId, localBook.fontSize)
                                                                        }
                                                                    }
                                                                } catch (e: Exception) {
                                                                    android.util.Log.e("Sync", "写入元数据失败", e)
                                                                }
                                                            }
                                                            val bmPart = if (restoredBm > 0) "，${restoredBm}书签" else ""
                                                            val hlPart = if (restoredHl > 0) "，${restoredHl}高亮" else ""
                                                            android.widget.Toast.makeText(context,
                                                                "Downloaded: ${book.title}$bmPart$hlPart", android.widget.Toast.LENGTH_SHORT).show()
                                                        },
                                                        onFailure = { e ->
                                                            android.widget.Toast.makeText(context,
                                                                context.getString(com.moyue.app.R.string.sync_download_fail, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                                                        },
                                                    )
                                                    isDownloading = null
                                                }
                                            },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isDownloading == book.id) MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                                            if (isDownloading == book.id) {
                                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                                Spacer(Modifier.width(4.dp))
                                            } else {
                                                Icon(Icons.Default.Download, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text("${book.title} (${book.author})",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f),
                                                overflow = TextOverflow.Ellipsis)
                                            if (isDeleting == book.id) {
                                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                            } else {
                                                IconButton(
                                                    onClick = { confirmDeleteBookId = book.id },
                                                    modifier = Modifier.size(20.dp),
                                                ) {
                                                    Icon(Icons.Default.Delete, androidx.compose.ui.res.stringResource(com.moyue.app.R.string.delete),
                                                        Modifier.size(14.dp),
                                                        tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
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
                            syncClient.logout()
                            loggedInVersion++
                            syncResult = null
                            loginError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Logout, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_logout))
                    }
                } else {
                    // ── 未登录 — 登录表单 ──
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_login_hint),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; loginError = null },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; loginError = null },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_password)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility, "")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_server_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    loginError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (email.isBlank() || password.isBlank()) {
                                loginError = context.getString(com.moyue.app.R.string.sync_need_email_password)
                                return@Button
                            }
                            isLoggingIn = true
                            loginError = null
                            scope.launch {
                                context.getSharedPreferences("moreader_sync", Context.MODE_PRIVATE).edit()
                                    .putString("sync_server", serverUrl).apply()
                                val result = syncClient.login(email, password)
                                isLoggingIn = false
                                result.fold(
                                    onSuccess = { loggedInVersion++; android.widget.Toast.makeText(context, context.getString(com.moyue.app.R.string.sync_login_success), android.widget.Toast.LENGTH_SHORT).show() },
                                    onFailure = { loginError = it.message ?: context.getString(com.moyue.app.R.string.sync_login_fail) },
                                )
                            }
                        },
                        enabled = !isLoggingIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isLoggingIn) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_logging_in) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_login))
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_no_open_reg),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close)) }
        },
    )

    // ── 帮助与详细教程弹窗 ──
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("墨阅云端使用指南", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    Text("二、什么是 AList？如何对接网盘？", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
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

    // ── 确认删除云端书籍 ──
    confirmDeleteBookId?.let { bookId ->
        val book = cloudBooks?.find { it.id == bookId }
        AlertDialog(
            onDismissRequest = { confirmDeleteBookId = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_delete_confirm)) },
            text = {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_delete_book_confirm, book?.title ?: ""))
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteBookId = null
                        isDeleting = bookId
                        scope.launch {
                            syncClient.deleteCloudBook(bookId).fold(
                                onSuccess = { msg ->
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    syncClient.listBooks().fold(
                                        onSuccess = { cloudBooks = it },
                                        onFailure = {},
                                    )
                                },
                                onFailure = { e ->
                                    android.widget.Toast.makeText(context,
                                        context.getString(com.moyue.app.R.string.sync_delete_fail, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                                },
                            )
                            isDeleting = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error),
                ) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBookId = null }) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel)) }
            },
        )
    }
}
