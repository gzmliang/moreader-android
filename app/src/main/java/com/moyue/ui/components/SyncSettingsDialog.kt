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
    var loggedInVersion by remember { mutableStateOf(0) }  // 登录状态变更触发器
    // 用本地状态跟踪登录状态，确保 UI 即时刷新
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
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.app_name))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isLoggedIn) {
                    // ── 已登录状态 ──
                    Icon(Icons.Default.CheckCircle, null,
                        modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_logged_in), fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(loggedEmail ?: "",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

                    Spacer(Modifier.height(16.dp))

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

                    Spacer(Modifier.height(12.dp))

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
                            // 搜索框
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
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                ),
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
                                Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
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
                                                            // Re-extract cover from downloaded EPUB
                                                            val coverPath = repo.extractCover(localBook.id)
                                                            if (coverPath != null) {
                                                                repo.updateBookCover(localBook.id, coverPath)
                                                            }
                                                            file.delete()
                                                            // 拉取云端元数据（书签+高亮+进度）
                                                            var restoredBm = 0
                                                            var restoredHl = 0
                                                            syncClient.pullBookMetadata(book.id).onSuccess { metaJson ->
                                                                try {
                                                                    val obj = org.json.JSONObject(metaJson)
                                                                    // 书签
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
                                                                    // 高亮
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
                                                                    // 进度
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
                                                CircularProgressIndicator(Modifier.size(12.dp),
                                                    strokeWidth = 1.5.dp)
                                                Spacer(Modifier.width(4.dp))
                                            } else {
                                                Icon(Icons.Default.Download, null,
                                                    Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text("${book.title} (${book.author})",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f),
                                                overflow = TextOverflow.Ellipsis)
                                            // 删除按钮
                                            if (isDeleting == book.id) {
                                                CircularProgressIndicator(Modifier.size(12.dp),
                                                    strokeWidth = 1.5.dp)
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
                                // Update server URL if changed
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
                                    // 刷新书库列表
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
