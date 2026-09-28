package com.moyue.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.util.extOf
import java.io.File

private fun iconForExt(ext: String): ImageVector = when (ext) {
    "epub" -> Icons.Default.Book
    "txt" -> Icons.Default.Description
    "pdf" -> Icons.Default.PictureAsPdf
    "zip" -> Icons.Default.FolderZip
    else -> Icons.Default.InsertDriveFile
}

/** 压缩包里有多本电子书时，让用户挑一本（网盘书库与本机导入共用） */
@Composable
fun ZipBookPickerDialog(
    books: List<File>,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(com.moyue.app.R.string.webdav_zip_pick_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for (b in books) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable(enabled = enabled) { onPick(b) },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                iconForExt(extOf(b.name)), null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(b.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.moyue.app.R.string.close)) }
        },
    )
}
