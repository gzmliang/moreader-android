package com.moyue.app.ui.components
import android.content.Context
import androidx.compose.ui.res.stringResource
import android.net.Uri
import android.util.Log

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.moyue.app.data.BookRepository
import com.moyue.app.sync.PdfImportManager
import com.moyue.app.sync.SyncClient
import com.moyue.app.sync.WebDavClient
import com.moyue.app.util.DAV_HANDLED_EXTS
import com.moyue.app.util.extOf
import com.moyue.app.util.extractBooksFromZip
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

private fun iconFor(ext: String): ImageVector = when (ext) {
    "epub" -> Icons.Default.Book
    "txt" -> Icons.Default.Description
    "pdf" -> Icons.Default.PictureAsPdf
    "zip" -> Icons.Default.FolderZip
    else -> Icons.Default.InsertDriveFile
}

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
    /** 转换 / 解压中的提示文案，非空时禁止再次点击 */
    var busyLabel by remember { mutableStateOf<String?>(null) }
    /** 压缩包里有多本书时，弹出来让用户挑 */
    var zipPick by remember { mutableStateOf<Pair<File, List<File>>?>(null) }

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

    fun toast(msg: String, long: Boolean = false) {
        android.widget.Toast.makeText(
            context, msg,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
        ).show()
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
                    errorMessage = e.message ?: context.getString(com.moyue.app.R.string.error_parse_failed)
                    isLoading = false
                }
            )
        }
    }

    /** EPUB：入库 + 抽封面 + 恢复同名伴侣元数据（书签/高亮/进度） */
    suspend fun importEpubFromFile(epubFile: File, remotePath: String, repo: BookRepository) {
        val imported = repo.importEpubFile(epubFile)
        val cover = repo.extractCover(imported.id)
        if (cover != null) {
            repo.updateBookCover(imported.id, cover)
        }
        epubFile.delete()

        var restoredBm = 0
        var restoredHl = 0
        if (remotePath.isNotBlank()) {
            val metaPath = remotePath.removeSuffix(".epub").removeSuffix(".EPUB") + ".moreader.json"
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
                    Log.e("WebDAV", "Failed to restore companion metadata", e)
                }
            }
        }

        val extraMsg = if (restoredBm > 0 || restoredHl > 0)
            context.getString(com.moyue.app.R.string.webdav_import_restored_meta, restoredBm, restoredHl) else ""
        toast(context.getString(com.moyue.app.R.string.webdav_imported_success, imported.title, extraMsg))
        onBookImported()
    }

    /** TXT：交给服务端做编码识别 + 智能分章，转成 EPUB 再入库 */
    suspend fun importTxtFile(txtFile: File) {
        val client = SyncClient(context)
        if (!client.isLoggedIn()) {
            toast(context.getString(com.moyue.app.R.string.webdav_txt_need_login), long = true)
            txtFile.delete()
            return
        }
        busyLabel = context.getString(com.moyue.app.R.string.webdav_txt_converting)
        val result = client.convertTxtToEpub(
            txtFile,
            title = txtFile.name.substringBeforeLast('.'),
        )
        busyLabel = null
        txtFile.delete()

        result.fold(
            onSuccess = { r ->
                val tmpEpub = File(context.cacheDir, "webdav_txt_${System.currentTimeMillis()}.epub")
                tmpEpub.writeBytes(r.epub)
                importEpubFromFile(tmpEpub, "", BookRepository(context))
            },
            onFailure = { e ->
                val code = (e as? SyncClient.PdfConvertException)?.code
                val msg = when (code) {
                    "not_text" -> context.getString(com.moyue.app.R.string.webdav_txt_not_text)
                    "empty" -> context.getString(com.moyue.app.R.string.webdav_txt_empty)
                    "too_large" -> context.getString(com.moyue.app.R.string.webdav_txt_too_large)
                    "login_required" -> context.getString(com.moyue.app.R.string.webdav_txt_need_login)
                    else -> context.getString(
                        com.moyue.app.R.string.webdav_txt_failed_fmt,
                        e.message ?: e.javaClass.simpleName
                    )
                }
                toast(msg, long = true)
            }
        )
    }

    /** PDF：投递到现有的云端转换队列（本地导入 PDF 用的是同一条通道） */
    fun submitPdfFile(pdfFile: File) {
        val client = SyncClient(context)
        if (!client.isLoggedIn()) {
            toast(context.getString(com.moyue.app.R.string.webdav_pdf_need_login), long = true)
            pdfFile.delete()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        }.getOrNull()
        if (uri == null) {
            toast(context.getString(com.moyue.app.R.string.webdav_pdf_prepare_failed), long = true)
            pdfFile.delete()
            return
        }
        PdfImportManager.submit(context, listOf(uri), listOf(pdfFile.name))
        toast(context.getString(com.moyue.app.R.string.webdav_pdf_queued), long = true)
        onDismiss()
    }

    /** 本地文件（已下载/已解压）按扩展名分派 */
    suspend fun importLocalBook(file: File, remotePath: String = "") {
        when (extOf(file.name)) {
            "epub" -> importEpubFromFile(file, remotePath, BookRepository(context))
            "txt" -> importTxtFile(file)
            "pdf" -> submitPdfFile(file)
        }
    }

    /** 远端条目：先下载到缓存，再按类型分派 */
    suspend fun handleRemoteItem(item: WebDavClient.DavItem) {
        val ext = extOf(item.name)
        val tmp = File(context.cacheDir, "webdav_${System.currentTimeMillis()}.$ext")
        webDavClient.downloadFile(item.path, tmp).fold(
            onSuccess = { file ->
                when (ext) {
                    "epub", "txt", "pdf" -> importLocalBook(file, item.path)
                    "zip" -> {
                        busyLabel = context.getString(com.moyue.app.R.string.webdav_zip_extracting)
                        val (dir, books) = extractBooksFromZip(context, file)
                        busyLabel = null
                        file.delete()
                        when {
                            books.isEmpty() -> {
                                dir.deleteRecursively()
                                toast(context.getString(com.moyue.app.R.string.webdav_zip_empty), long = true)
                            }
                            books.size == 1 -> {
                                try {
                                    importLocalBook(books[0])
                                } finally {
                                    dir.deleteRecursively()
                                }
                            }
                            else -> zipPick = Pair(dir, books)
                        }
                    }
                }
            },
            onFailure = { err ->
                toast(context.getString(com.moyue.app.R.string.webdav_download_failed_fmt, err.message ?: ""), long = true)
            }
        )
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
                    if (isAtRoot(currentPath)) stringResource(com.moyue.app.R.string.webdav_title) else {
                        val decoded = try { URLDecoder.decode(currentPath, "UTF-8") } catch (e: Exception) { currentPath }
                        "..." + decoded.trimEnd('/').substringAfterLast('/')
                    }
                } else stringResource(com.moyue.app.R.string.webdav_title)
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
                    Icon(Icons.Default.HelpOutline, contentDescription = stringResource(com.moyue.app.R.string.help_title), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isConfigured) {
                    // 配置界面
                    Text(stringResource(com.moyue.app.R.string.webdav_desc_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it; errorMessage = null },
                        label = { Text(stringResource(com.moyue.app.R.string.webdav_server_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it; errorMessage = null },
                        label = { Text(stringResource(com.moyue.app.R.string.webdav_user)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text(stringResource(com.moyue.app.R.string.webdav_pass)) },
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
                                errorMessage = context.getString(com.moyue.app.R.string.webdav_fill_url_and_user)
                                return@Button
                            }
                            webDavClient.saveConfig(serverUrl, user, password)
                            isConfigured = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(com.moyue.app.R.string.webdav_save))
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
                                Text(stringResource(com.moyue.app.R.string.webdav_parent_dir), fontSize = 12.sp)
                            }
                        } else {
                            Text(stringResource(com.moyue.app.R.string.webdav_root_dir), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 设为默认上传目录按钮
                            val isCurrentDefault = defaultUploadDir.isNotBlank() && defaultUploadDir.trimEnd('/') == currentPath.trimEnd('/')
                            TextButton(
                                onClick = {
                                    webDavClient.setDefaultUploadDir(currentPath)
                                    defaultUploadDir = currentPath
                                    android.widget.Toast.makeText(context, context.getString(com.moyue.app.R.string.webdav_set_upload_dir_success), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(if (isCurrentDefault) Icons.Default.CheckCircle else Icons.Default.DriveFolderUpload, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(if (isCurrentDefault) stringResource(com.moyue.app.R.string.webdav_is_upload_dir) else stringResource(com.moyue.app.R.string.webdav_set_upload_dir), fontSize = 11.sp)
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

                    busyLabel?.let { label ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

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
                                Text(stringResource(com.moyue.app.R.string.retry))
                            }
                        }
                    } else {
                        val list = items ?: emptyList()
                        if (list.isEmpty()) {
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(com.moyue.app.R.string.webdav_empty_folder_retry), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
                                    val ext = extOf(item.name)
                                    val isBook = !item.isDirectory && ext in DAV_HANDLED_EXTS
                                    val isDownloadingThis = downloadingPath == item.path
                                    val clickable = downloadingPath == null && busyLabel == null

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clickable(enabled = clickable) {
                                                if (item.isDirectory) {
                                                    refreshList(item.path)
                                                } else if (isBook) {
                                                    downloadingPath = item.path
                                                    scope.launch {
                                                        try {
                                                            handleRemoteItem(item)
                                                        } finally {
                                                            downloadingPath = null
                                                        }
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
                                            } else {
                                                Icon(
                                                    iconFor(ext), null, Modifier.size(18.dp),
                                                    tint = if (isBook) MaterialTheme.colorScheme.secondary
                                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                )
                                            }

                                            Spacer(Modifier.width(8.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    item.name,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = if (item.isDirectory) FontWeight.Medium else FontWeight.Normal,
                                                    color = if (isBook || item.isDirectory) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                )
                                                if (!item.isDirectory && item.size > 0) {
                                                    val mb = item.size / (1024.0 * 1024.0)
                                                    Text(String.format("%.2f MB", mb), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                                }
                                            }

                                            if (isBook && !isDownloadingThis) {
                                                Icon(Icons.Default.Download, contentDescription = stringResource(com.moyue.app.R.string.sync_download), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
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
                        Text(stringResource(com.moyue.app.R.string.webdav_disconnect_btn), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.moyue.app.R.string.close)) }
        }
    )

    // ── 压缩包里的多本书：让用户挑一本 ──
    zipPick?.let { pick ->
        val dir = pick.first
        val books = pick.second
        ZipBookPickerDialog(
            books = books,
            enabled = downloadingPath == null && busyLabel == null,
            onPick = { book ->
                zipPick = null
                scope.launch {
                    try {
                        importLocalBook(book)
                    } finally {
                        dir.deleteRecursively()
                    }
                }
            },
            onDismiss = {
                zipPick = null
                dir.deleteRecursively()
            },
        )
    }

    // ── 帮助与详细教程弹窗 ──
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(com.moyue.app.R.string.sync_help_guide_title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(com.moyue.app.R.string.sync_help_section1_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(com.moyue.app.R.string.sync_help_section1_body),
                        fontSize = 12.sp, lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(com.moyue.app.R.string.sync_help_section2_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(com.moyue.app.R.string.sync_help_section2_body),
                        fontSize = 12.sp, lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(com.moyue.app.R.string.sync_help_section3_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(com.moyue.app.R.string.sync_help_section3_body),
                        fontSize = 12.sp, lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text(stringResource(com.moyue.app.R.string.help_close)) }
            }
        )
    }
}
