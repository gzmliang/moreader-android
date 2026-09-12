package com.moyue.ai.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiPlotResult
import com.moyue.ai.model.CharacterCard
import com.moyue.ai.model.PlotStage
import com.moyue.ai.model.StructuredRelation
import com.moyue.ai.service.AiPromptBuilder
import com.moyue.ai.service.BookTextExtractor
import com.moyue.ai.service.LlmClient
import com.moyue.app.R
import com.moyue.app.data.BookRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlotMapTabContent(
    bookId: String,
    bookTitle: String,
    chapterIndex: Int,
    chapterTitle: String,
    chapterText: String,
    repository: AiCacheRepository,
    config: AiConfig,
    isEink: Boolean,
    textSizeSp: Float,
    displayMode: String = "bilingual",
    bookRepository: BookRepository? = null
) {
    val scope = rememberCoroutineScope()
    var selectedScope by remember { mutableStateOf("chapter") } // "chapter" or "book"

    val isChineseBook = remember(bookTitle, chapterText) {
        val sample = (bookTitle + " " + chapterText.take(1000))
        val count = sample.count { it in '\u4e00'..'\u9fff' }
        count >= 5 || (sample.isNotBlank() && count.toDouble() / sample.length > 0.1) || com.moyue.tts.LanguageVoiceDetector.detectLanguage(sample) == "zh"
    }

    fun isStalePlotResult(result: AiPlotResult?, isChinese: Boolean): Boolean {
        if (result == null || !isChinese) return false
        val chars = result.characters
        if (chars.isNotEmpty()) {
            val englishOrigCount = chars.count { card ->
                card.nameOriginal.isNotBlank() &&
                card.nameOriginal.all { it.code < 128 } &&
                card.nameOriginal.any { it.isLetter() }
            }
            if (englishOrigCount.toDouble() / chars.size > 0.3) {
                return true
            }
        }
        if (result.coreDynamicsOriginal.isNotBlank() &&
            !result.coreDynamicsOriginal.any { it in '\u4e00'..'\u9fff' } &&
            (result.coreDynamicsTranslation.any { it in '\u4e00'..'\u9fff' } || isChinese)
        ) {
            return true
        }
        return false
    }

    var plotResult by remember {
        val initial = repository.getPlot(bookId, chapterIndex, selectedScope)
        if (isStalePlotResult(initial, isChineseBook)) {
            repository.clearPlot(bookId, chapterIndex, selectedScope)
            mutableStateOf<AiPlotResult?>(null)
        } else {
            mutableStateOf(initial)
        }
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isCoreExpanded by remember { mutableStateOf(false) }

    // Faction filter, search query, and BottomSheet character selection
    var selectedFaction by remember { mutableStateOf<String?>(null) }
    var characterSearchQuery by remember { mutableStateOf("") }
    var selectedCharacter by remember { mutableStateOf<CharacterCard?>(null) }

    fun doGeneratePlot() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val textToAnalyze = if (selectedScope == "book") {
                BookTextExtractor.extractBookOverview(bookRepository, bookId, bookTitle, chapterText)
            } else {
                chapterText
            }
            val promptTitle = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle"
            val (sys, usr) = AiPromptBuilder.buildPlotPrompt(
                config = config,
                title = promptTitle,
                text = textToAnalyze,
                scope = selectedScope
            )
            val res = LlmClient().chatCompletion(config, sys, usr, responseJson = true)
            isLoading = false
            res.fold(
                onSuccess = { json ->
                    val parsed = AiPromptBuilder.parsePlotResponse(json, bookId, chapterIndex, selectedScope)
                    repository.savePlot(parsed)
                    plotResult = parsed
                },
                onFailure = { errorMessage = it.localizedMessage }
            )
        }
    }

    LaunchedEffect(bookId, chapterIndex, selectedScope) {
        val cached = repository.getPlot(bookId, chapterIndex, selectedScope)
        if (isStalePlotResult(cached, isChineseBook)) {
            repository.clearPlot(bookId, chapterIndex, selectedScope)
            plotResult = null
            doGeneratePlot()
        } else {
            plotResult = cached
        }
        selectedFaction = null
        characterSearchQuery = ""
        selectedCharacter = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls Header
        Surface(
            color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Scope selector
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = (selectedScope == "chapter"),
                        onClick = { selectedScope = "chapter" },
                        label = { Text(stringResource(R.string.ai_scope_chapter), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = (selectedScope == "book"),
                        onClick = { selectedScope = "book" },
                        label = { Text(stringResource(R.string.ai_scope_book), fontSize = 12.sp) }
                    )
                }

                if (plotResult != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "⚡" + stringResource(R.string.ai_cached_short),
                            fontSize = 11.sp,
                            color = if (isEink) Color.Black else Color(0xFF10B981),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.ai_regenerate_btn),
                            fontSize = 11.sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                doGeneratePlot()
                            }
                        )
                    }
                }
            }
        }

        // Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.ai_generating),
                            fontSize = 14.sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.ai_error_format, errorMessage ?: ""),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                }
                plotResult != null -> {
                    val result = plotResult!!
                    val allCharacters = result.characters
                    val factions = remember(allCharacters) {
                        allCharacters.map { it.faction }.filter { it.isNotBlank() }.distinct()
                    }
                    val filteredCharacters = remember(allCharacters, selectedFaction, characterSearchQuery) {
                        allCharacters.filter { card ->
                            val matchesFaction = selectedFaction.isNullOrBlank() || card.faction == selectedFaction
                            val matchesSearch = characterSearchQuery.isBlank() ||
                                    card.nameOriginal.contains(characterSearchQuery, ignoreCase = true) ||
                                    card.nameTranslation.contains(characterSearchQuery, ignoreCase = true) ||
                                    card.faction.contains(characterSearchQuery, ignoreCase = true) ||
                                    card.role.contains(characterSearchQuery, ignoreCase = true) ||
                                    card.bioOriginal.contains(characterSearchQuery, ignoreCase = true) ||
                                    card.bioTranslation.contains(characterSearchQuery, ignoreCase = true)
                            if (characterSearchQuery.isNotBlank()) matchesSearch else (matchesFaction && matchesSearch)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                    ) {
                        // 1. Character Relationship Cards & Lineage (第一核心视觉重心：置顶人物角色图谱)
                        if (allCharacters.isNotEmpty()) {
                            item {
                                SectionHeader(title = stringResource(R.string.ai_plot_relations_title), isEink = isEink)

                                // Character Search Bar
                                OutlinedTextField(
                                    value = characterSearchQuery,
                                    onValueChange = { characterSearchQuery = it },
                                    placeholder = {
                                        Text(stringResource(R.string.ai_character_search_hint), fontSize = 12.sp)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    trailingIcon = {
                                        if (characterSearchQuery.isNotBlank()) {
                                            IconButton(onClick = { characterSearchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    )
                                )

                                // Faction Filter Chips (if more than 1 faction and not actively searching)
                                if (factions.size > 1 && characterSearchQuery.isBlank()) {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                    ) {
                                        item {
                                            FilterChip(
                                                selected = (selectedFaction == null),
                                                onClick = { selectedFaction = null },
                                                label = {
                                                    Text(
                                                        stringResource(R.string.ai_filter_all_factions) + " (${allCharacters.size})",
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            )
                                        }
                                        items(factions) { faction ->
                                            val count = allCharacters.count { it.faction == faction }
                                            FilterChip(
                                                selected = (selectedFaction == faction),
                                                onClick = {
                                                    selectedFaction = if (selectedFaction == faction) null else faction
                                                },
                                                label = {
                                                    Text("$faction ($count)", fontSize = 11.sp)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            items(filteredCharacters, key = { it.nameOriginal + it.nameTranslation }) { card ->
                                CharacterCardItem(
                                    card = card,
                                    isEink = isEink,
                                    textSizeSp = textSizeSp,
                                    displayMode = displayMode,
                                    isChineseBook = isChineseBook,
                                    onClick = { selectedCharacter = card }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            item { Spacer(modifier = Modifier.height(10.dp)) }
                        }

                        // 2. Narrative Core & Stakes (收敛为精简可折叠小卡片)
                        val showCoreOrig = (displayMode == "bilingual" || displayMode == "orig" || result.coreDynamicsTranslation.isBlank()) && result.coreDynamicsOriginal.isNotBlank()
                        val showCoreTrans = (displayMode == "bilingual" || displayMode == "target") && result.coreDynamicsTranslation.isNotBlank()

                        if (showCoreOrig || showCoreTrans) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { isCoreExpanded = !isCoreExpanded }
                                        .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.ai_plot_core_title),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = if (isCoreExpanded) stringResource(R.string.ai_collapse) else stringResource(R.string.ai_expand),
                                                    fontSize = 11.sp,
                                                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.primary
                                                )
                                                Icon(
                                                    imageVector = if (isCoreExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        if (isCoreExpanded) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            val coreOrigText = if (isChineseBook && !result.coreDynamicsOriginal.any { it in '\u4e00'..'\u9fff' } && result.coreDynamicsTranslation.any { it in '\u4e00'..'\u9fff' }) {
                                                result.coreDynamicsTranslation
                                            } else {
                                                result.coreDynamicsOriginal
                                            }
                                            if (showCoreOrig && coreOrigText.isNotBlank()) {
                                                Text(
                                                    text = coreOrigText,
                                                    fontSize = textSizeSp.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            if (showCoreTrans && result.coreDynamicsTranslation.isNotBlank() && result.coreDynamicsTranslation != coreOrigText) {
                                                if (showCoreOrig && coreOrigText.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = result.coreDynamicsTranslation,
                                                    fontSize = (textSizeSp * 0.92f).sp,
                                                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                        }

                        // 3. Chronological Plotline
                        if (result.timeline.isNotEmpty()) {
                            item {
                                SectionHeader(title = stringResource(R.string.ai_plot_timeline_title), isEink = isEink)
                            }
                            items(result.timeline) { stage ->
                                TimelineStageItem(
                                    stage = stage,
                                    isEink = isEink,
                                    textSizeSp = textSizeSp,
                                    displayMode = displayMode,
                                    isChineseBook = isChineseBook
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }

                    // 4. Character Profile & Lineage ModalBottomSheet (方式B)
                    if (selectedCharacter != null) {
                        ModalBottomSheet(
                            onDismissRequest = { selectedCharacter = null },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                        ) {
                            CharacterProfileSheetContent(
                                character = selectedCharacter!!,
                                allCharacters = allCharacters,
                                isEink = isEink,
                                textSizeSp = textSizeSp,
                                displayMode = displayMode,
                                isChineseBook = isChineseBook,
                                onSelectCharacter = { newChar -> selectedCharacter = newChar },
                                onClose = { selectedCharacter = null }
                            )
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.ai_empty_hint),
                            fontSize = 14.sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                doGeneratePlot()
                            },
                            colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                            else ButtonDefaults.buttonColors()
                        ) {
                            Text(stringResource(R.string.ai_generate_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, isEink: Boolean) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * Character card item on the plot map.
 * Uses FlowRow for natural line-wrapping without horizontal truncation or squeezing.
 * Entire card is clickable to open the character profile & lineage bottom sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterCardItem(
    card: CharacterCard,
    isEink: Boolean,
    textSizeSp: Float,
    displayMode: String,
    isChineseBook: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shadowElevation = if (isEink) 0.dp else 1.5.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(10.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top Row: Character Name + Faction/Role
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isChinese = isChineseBook || when {
                    card.nameOriginal.isNotBlank() -> card.nameOriginal.any { it in '\u4e00'..'\u9fff' }
                    card.nameTranslation.isNotBlank() -> card.nameTranslation.any { it in '\u4e00'..'\u9fff' }
                    else -> false
                }
                val isSame = card.nameOriginal.trim().equals(card.nameTranslation.trim(), ignoreCase = true)

                val primaryName = when (displayMode) {
                    "orig" -> {
                        if (isChinese && !card.nameOriginal.any { it in '\u4e00'..'\u9fff' } && card.nameTranslation.any { it in '\u4e00'..'\u9fff' }) {
                            card.nameTranslation
                        } else {
                            if (card.nameOriginal.isNotBlank()) card.nameOriginal else card.nameTranslation
                        }
                    }
                    "target" -> if (card.nameTranslation.isNotBlank()) card.nameTranslation else card.nameOriginal
                    else -> {
                        // In bilingual mode, if it's Chinese text, prefer Chinese name
                        if (isChinese) {
                            if (card.nameOriginal.any { it in '\u4e00'..'\u9fff' }) card.nameOriginal
                            else if (card.nameTranslation.any { it in '\u4e00'..'\u9fff' }) card.nameTranslation
                            else card.nameOriginal.ifBlank { card.nameTranslation }
                        } else {
                            card.nameOriginal.ifBlank { card.nameTranslation }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = primaryName,
                        fontSize = textSizeSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                    )
                    if (displayMode == "bilingual" && card.nameTranslation.isNotBlank() && !isSame) {
                        val secondaryName = if (primaryName == card.nameOriginal) card.nameTranslation else card.nameOriginal
                        if (secondaryName.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "($secondaryName)",
                                fontSize = (textSizeSp * 0.9f).sp,
                                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Faction / Role Tag
                if (card.faction.isNotBlank() || card.role.isNotBlank()) {
                    val tagText = listOf(card.faction, card.role).filter { it.isNotBlank() }.joinToString(" • ")
                    Text(
                        text = tagText,
                        fontSize = 11.sp,
                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Quick Relationships Overview: Uses FlowRow to wrap gracefully (Fixes the squeezed vertical bar bug!)
            val relations = card.getResolvedRelations()
            if (relations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    relations.take(4).forEach { rel ->
                        val icon = when (rel.category) {
                            "spouse" -> "💍"
                            "child" -> "👶"
                            "parent" -> "👴"
                            "sibling" -> "🤝"
                            "ally" -> "🛡️"
                            "rival" -> "⚔️"
                            else -> "🔗"
                        }
                        val activeLabel = rel.getDisplayLabel(displayMode)
                        val displayText = if (activeLabel.isNotBlank()) "$icon $activeLabel: ${rel.target}" else "$icon ${rel.target}"
                        Text(
                            text = displayText,
                            fontSize = 11.sp,
                            color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .background(
                                    if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    RoundedCornerShape(4.dp)
                                )
                                .then(if (isEink) Modifier.border(1.dp, Color.LightGray, RoundedCornerShape(4.dp)) else Modifier)
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Bottom prompt: Tap to view profile & lineage
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_character_tap_hint),
                    fontSize = 11.sp,
                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }
        }
    }
}

/**
 * Character Profile & Lineage Bottom Sheet Content (方式B)
 * Displays:
 * 1. Double-language Character Name & Faction Header
 * 2. Biography & Personality Background (bioOriginal + bioTranslation)
 * 3. Categorized Lineage Tree (Spouse, Children, Parents, Siblings, Allies, Rivals)
 * 4. Interactive Node Tap-Through: Tap any related character to immediately jump to their profile!
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterProfileSheetContent(
    character: CharacterCard,
    allCharacters: List<CharacterCard>,
    isEink: Boolean,
    textSizeSp: Float,
    displayMode: String,
    isChineseBook: Boolean = false,
    onSelectCharacter: (CharacterCard) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val isChinese = isChineseBook || when {
        character.nameOriginal.isNotBlank() -> character.nameOriginal.any { it in '\u4e00'..'\u9fff' }
        character.nameTranslation.isNotBlank() -> character.nameTranslation.any { it in '\u4e00'..'\u9fff' }
        else -> false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(horizontal = 18.dp)
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp)
    ) {
        // ── 1. Header: Name + Faction + Close ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val isSame = character.nameOriginal.trim().equals(character.nameTranslation.trim(), ignoreCase = true)

                val primaryName = when (displayMode) {
                    "orig" -> {
                        if (isChinese && !character.nameOriginal.any { it in '\u4e00'..'\u9fff' } && character.nameTranslation.any { it in '\u4e00'..'\u9fff' }) {
                            character.nameTranslation
                        } else {
                            if (character.nameOriginal.isNotBlank()) character.nameOriginal else character.nameTranslation
                        }
                    }
                    "target" -> if (character.nameTranslation.isNotBlank()) character.nameTranslation else character.nameOriginal
                    else -> {
                        if (isChinese) {
                            if (character.nameOriginal.any { it in '\u4e00'..'\u9fff' }) character.nameOriginal
                            else if (character.nameTranslation.any { it in '\u4e00'..'\u9fff' }) character.nameTranslation
                            else character.nameOriginal.ifBlank { character.nameTranslation }
                        } else {
                            character.nameOriginal.ifBlank { character.nameTranslation }
                        }
                    }
                }

                Text(
                    text = primaryName,
                    fontSize = (textSizeSp * 1.25f).sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                )
                if (displayMode == "bilingual" && character.nameTranslation.isNotBlank() && !isSame) {
                    val secondaryName = if (primaryName == character.nameOriginal) character.nameTranslation else character.nameOriginal
                    if (secondaryName.isNotBlank()) {
                        Text(
                            text = secondaryName,
                            fontSize = (textSizeSp * 0.95f).sp,
                            color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Faction & Role Badges
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (character.faction.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier
                        ) {
                            Text(
                                text = character.faction,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (character.role.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isEink) Color.White else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            modifier = if (isEink) Modifier.border(1.dp, Color.Gray, RoundedCornerShape(4.dp)) else Modifier
                        ) {
                            Text(
                                text = character.role,
                                fontSize = 11.sp,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            thickness = 0.5.dp,
            color = if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant
        )

        // ── 2. Biography & Background ──
        val isBioOrigEnglishOnly = isChinese && !character.bioOriginal.any { it in '\u4e00'..'\u9fff' } && character.bioTranslation.any { it in '\u4e00'..'\u9fff' }
        val effectiveBioOrig = if (isBioOrigEnglishOnly) character.bioTranslation else character.bioOriginal
        val showBioOrig = (displayMode == "bilingual" || displayMode == "orig" || character.bioTranslation.isBlank()) && effectiveBioOrig.isNotBlank()
        val showBioTrans = (displayMode == "bilingual" || displayMode == "target") && character.bioTranslation.isNotBlank() && (!isBioOrigEnglishOnly || displayMode == "target")

        if (showBioOrig || showBioTrans) {
            Text(
                text = stringResource(R.string.ai_character_bio_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (showBioOrig) {
                        Text(
                            text = effectiveBioOrig,
                            fontSize = (textSizeSp * 0.95f).sp,
                            lineHeight = (textSizeSp * 1.35f).sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (showBioTrans && character.bioTranslation != effectiveBioOrig) {
                        if (showBioOrig) Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = character.bioTranslation,
                            fontSize = (textSizeSp * 0.9f).sp,
                            lineHeight = (textSizeSp * 1.3f).sp,
                            color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // ── 3. Lineage & Relationship Tree ──
        val relations = character.getResolvedRelations()
        if (relations.isNotEmpty()) {
            Text(
                text = stringResource(R.string.ai_character_lineage_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // Group relations into hierarchy
            val groupedRelations = relations.groupBy { it.category }
            val orderedCategories = listOf(
                "spouse" to R.string.ai_relation_cat_spouse,
                "child" to R.string.ai_relation_cat_child,
                "parent" to R.string.ai_relation_cat_parent,
                "sibling" to R.string.ai_relation_cat_sibling,
                "ally" to R.string.ai_relation_cat_ally,
                "rival" to R.string.ai_relation_cat_rival,
                "other" to R.string.ai_relation_cat_other
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                orderedCategories.forEach { (catKey, labelResId) ->
                    val list = groupedRelations[catKey]
                    if (!list.isNullOrEmpty()) {
                        Column {
                            Text(
                                text = stringResource(labelResId),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                list.forEach { rel ->
                                    val activeLabel = rel.getDisplayLabel(displayMode)
                                    val chipText = if (activeLabel.isNotBlank()) "$activeLabel: ${rel.target}" else rel.target

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                // Node Tap-Through: Find matching character in book list
                                                val targetClean = rel.target.lowercase().trim()
                                                val matched = allCharacters.firstOrNull { other ->
                                                    other !== character && (
                                                            other.nameOriginal.lowercase().contains(targetClean) ||
                                                                    targetClean.contains(other.nameOriginal.lowercase()) ||
                                                                    (other.nameTranslation.isNotBlank() && (
                                                                            other.nameTranslation.contains(targetClean) ||
                                                                                    targetClean.contains(other.nameTranslation)
                                                                            ))
                                                            )
                                                }
                                                if (matched != null) {
                                                    onSelectCharacter(matched)
                                                } else {
                                                    // Seamless fallback card for unindexed relatives (Never hit a dead-end!)
                                                    val fallbackRole = activeLabel.ifBlank { rel.label.ifBlank { character.faction } }
                                                    val syntheticCard = CharacterCard(
                                                        nameOriginal = rel.target,
                                                        nameTranslation = "",
                                                        faction = character.faction,
                                                        role = fallbackRole,
                                                        bioOriginal = "Appears in the narrative in relation to ${character.nameOriginal} ($fallbackRole).",
                                                        bioTranslation = context.getString(
                                                            R.string.ai_character_synthetic_bio,
                                                            fallbackRole,
                                                            character.nameTranslation.ifBlank { character.nameOriginal }
                                                        ),
                                                        relationships = listOf("${character.nameOriginal}: $fallbackRole")
                                                    )
                                                    onSelectCharacter(syntheticCard)
                                                }
                                            }
                                            .then(
                                                if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp))
                                                else Modifier.border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                            )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = chipText,
                                                fontSize = 12.sp,
                                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineStageItem(
    stage: PlotStage,
    isEink: Boolean,
    textSizeSp: Float,
    displayMode: String,
    isChineseBook: Boolean = false
) {
    val isEventOrigEnglishOnly = isChineseBook && !stage.eventOriginal.any { it in '\u4e00'..'\u9fff' } && stage.eventTranslation.any { it in '\u4e00'..'\u9fff' }
    val effectiveEventOrig = if (isEventOrigEnglishOnly) stage.eventTranslation else stage.eventOriginal
    val showEventOrig = (displayMode == "bilingual" || displayMode == "orig" || stage.eventTranslation.isBlank()) && effectiveEventOrig.isNotBlank()
    val showEventTrans = (displayMode == "bilingual" || displayMode == "target") && stage.eventTranslation.isNotBlank() && (!isEventOrigEnglishOnly || displayMode == "target")

    Row(modifier = Modifier.fillMaxWidth()) {
        // Vertical Timeline bullet
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 10.dp, top = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (isEink) Color.Black else MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(45.dp)
                    .background(if (isEink) Color.LightGray else MaterialTheme.colorScheme.outlineVariant)
            )
        }

        // Stage content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stage.stage,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
            )
            if (showEventOrig) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = effectiveEventOrig,
                    fontSize = (textSizeSp * 0.95f).sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                )
            }
            if (showEventTrans && stage.eventTranslation != effectiveEventOrig) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stage.eventTranslation,
                    fontSize = (textSizeSp * 0.88f).sp,
                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
