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
    onOpenWebDav: (() -> Unit)? = null,
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
    var loggedInVersion by remember { mutableStateOf(0) }
    var showHelpDialog by remember { mutableStateOf(false) }

    var defaultCloudTarget by remember { mutableStateOf(webDavClient.getDefaultCloudTarget()) }

    val localIsLoggedIn by remember { derivedStateOf { loggedInVersion >= 0 && syncClient.isLoggedIn() } }
    val localLoggedEmail by remember { derivedStateOf { syncClient.getEmail() } }
    val isLoggedIn = localIsLoggedIn
    val loggedEmail = localLoggedEmail

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_settings_title), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.HelpOutline, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_title), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_default_upload_target), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = defaultCloudTarget == "MOYUE",
                                onClick = {
                                    defaultCloudTarget = "MOYUE"
                                    webDavClient.setDefaultCloudTarget("MOYUE")
                                }
                            )
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_target_moyue), fontSize = 12.sp, modifier = Modifier.clickable {
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
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_target_webdav), fontSize = 12.sp, modifier = Modifier.clickable {
                                defaultCloudTarget = "WEBDAV"
                                webDavClient.setDefaultCloudTarget("WEBDAV")
                            })
                        }
                        if (defaultCloudTarget == "WEBDAV") {
                            val curUploadDir = webDavClient.getDefaultUploadDir()
                            Text(
                                if (curUploadDir.isBlank()) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_webdav_dir_root)
                                else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_webdav_dir_custom, curUploadDir),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                if (defaultCloudTarget == "WEBDAV") {
                    if (webDavClient.isConfigured()) {
                        Icon(Icons.Default.Storage, null,
                            modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.webdav_connected),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_webdav_configured_desc, webDavClient.getServerUrl()),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(12.dp))

                        // 上传全部书籍到 WebDAV
                        Button(
                            onClick = {
                                syncResult = context.getString(com.moyue.app.R.string.sync_webdav_uploading)
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
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_to_webdav_btn))
                        }

                        Spacer(Modifier.height(8.dp))

                        // 打开 WebDAV 网盘书库
                        if (onOpenWebDav != null) {
                            OutlinedButton(
                                onClick = onOpenWebDav,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_open_webdav_browser))
                            }
                        }
                    } else {
                        // 未配置 WebDAV
                        Icon(Icons.Default.Storage, null,
                            modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally),
                            tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.webdav_not_configured),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(10.dp))
                        if (onOpenWebDav != null) {
                            Button(
                                onClick = onOpenWebDav,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.webdav_save))
                            }
                        }
                    }

                    syncResult?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(msg, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                } else if (isLoggedIn) {
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

                    Spacer(Modifier.height(4.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_moved_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )

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
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_help_guide_title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_help_section1_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_help_section1_body),
                        fontSize = 12.sp, lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_help_section2_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_help_section2_body),
                        fontSize = 12.sp, lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_help_section3_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_help_section3_body),
                        fontSize = 12.sp, lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_close)) }
            }
        )
    }

}
