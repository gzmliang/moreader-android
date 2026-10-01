package com.moyue.app.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.R
import com.moyue.app.sync.SyncClient
import com.moyue.app.sync.WebDavClient
import kotlinx.coroutines.launch

@Composable
fun SyncSettingsDialog(
    syncClient: SyncClient? = null,
    onDismiss: () -> Unit,
    onUpload: ((onResult: (String) -> Unit) -> Unit)? = null,
    onDownload: ((onResult: (String) -> Unit) -> Unit)? = null,
    onOpenWebDav: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val webDavClient = remember { WebDavClient(context) }

    var preset by remember { mutableStateOf(webDavClient.getPreset()) }
    var serverUrl by remember { mutableStateOf(webDavClient.getServerUrl()) }
    var user by remember { mutableStateOf(webDavClient.getUser()) }
    var password by remember { mutableStateOf(webDavClient.getPassword()) }
    var uploadDir by remember { mutableStateOf(webDavClient.getDefaultUploadDir()) }

    var showPassword by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showDirPicker by remember { mutableStateOf(false) }
    var isConfigured by remember { mutableStateOf(webDavClient.isConfigured()) }
    var syncResultMsg by remember { mutableStateOf<String?>(null) }

    fun applyPreset(newPreset: String) {
        preset = newPreset
        if (newPreset == "jianguo") {
            if (serverUrl.isBlank() || serverUrl.contains(":5244") || serverUrl.contains("/dav/Books")) {
                serverUrl = "https://dav.jianguoyun.com/dav/Moreader"
            }
        } else if (newPreset == "alist") {
            if (serverUrl.isBlank() || serverUrl.contains("jianguoyun.com")) {
                serverUrl = "http://192.168.1.100:5244/dav"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.sync_settings_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = stringResource(R.string.help_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── 网盘类型选择（AList / 坚果云 / 自定义） ──
                Text(
                    stringResource(R.string.sync_default_upload_target),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        "alist" to stringResource(R.string.sync_preset_alist),
                        "jianguo" to stringResource(R.string.sync_preset_jianguo),
                        "custom" to stringResource(R.string.sync_preset_custom),
                    )
                    presets.forEach { (key, label) ->
                        val selected = preset == key
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { applyPreset(key) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // 预设说明小贴士
                Spacer(Modifier.height(6.dp))
                val hintDesc = when (preset) {
                    "alist" -> stringResource(R.string.sync_preset_alist_desc)
                    "jianguo" -> stringResource(R.string.sync_preset_jianguo_desc)
                    else -> stringResource(R.string.sync_preset_custom_desc)
                }
                Text(
                    hintDesc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    lineHeight = 15.sp
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // ── 输入字段 ──
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; testResult = null },
                    label = { Text(stringResource(R.string.webdav_server_url)) },
                    placeholder = {
                        Text(
                            if (preset == "alist") "http://192.168.1.100:5244/dav"
                            else if (preset == "jianguo") "https://dav.jianguoyun.com/dav/Moreader"
                            else "https://nas.example.com/dav"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (serverUrl.isNotEmpty()) {
                            IconButton(onClick = { serverUrl = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null, Modifier.size(16.dp))
                            }
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it; testResult = null },
                    label = { Text(stringResource(R.string.webdav_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; testResult = null },
                    label = {
                        Text(
                            if (preset == "jianguo") stringResource(R.string.webdav_pass) + " (应用密码)"
                            else stringResource(R.string.webdav_pass)
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = uploadDir,
                        onValueChange = { uploadDir = it; testResult = null },
                        label = { Text(stringResource(R.string.sync_dir_label)) },
                        placeholder = { Text(stringResource(R.string.sync_dir_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            if (serverUrl.isBlank() || user.isBlank()) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.sync_please_configure_first),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                // 临时暂存当前配置以便探测
                                webDavClient.saveConfig(serverUrl, user, password, preset, uploadDir)
                                showDirPicker = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.sync_browse_cloud_btn), fontSize = 12.sp)
                    }
                }

                // ── 测试连接与保存按钮 ──
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (serverUrl.isBlank() || user.isBlank()) {
                                val msg = context.getString(R.string.webdav_fill_url_and_user)
                                testResult = msg
                                isTestSuccess = false
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            isTesting = true
                            testResult = null
                            scope.launch {
                                // 临时保存并测试
                                webDavClient.saveConfig(serverUrl, user, password, preset, uploadDir)
                                val res = webDavClient.testConnection()
                                isTesting = false
                                res.fold(
                                    onSuccess = {
                                        isTestSuccess = true
                                        val okMsg = context.getString(R.string.sync_test_success)
                                        testResult = okMsg
                                        isConfigured = true
                                        android.widget.Toast.makeText(context, okMsg, android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    onFailure = { e ->
                                        isTestSuccess = false
                                        val errMsg = context.getString(R.string.sync_test_fail, e.message ?: "")
                                        testResult = errMsg
                                        android.widget.Toast.makeText(context, errMsg, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Default.NetworkCheck, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(if (isTesting) stringResource(R.string.sync_testing) else stringResource(R.string.sync_test_connection), fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (serverUrl.isBlank() || user.isBlank()) {
                                testResult = context.getString(R.string.webdav_fill_url_and_user)
                                isTestSuccess = false
                                return@Button
                            }
                            webDavClient.saveConfig(serverUrl, user, password, preset, uploadDir)
                            isConfigured = true
                            android.widget.Toast.makeText(context, context.getString(R.string.sync_config_saved), android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.sync_settings_save), fontSize = 12.sp)
                    }
                }

                testResult?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = if (isTestSuccess) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                // ── 若已配置：显示高级快捷操作 ──
                if (isConfigured) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = Color(0xFF16A34A))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.webdav_connected),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF16A34A)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 备份全部书籍至网盘
                    Button(
                        onClick = {
                            syncResultMsg = context.getString(R.string.sync_webdav_uploading)
                            onUpload?.invoke { msg -> syncResultMsg = msg }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.sync_backup_all_btn), fontSize = 13.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    // 浏览网盘书库
                    if (onOpenWebDav != null) {
                        OutlinedButton(
                            onClick = onOpenWebDav,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sync_open_webdav_browser), fontSize = 13.sp)
                        }
                    }

                    syncResultMsg?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(Modifier.height(6.dp))

                    // 断开/清除配置
                    TextButton(
                        onClick = {
                            webDavClient.clearConfig()
                            serverUrl = ""
                            user = ""
                            password = ""
                            uploadDir = ""
                            isConfigured = false
                            testResult = null
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.sync_settings_disconnect),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )

    // ── 帮助与详细指南弹窗 ──
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.sync_help_guide_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.sync_help_section1_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sync_help_section1_body),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.sync_help_section2_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sync_help_section2_body),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.sync_help_section3_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sync_help_section3_body),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.help_close))
                }
            }
        )
    }

    // ── 图形化目录选择器弹窗 ──
    if (showDirPicker) {
        WebDavDirPickerDialog(
            webDavClient = webDavClient,
            initialDir = uploadDir,
            onDirSelected = { chosenPath ->
                uploadDir = chosenPath
                showDirPicker = false
                testResult = null
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.sync_selected_dir_fmt, if (chosenPath.isBlank()) "/" else chosenPath),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { showDirPicker = false }
        )
    }
}

/**
 * 专门用于图形化浏览并选取 WebDAV 目录的轻量弹窗
 */
@Composable
fun WebDavDirPickerDialog(
    webDavClient: WebDavClient,
    initialDir: String,
    onDirSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf(initialDir.trim()) }
    var folders by remember { mutableStateOf<List<WebDavClient.DavItem>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val baseUri = remember(webDavClient.getServerUrl()) {
        try { android.net.Uri.parse(webDavClient.getServerUrl()) } catch (e: Exception) { null }
    }
    val rootDavPath = remember(baseUri) {
        (baseUri?.path ?: "").trimEnd('/')
    }

    fun isAtRoot(path: String): Boolean {
        val p = path.trimEnd('/')
        return p.isBlank() || p == rootDavPath || p == "/"
    }

    fun loadFolders(path: String) {
        isLoading = true
        errorMessage = null
        currentPath = path
        scope.launch {
            webDavClient.listFiles(path).fold(
                onSuccess = { list ->
                    // 仅列出目录文件夹，排除文件和隐藏伴侣
                    folders = list.filter { it.isDirectory && !it.name.startsWith(".") }
                    isLoading = false
                },
                onFailure = { e ->
                    errorMessage = e.message ?: context.getString(R.string.error_parse_failed)
                    isLoading = false
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        loadFolders(currentPath)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.sync_browse_cloud_dir),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
            ) {
                // 当前路径导航栏与返回上一级
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isAtRoot(currentPath)) stringResource(R.string.webdav_root_dir) else currentPath,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (!isAtRoot(currentPath)) {
                            TextButton(
                                onClick = {
                                    val p = currentPath.trimEnd('/')
                                    val parent = p.substringBeforeLast('/', "")
                                    if (parent.isBlank() || parent == rootDavPath) {
                                        loadFolders("")
                                    } else {
                                        loadFolders(parent)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.ArrowBack, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(stringResource(R.string.webdav_parent_dir), fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 内容列表
                if (isLoading) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                } else if (errorMessage != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .height(180.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { loadFolders(currentPath) }) {
                            Text(stringResource(R.string.retry), fontSize = 12.sp)
                        }
                    }
                } else {
                    val list = folders ?: emptyList()
                    if (list.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.sync_no_subfolders),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            for (item in list) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clickable {
                                            loadFolders(item.path)
                                        },
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selected = if (isAtRoot(currentPath)) "" else currentPath
                    onDirSelected(selected)
                }
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sync_select_this_dir), fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), fontSize = 12.sp)
            }
        }
    )
}
