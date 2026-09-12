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

    var plotResult by remember {
        mutableStateOf(repository.getPlot(bookId, chapterIndex, selectedScope))
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Faction filter and BottomSheet character selection
    var selectedFaction by remember { mutableStateOf<String?>(null) }
    var selectedCharacter by remember { mutableStateOf<CharacterCard?>(null) }

    LaunchedEffect(bookId, chapterIndex, selectedScope) {
        plotResult = repository.getPlot(bookId, chapterIndex, selectedScope)
        selectedFaction = null
        selectedCharacter = null
    }

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
                    .padding(8.dp),
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

        // Cache Status
        if (plotResult != null) {
            Text(
                text = stringResource(R.string.ai_cached_badge),
                fontSize = 11.sp,
                color = if (isEink) Color.Black else Color(0xFF10B981),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
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
                    val filteredCharacters = remember(allCharacters, selectedFaction) {
                        if (selectedFaction.isNullOrBlank()) allCharacters
                        else allCharacters.filter { it.faction == selectedFaction }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        // 1. Narrative Core & Stakes
                        val showCoreOrig = (displayMode == "bilingual" || displayMode == "orig" || result.coreDynamicsTranslation.isBlank()) && result.coreDynamicsOriginal.isNotBlank()
                        val showCoreTrans = (displayMode == "bilingual" || displayMode == "target") && result.coreDynamicsTranslation.isNotBlank()

                        if (showCoreOrig || showCoreTrans) {
                            item {
                                SectionHeader(title = stringResource(R.string.ai_plot_core_title), isEink = isEink)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp)
                                        .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        if (showCoreOrig) {
                                            Text(
                                                text = result.coreDynamicsOriginal,
                                                fontSize = textSizeSp.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (showCoreTrans) {
                                            if (showCoreOrig) Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = result.coreDynamicsTranslation,
                                                fontSize = (textSizeSp * 0.92f).sp,
                                                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Character Relationship Cards & Lineage
                        if (allCharacters.isNotEmpty()) {
                            item {
                                SectionHeader(title = stringResource(R.string.ai_plot_relations_title), isEink = isEink)

                                // Faction Filter Chips (if more than 1 faction)
                                if (factions.size > 1) {
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
                                    onClick = { selectedCharacter = card }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            item { Spacer(modifier = Modifier.height(12.dp)) }
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
                                    displayMode = displayMode
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
                val primaryName = when (displayMode) {
                    "orig" -> if (card.nameOriginal.isNotBlank()) card.nameOriginal else card.nameTranslation
                    "target" -> if (card.nameTranslation.isNotBlank()) card.nameTranslation else card.nameOriginal
                    else -> card.nameOriginal.ifBlank { card.nameTranslation }
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
                    if (displayMode == "bilingual" && card.nameTranslation.isNotBlank() && card.nameTranslation != card.nameOriginal) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(${card.nameTranslation})",
                            fontSize = (textSizeSp * 0.9f).sp,
                            color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        val displayText = if (rel.label.isNotBlank()) "$icon ${rel.label}: ${rel.target}" else "$icon ${rel.target}"
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
    onSelectCharacter: (CharacterCard) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

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
                val primaryName = when (displayMode) {
                    "orig" -> if (character.nameOriginal.isNotBlank()) character.nameOriginal else character.nameTranslation
                    "target" -> if (character.nameTranslation.isNotBlank()) character.nameTranslation else character.nameOriginal
                    else -> character.nameOriginal.ifBlank { character.nameTranslation }
                }

                Text(
                    text = primaryName,
                    fontSize = (textSizeSp * 1.25f).sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                )
                if (displayMode == "bilingual" && character.nameTranslation.isNotBlank() && character.nameTranslation != character.nameOriginal) {
                    Text(
                        text = character.nameTranslation,
                        fontSize = (textSizeSp * 0.95f).sp,
                        color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
        val showBioOrig = (displayMode == "bilingual" || displayMode == "orig" || character.bioTranslation.isBlank()) && character.bioOriginal.isNotBlank()
        val showBioTrans = (displayMode == "bilingual" || displayMode == "target") && character.bioTranslation.isNotBlank()

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
                            text = character.bioOriginal,
                            fontSize = (textSizeSp * 0.95f).sp,
                            lineHeight = (textSizeSp * 1.35f).sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (showBioTrans) {
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
                                    val chipText = if (rel.label.isNotBlank()) "${rel.label}: ${rel.target}" else rel.target

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
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.ai_character_not_in_list),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
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
    displayMode: String
) {
    val showEventOrig = (displayMode == "bilingual" || displayMode == "orig" || stage.eventTranslation.isBlank()) && stage.eventOriginal.isNotBlank()
    val showEventTrans = (displayMode == "bilingual" || displayMode == "target") && stage.eventTranslation.isNotBlank()

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
                    text = stage.eventOriginal,
                    fontSize = (textSizeSp * 0.95f).sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                )
            }
            if (showEventTrans) {
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
