package com.moyue.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiPlotResult
import com.moyue.ai.model.CharacterCard
import com.moyue.ai.model.PlotStage
import com.moyue.ai.service.AiPromptBuilder
import com.moyue.ai.service.LlmClient
import com.moyue.app.R
import kotlinx.coroutines.launch

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
    textSizeSp: Float
) {
    val scope = rememberCoroutineScope()
    var selectedScope by remember { mutableStateOf("chapter") } // "chapter" or "book"

    var plotResult by remember {
        mutableStateOf(repository.getPlot(bookId, chapterIndex, selectedScope))
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId, chapterIndex, selectedScope) {
        plotResult = repository.getPlot(bookId, chapterIndex, selectedScope)
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
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val (sys, usr) = AiPromptBuilder.buildPlotPrompt(
                                    config = config,
                                    title = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle",
                                    text = chapterText,
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        // 1. Narrative Core & Stakes
                        if (result.coreDynamicsOriginal.isNotBlank() || result.coreDynamicsTranslation.isNotBlank()) {
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
                                        if (result.coreDynamicsOriginal.isNotBlank()) {
                                            Text(
                                                text = result.coreDynamicsOriginal,
                                                fontSize = textSizeSp.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (result.coreDynamicsTranslation.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
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

                        // 2. Character Relationship Cards
                        if (result.characters.isNotEmpty()) {
                            item {
                                SectionHeader(title = stringResource(R.string.ai_plot_relations_title), isEink = isEink)
                            }
                            items(result.characters) { card ->
                                CharacterCardItem(card = card, isEink = isEink, textSizeSp = textSizeSp)
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
                                TimelineStageItem(stage = stage, isEink = isEink, textSizeSp = textSizeSp)
                                Spacer(modifier = Modifier.height(10.dp))
                            }
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
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    val (sys, usr) = AiPromptBuilder.buildPlotPrompt(
                                        config = config,
                                        title = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle",
                                        text = chapterText,
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

@Composable
private fun CharacterCardItem(card: CharacterCard, isEink: Boolean, textSizeSp: Float) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shadowElevation = if (isEink) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Name (Orig + Trans)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.nameOriginal,
                        fontSize = (textSizeSp).sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                    )
                    if (card.nameTranslation.isNotBlank()) {
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
                            .background(
                                if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Relationships
            if (card.relationships.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.relationships.forEach { rel ->
                        Text(
                            text = "🔗 $rel",
                            fontSize = 11.sp,
                            color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .background(
                                    if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(4.dp)
                                )
                                .then(if (isEink) Modifier.border(1.dp, Color.Gray, RoundedCornerShape(4.dp)) else Modifier)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineStageItem(stage: PlotStage, isEink: Boolean, textSizeSp: Float) {
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
            if (stage.eventOriginal.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stage.eventOriginal,
                    fontSize = (textSizeSp * 0.95f).sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                )
            }
            if (stage.eventTranslation.isNotBlank()) {
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
