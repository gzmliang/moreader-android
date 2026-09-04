package com.moyue.app.ui.components

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.data.BookRepository
import com.moyue.app.sync.WebDavClient
import kotlinx.coroutines.launch
import java.io.File

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

    fun refreshList(path: String) {
        isLoading = true
        errorMessage = null
        currentPath = path
        scope.launch {
            webDavClient.listFiles(path).fold(
                onSuccess = { list ->
                    items = list
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
                Text(
                    if (isConfigured) {
                        if (currentPath.isBlank()) "WebDAV 网盘" else "WebDAV: .../${currentPath.trimEnd('/').substringAfterLast('/')}"
                    } else "连接 WebDAV 网盘",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                        placeholder = { Text("如 http://192.168.199.159:6355/dav") },
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
                        if (currentPath.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
                                    refreshList(parent)
                                }
                            ) {
                                Icon(Icons.Default.ArrowBack, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("上一级", fontSize = 12.sp)
                            }
                        } else {
                            Text("根目录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }

                        IconButton(
                            onClick = { refreshList(currentPath) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
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
                                // 文件夹排在前面，epub 电子书排后面
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
                                                                android.widget.Toast.makeText(context, "已导入: ${imported.title}", android.widget.Toast.LENGTH_SHORT).show()
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
}
