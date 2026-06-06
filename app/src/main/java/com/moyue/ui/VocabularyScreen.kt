package com.moyue.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
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
    val currentPlan by viewModel.currentPlan.collectAsStateWithLifecycle()
    val planNames by viewModel.planNames.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportMenu by remember { mutableStateOf(false) }
    
    // Multi-select mode for import
    var isSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    
    // Plan selector for imports
    var showPlanPicker by remember { mutableStateOf(false) }
    var pendingImportWords by remember { mutableStateOf<List<Vocabulary>>(emptyList()) }
    var importPlanOptions by remember { mutableStateOf<List<String>>(listOf(context.getString(com.moyue.app.R.string.flashcard_plan_default))) }

    // Custom word add dialog
    var showAddDialog by remember { mutableStateOf(false) }
    var addWordText by remember { mutableStateOf("") }

    // Vocab notebook plan dialogs
    var showNewPlanDialog by remember { mutableStateOf(false) }
    var showDeletePlanDialog by remember { mutableStateOf(false) }
    var planNameInput by remember { mutableStateOf("") }

    // Notebook PlanSelector
    @Composable
    fun VocabPlanSelector() {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(planNames) { plan ->
                    val isSelected = plan == currentPlan
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.switchPlan(plan) },
                        label = {
                            Text(
                                if (plan == "默认") stringResource(R.string.flashcard_plan_default) else plan,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        },
                        trailingIcon = if (isSelected && plan != "默认") {
                            {
                                IconButton(
                                    onClick = { showDeletePlanDialog = true },
                                    modifier = Modifier.size(14.dp),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(10.dp))
                                }
                            }
                        } else null,
                    )
                }
            }
            FilledTonalIconButton(
                onClick = { planNameInput = ""; showNewPlanDialog = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.flashcard_plan_new), modifier = Modifier.size(16.dp))
            }
        }
    }
    
    // Load plan options when picker is about to show
    fun openPlanPicker(words: List<Vocabulary>) {
        scope.launch {
            val dataStore = com.moyue.app.data.FlashcardDataStore(context)
            val plans = dataStore.getPlanNames().ifEmpty { listOf(context.getString(com.moyue.app.R.string.flashcard_plan_default)) }
            importPlanOptions = plans
            pendingImportWords = words
            showPlanPicker = true
        }
    }
    
    // Plan picker dialog
    if (showPlanPicker) {
        AlertDialog(
            onDismissRequest = { showPlanPicker = false },
            title = { Text(stringResource(R.string.flashcard_import_plan_title)) },
            text = {
                Column {
                    importPlanOptions.forEach { plan ->
                        TextButton(
                            onClick = {
                                showPlanPicker = false
                                // Perform import
                                scope.launch {
                                    val dataStore = com.moyue.app.data.FlashcardDataStore(context)
                                    var imported = 0
                                    var skipped = 0
                                    pendingImportWords.forEach { vocab ->
                                        var exampleText: String? = null
                                        var exampleTranslation: String? = null
                                        if (!vocab.exampleJson.isNullOrEmpty()) {
                                            try {
                                                val ex = org.json.JSONObject(vocab.exampleJson)
                                                exampleText = ex.optString("text", null)
                                                exampleTranslation = ex.optString("translation", null)
                                            } catch (_: Exception) {}
                                        }
                                        val card = com.moyue.app.data.FlashcardDataStore.Flashcard(
                                            id = dataStore.generateId(), word = vocab.word,
                                            pronunciation = vocab.pronunciation, partOfSpeech = vocab.partOfSpeech,
                                            chineseDef = vocab.chineseDef, englishDef = vocab.englishDef,
                                            exampleText = exampleText, exampleTranslation = exampleTranslation,
                                            dueDate = System.currentTimeMillis(),
                                            plan = plan,
                                        )
                                        if (dataStore.addFlashcard(card)) imported++ else skipped++
                                    }
                                    val msg = if (pendingImportWords.size > 1) {
                                        context.getString(R.string.flashcard_import_result, imported, skipped)
                                    } else if (imported > 0) {
                                        context.getString(R.string.flashcard_import_success)
                                    } else {
                                        context.getString(R.string.flashcard_import_exists)
                                    }
                                    snackbarHostState.showSnackbar("$msg → ${plan}")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(plan, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlanPicker = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    // Add word dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                addWordText = ""
            },
            title = { Text(stringResource(R.string.add_word)) },
            text = {
                OutlinedTextField(
                    value = addWordText,
                    onValueChange = { addWordText = it },
                    label = { Text(stringResource(R.string.add_word)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addCustomWord(addWordText) { success, message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                        showAddDialog = false
                        addWordText = ""
                    }
                ) {
                    Text(stringResource(R.string.action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    addWordText = ""
                }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // New vocab notebook dialog
    if (showNewPlanDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlanDialog = false },
            title = { Text(stringResource(R.string.vocab_notebook_create_title)) },
            text = {
                OutlinedTextField(
                    value = planNameInput,
                    onValueChange = { planNameInput = it },
                    placeholder = { Text(stringResource(R.string.vocab_notebook_create_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (planNameInput.isNotBlank()) {
                        viewModel.createPlan(context, planNameInput.trim())
                        showNewPlanDialog = false
                        planNameInput = ""
                    }
                }) { Text(stringResource(R.string.vocab_notebook_create_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlanDialog = false; planNameInput = "" }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    // Delete vocab notebook dialog
    if (showDeletePlanDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePlanDialog = false },
            title = { Text(stringResource(R.string.vocab_notebook_delete_title)) },
            text = { Text(stringResource(R.string.vocab_notebook_delete_confirm, currentPlan)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deletePlan(context, currentPlan); showDeletePlanDialog = false }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePlanDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Bottom action bar in select mode
            if (isSelectMode && selectedIds.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.vocab_selected_count, selectedIds.size),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                selectedIds = vocabulary.map { it.id }.toSet()
                            }) {
                                Text(stringResource(R.string.vocab_select_all), fontSize = 13.sp)
                            }
                            FilledTonalButton(onClick = {
                                val selected = vocabulary.filter { selectedIds.contains(it.id) }
                                openPlanPicker(selected)
                            }) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.vocab_import_selected), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vocabulary_title), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Select mode toggle
                    if (!isSelectMode) {
                        // Add custom word
                    IconButton(onClick = {
                        addWordText = ""
                        showAddDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_word))
                    }
                    // Batch import ALL to flashcards
                        IconButton(onClick = { openPlanPicker(vocabulary) }) {
                            Icon(Icons.Default.Bolt, contentDescription = stringResource(R.string.flashcard_batch_import))
                        }
                        // Enter select mode
                        IconButton(onClick = { isSelectMode = true }) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.vocab_select_mode))
                        }
                    } else {
                        // In select mode: show count, import selected, cancel
                        Text(
                            "${selectedIds.size}/${vocabulary.size}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        if (selectedIds.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    val selected = vocabulary.filter { selectedIds.contains(it.id) }
                                    openPlanPicker(selected)
                                }
                            ) {
                                Text(stringResource(R.string.vocab_import_selected), fontSize = 13.sp)
                            }
                        }
                        IconButton(onClick = { isSelectMode = false; selectedIds = emptySet() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                        }
                    }
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
        Column(modifier = Modifier.padding(padding)) {
            // Plan selector
            VocabPlanSelector()
            Spacer(Modifier.height(2.dp))

            if (vocabulary.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.vocabulary_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                items(vocabulary, key = { it.id }) { item ->
                    val isSelected: Boolean = selectedIds.contains(item.id)
                    VocabularyItem(
                        vocab = item,
                        isSpeaking = speakingWordId == item.id,
                        isSelectMode = isSelectMode,
                        isSelected = isSelected,
                        onToggleSelect = {
                            selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                        },
                        onSpeak = { viewModel.speakWord(item.id, item.word, context) },
                        onDelete = { viewModel.deleteVocabulary(item.id) },
                        onFetchDefinition = { viewModel.fetchDefinition(item.id, item.word, context) { success, message ->
                            scope.launch {
                                if (!success) snackbarHostState.showSnackbar(message)
                            }
                        }},
                        onAddToFlashcard = { vocab ->
                            openPlanPicker(listOf(vocab))
                        }
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun VocabularyItem(
    vocab: Vocabulary,
    isSpeaking: Boolean,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
    onFetchDefinition: () -> Unit,
    onAddToFlashcard: (Vocabulary) -> Unit
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
            .clickable(enabled = isSelectMode) { onToggleSelect() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox (visible only in select mode)
                if (isSelectMode) {
                    IconButton(onClick = { onToggleSelect() }) {
                        Icon(
                            if (isSelected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                Text(
                    text = vocab.word,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                if (!isSelectMode) {
                    // Single-word flashcard import
                    IconButton(onClick = { onAddToFlashcard(vocab) }) {
                        Icon(Icons.Default.Bolt, contentDescription = stringResource(R.string.flashcard_batch_import), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                    }
                }
                
                IconButton(
                    onClick = { onSpeak() },
                    enabled = !isSpeaking,
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_speak_word),
                        tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                if (!isSelectMode) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
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
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_refresh_definition))
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
        SectionLabel(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_section_chinese_def))
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
        SectionLabel(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_section_word_formation))
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
        SectionLabel(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_section_example))
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
