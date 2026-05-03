package com.moyue.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyue.app.R
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.Vocabulary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(
    repository: BookRepository,
    onBack: () -> Unit,
    viewModel: VocabularyViewModel = viewModel(
        factory = VocabularyViewModelFactory(repository)
    )
) {
    val vocabulary by viewModel.vocabulary.collectAsStateWithLifecycle()
    val speakingWordId by viewModel.isSpeakingWord.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportMenu by remember { mutableStateOf(false) }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vocabulary_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.vocabulary_export_csv))
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vocabulary_export_csv)) },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportVocabulary(context, "csv") { success, message ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(message)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vocabulary_export_md)) },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportVocabulary(context, "md") { success, message ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(message)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (vocabulary.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.vocabulary_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(vocabulary, key = { it.id }) { item ->
                    VocabularyItem(
                        vocab = item,
                        isSpeaking = speakingWordId == item.id,
                        onSpeak = { viewModel.speakWord(item.id, item.word, context) },
                        onDelete = { viewModel.deleteVocabulary(item.id) },
                        onFetchDefinition = { viewModel.fetchDefinition(item.id, item.word, context) { success, message ->
                            scope.launch {
                                if (!success) snackbarHostState.showSnackbar(message)
                            }
                        }}
                    )
                }
            }
        }
    }
}

@Composable
private fun VocabularyItem(
    vocab: Vocabulary,
    isSpeaking: Boolean,
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
    onFetchDefinition: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var showDefinition by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = vocab.word,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(
                    onClick = { onSpeak() },
                    enabled = !isSpeaking,
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "朗读单词",
                        tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            if (showDefinition) {
                if (vocab.pronunciation != null) {
                    Text(
                        text = vocab.pronunciation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                if (vocab.partOfSpeech != null && vocab.definition != null) {
                    Text(
                        text = "${vocab.partOfSpeech} ${vocab.definition}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else if (vocab.definition != null) {
                    Text(
                        text = vocab.definition,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                if (vocab.example != null) {
                    Text(
                        text = vocab.example,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                TextButton(
                    onClick = { showDefinition = false },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.vocabulary_hide_definition))
                }
            } else {
                if (vocab.definition == null) {
                    TextButton(
                        onClick = {
                            onFetchDefinition()
                            showDefinition = true
                        }
                    ) {
                        Text(stringResource(R.string.vocabulary_show_definition))
                    }
                } else {
                    TextButton(
                        onClick = { showDefinition = true }
                    ) {
                        Text(stringResource(R.string.vocabulary_show_definition))
                    }
                }
            }
            
            Text(
                text = stringResource(R.string.bookmark_added_at, dateFormat.format(Date(vocab.createdAt))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
