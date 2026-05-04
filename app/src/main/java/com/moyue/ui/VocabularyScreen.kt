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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    
    // Check if this vocab has new structured data
    val hasStructuredData = vocab.chineseDef != null || vocab.englishDef != null
    val hasLegacyData = vocab.definition != null
    
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
            // Header row: word + speaker + delete
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = vocab.word,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
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
            
            // Definition section
            if (showDefinition) {
                if (hasStructuredData) {
                    StructuredDefinition(vocab)
                } else if (hasLegacyData) {
                    LegacyDefinition(vocab)
                }
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!hasStructuredData) {
                        TextButton(
                            onClick = { onFetchDefinition() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔄 重新拉取释义")
                        }
                    }
                    TextButton(
                        onClick = { showDefinition = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.vocabulary_hide_definition))
                    }
                }
            } else {
                // Collapsed state: show preview or fetch button
                if (hasStructuredData) {
                    Text(
                        text = vocab.chineseDef?.split("\n")?.firstOrNull()?.replace(Regex("^\\d+\\.\\s*"), "")
                            ?: vocab.englishDef?.split("\n")?.firstOrNull()?.replace(Regex("^\\d+\\.\\s*"), "")
                            ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(onClick = { showDefinition = true }) {
                        Text(stringResource(R.string.vocabulary_show_definition))
                    }
                } else if (hasLegacyData) {
                    TextButton(onClick = { showDefinition = true }) {
                        Text(stringResource(R.string.vocabulary_show_definition))
                    }
                } else {
                    TextButton(onClick = { onFetchDefinition(); showDefinition = true }) {
                        Text(stringResource(R.string.vocabulary_show_definition))
                    }
                }
            }
            
            // Date
            Text(
                text = stringResource(R.string.bookmark_added_at, dateFormat.format(Date(vocab.createdAt))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun StructuredDefinition(vocab: Vocabulary) {
    // Pronunciation + part of speech line
    val posLine = listOfNotNull(
        vocab.pronunciation,
        vocab.partOfSpeech?.let { "[${it}]" }
    ).joinToString("  ")
    
    if (posLine.isNotEmpty()) {
        Text(
            text = posLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
    
    // Chinese definition
    if (vocab.chineseDef != null) {
        SectionLabel("【中文释义】")
        Text(
            text = vocab.chineseDef,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
    }
    
    // English definition
    if (vocab.englishDef != null) {
        SectionLabel("【English Definition】")
        Text(
            text = vocab.englishDef,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
    }
    
    // Word forms / 组词
    val wordForms = try {
        if (vocab.wordForms != null) {
            org.json.JSONArray(vocab.wordForms).let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
            }
        } else emptyList()
    } catch (e: Exception) { emptyList() }
    
    if (wordForms.isNotEmpty()) {
        SectionLabel("【组词】Word Formation")
        wordForms.forEach { form ->
            BulletText(form)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    
    // Example sentence
    val example = try {
        if (vocab.exampleJson != null) {
            org.json.JSONObject(vocab.exampleJson)
        } else null
    } catch (e: Exception) { null }
    
    if (example != null) {
        SectionLabel("【例句】Example")
        Text(
            text = example.optString("text", ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = example.optString("translation", ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun LegacyDefinition(vocab: Vocabulary) {
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
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun BulletText(text: String) {
    Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
        Text("• ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
