package com.moyue.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moyue.app.R
import com.moyue.app.tts.TtsRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS Recording UI — independent module, no changes to existing reader/playback code.
 *
 * Two entry points:
 * 1. RecordDialog — modal dialog for recording a chapter or full book
 * 2. RecordingManagerScreen — browse/manage saved recordings
 */

// ===== State =====

sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(
        val chapterLabel: String,
        val isFullBook: Boolean,
    ) : RecordingState()
    data class Progress(
        val chapterLabel: String,
        val isFullBook: Boolean,
        val progress: TtsRecorder.Progress,
    ) : RecordingState()
    data class Done(
        val file: File,
        val segments: Int,
        val sizeKB: Long,
    ) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

// ===== Recording Dialog =====

/**
 * Recording dialog — shown when user taps "Record TTS Audio" in settings.
 * Lets user choose: record current chapter OR record full book.
 */
@Composable
fun RecordDialog(
    bookTitle: String,
    currentChapterLabel: String,
    totalChapters: Int,
    currentChapterIndex: Int,
    onDismiss: () -> Unit,
    onStartRecording: (isFullBook: Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "🎙️ ${stringResource(R.string.recording_title)}",
                    style = MaterialTheme.typography.titleLarge,
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

                // Option 1: Current chapter
                FilledTonalButton(
                    onClick = { onStartRecording(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("📖 ${stringResource(R.string.recording_current_chapter)}")
                        Text(
                            currentChapterLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Option 2: Full book
                FilledTonalButton(
                    onClick = { onStartRecording(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("📚 ${stringResource(R.string.recording_full_book)}")
                        Text(
                            stringResource(R.string.recording_chapters_count, totalChapters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.recording_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}

/**
 * Recording progress dialog — shown during active recording.
 */
@Composable
fun RecordingProgressDialog(
    state: RecordingState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onPlayRecording: (File) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playFile = { file: File ->
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/mpeg")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
    Dialog(
        onDismissRequest = { if (state is RecordingState.Done || state is RecordingState.Error) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    is RecordingState.Idle -> {}

                    is RecordingState.Recording -> {
                        Text("🎙️", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.recording_in_progress, state.chapterLabel),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onCancel) {
                            Text(stringResource(R.string.recording_cancel))
                        }
                    }

                    is RecordingState.Progress -> {
                        val p = state.progress
                        Text("🎙️", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.isFullBook)
                                stringResource(R.string.recording_full_book_progress, state.chapterLabel)
                            else
                                stringResource(R.string.recording_chapter_progress, state.chapterLabel),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(16.dp))

                        // Progress bar
                        LinearProgressIndicator(
                            progress = { p.percentage / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))

                        Text(
                            stringResource(
                                R.string.recording_segment_info,
                                p.currentSegment,
                                p.totalSegments,
                                p.percentage,
                                p.bytesWritten / (1024f * 1024f),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (p.currentText.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "\"${p.currentText}...\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onCancel) {
                            Text(stringResource(R.string.recording_cancel))
                        }
                    }

                    is RecordingState.Done -> {
                        Text("✅", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.recording_done),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                R.string.recording_done_info,
                                state.segments,
                                state.sizeKB,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "📁 ${state.file.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { playFile(state.file) }) {
                                Text("▶️ ${stringResource(R.string.recording_play)}")
                            }
                            Button(onClick = onDismiss) {
                                Text(stringResource(R.string.recording_close))
                            }
                        }
                    }

                    is RecordingState.Error -> {
                        Text("❌", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.recording_error),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.recording_close))
                        }
                    }
                }
            }
        }
    }
}

// ===== Recording Manager =====

/** Simple data class for recording list items */
data class RecordingItem(
    val file: File,
    val bookTitle: String,
    val chapterLabel: String?,
    val sizeKB: Long,
    val createdAt: Long,
)

/**
 * Recording manager screen — browse, play, and delete saved TTS recordings.
 */
@Composable
fun RecordingManagerScreen(
    recordings: List<RecordingItem>,
    onPlay: (File) -> Unit,
    onDelete: (File) -> Unit,
    onShare: (File) -> Unit = {},
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "🎙️ ${stringResource(R.string.recording_manager_title)}",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.recording_back))
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📭", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.recording_no_recordings),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recordings) { item ->
                    com.moyue.app.ui.components.RecordingListItem(
                        item = item,
                        onPlay = { onPlay(item.file) },
                        onDelete = { onDelete(item.file) },
                        onShare = { onShare(item.file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingListItem(
    item: RecordingItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎵", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.file.name.replace(".mp3", ""),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    "${item.sizeKB} KB · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(item.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.chapterLabel != null) {
                    Text(
                        item.chapterLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onPlay) {
                    Text("▶️")
                }
                FilledTonalButton(onClick = onShare) {
                    Text("📤")
                }
                OutlinedButton(onClick = onDelete) {
                    Text("🗑️")
                }
            }
        }
    }
}
