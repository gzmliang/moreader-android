package com.moyue.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(
    fontSize: Int,
    fontFamily: String,
    fontWeight: String,
    layoutSpreadMode: String,
    isLargeScreen: Boolean,
    isEinkMode: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    onFontWeightChange: (String) -> Unit,
    onLayoutSpreadModeChange: (String) -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = if (isEinkMode) Color.White else MaterialTheme.colorScheme.surface,
        contentColor = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Aa",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.reader_typography_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 1. Font Size (Slider with A- / A+) ──
            Text(
                text = stringResource(R.string.reader_font_size_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isEinkMode) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = if (isEinkMode) BorderStroke(1.dp, Color.Black) else BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { if (fontSize > 14) onFontSizeChange(fontSize - 1) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("A-", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(10.dp))

                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { onFontSizeChange(it.toInt()) },
                        valueRange = 14f..32f,
                        steps = 17,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(10.dp))

                    FilledTonalIconButton(
                        onClick = { if (fontSize < 32) onFontSizeChange(fontSize + 1) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(10.dp))

                    Text(
                        text = "$fontSize",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(26.dp),
                        textAlign = TextAlign.End
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── 2. Font Family Selection ──
            Text(
                text = stringResource(R.string.reader_font_family_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            val fontList = listOf(
                Triple("serif", R.string.font_name_serif, "优雅宋体 Classic Literary (精读推荐)"),
                Triple("sans-serif", R.string.font_name_sans, "清晰黑体 Modern Clean (现代干练)"),
                Triple("cursive", R.string.font_name_kaiti, "温润楷体 Gentle Script (诗意柔美)"),
                Triple("monospace", R.string.font_name_mono, "现代等宽 Monospace (严谨工整)")
            )

            // Render 2x2 Grid of Font Cards
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until fontList.size step 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (j in i until minOf(i + 2, fontList.size)) {
                            val (valKey, nameResId, sampleText) = fontList[j]
                            val isSelected = (fontFamily == valKey)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    if (isEinkMode) Color.White else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    if (isEinkMode) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                },
                                border = if (isSelected) {
                                    BorderStroke(1.5.dp, if (isEinkMode) Color.Black else MaterialTheme.colorScheme.primary)
                                } else {
                                    BorderStroke(0.8.dp, if (isEinkMode) Color.LightGray else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onFontFamilyChange(valKey) }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(nameResId),
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isEinkMode) Color.Black else if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = sampleText,
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        color = if (isEinkMode) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── 3. Font Weight ──
            Text(
                text = stringResource(R.string.reader_font_weight_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val weightList = listOf(
                    "400" to R.string.font_weight_normal,
                    "600" to R.string.font_weight_semibold,
                    "700" to R.string.font_weight_bold
                )
                weightList.forEach { (wKey, wLabelId) ->
                    val isSel = (fontWeight == wKey)
                    FilterChip(
                        selected = isSel,
                        onClick = { onFontWeightChange(wKey) },
                        label = {
                            Text(
                                stringResource(wLabelId),
                                fontSize = 12.sp,
                                fontWeight = if (wKey == "700") FontWeight.Bold else if (wKey == "600") FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── 4. Layout Mode (Tablet / Foldable Dual Spread) ──
            Text(
                text = stringResource(R.string.reader_layout_mode_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.reader_layout_auto_desc),
                fontSize = 11.sp,
                color = if (isEinkMode) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modes = listOf(
                    "auto" to R.string.reader_layout_auto,
                    "single" to R.string.reader_layout_single,
                    "dual" to R.string.reader_layout_dual
                )
                modes.forEach { (mKey, mLabelId) ->
                    val isSel = (layoutSpreadMode == mKey)
                    FilterChip(
                        selected = isSel,
                        onClick = { onLayoutSpreadModeChange(mKey) },
                        label = {
                            Text(
                                stringResource(mLabelId),
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}
