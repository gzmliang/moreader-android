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
    lineHeightMode: String = "standard", // "compact", "standard", "relaxed"
    pageAnimationMode: String,
    isLargeScreen: Boolean,
    landscapeLimitWidth: Boolean = false,
    isEinkMode: Boolean,
    currentTheme: com.moyue.app.data.models.ReaderTheme = com.moyue.app.data.models.ReaderTheme.LIGHT,
    onFontSizeChange: (Int) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    onFontWeightChange: (String) -> Unit,
    onLineHeightChange: (String) -> Unit,
    onLayoutSpreadModeChange: (String) -> Unit,
    onPageAnimationModeChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDark = currentTheme.isDark || isSystemDark

    val sheetBgColor = when {
        isEinkMode -> Color.White
        currentTheme.isDark -> Color(android.graphics.Color.parseColor(currentTheme.bgColor))
        isSystemDark -> Color(0xFF1E1E1E)
        else -> MaterialTheme.colorScheme.surface
    }

    val sheetTextColor = when {
        isEinkMode -> Color.Black
        currentTheme.isDark -> Color(android.graphics.Color.parseColor(currentTheme.textColor))
        isSystemDark -> Color(0xFFE0E0E0)
        else -> MaterialTheme.colorScheme.onSurface
    }

    val sheetSecondaryTextColor = when {
        isEinkMode -> Color.DarkGray
        isDark -> sheetTextColor.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val itemCardBg = when {
        isEinkMode -> Color.White
        isDark -> Color.White.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = if (landscapeLimitWidth) Modifier.widthIn(max = 560.dp) else Modifier,
        containerColor = sheetBgColor,
        contentColor = sheetTextColor,
        dragHandle = { BottomSheetDefaults.DragHandle(color = sheetSecondaryTextColor.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp)
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEinkMode) Color.Black else if (isDark) Color(0xFF90CAF9) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.reader_typography_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = sheetTextColor
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close, 
                        contentDescription = null, 
                        modifier = Modifier.size(16.dp),
                        tint = sheetSecondaryTextColor
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── 1. Font Size (Slider with A- / A+) ──
            Text(
                text = stringResource(R.string.reader_font_size_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = sheetSecondaryTextColor
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = itemCardBg,
                border = if (isEinkMode) BorderStroke(1.dp, Color.Black) else BorderStroke(0.6.dp, sheetSecondaryTextColor.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { if (fontSize > 14) onFontSizeChange(fontSize - 1) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Text("A-", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(8.dp))

                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { onFontSizeChange(it.toInt()) },
                        valueRange = 14f..32f,
                        steps = 17,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(8.dp))

                    FilledTonalIconButton(
                        onClick = { if (fontSize < 32) onFontSizeChange(fontSize + 1) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Text("A+", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "$fontSize",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEinkMode) Color.Black else if (isDark) Color(0xFF90CAF9) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(22.dp),
                        textAlign = TextAlign.End
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 2. Font Family Selection ──
            Text(
                text = stringResource(R.string.reader_font_family_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = sheetSecondaryTextColor
            )
            Spacer(Modifier.height(6.dp))

            val fontList = listOf(
                Triple("serif", R.string.font_name_serif, R.string.font_desc_serif),
                Triple("sans-serif", R.string.font_name_sans, R.string.font_desc_sans),
                Triple("cursive", R.string.font_name_kaiti, R.string.font_desc_kaiti),
                Triple("monospace", R.string.font_name_mono, R.string.font_desc_mono)
            )

            // Render 2x2 Grid of Font Cards
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until fontList.size step 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (j in i until minOf(i + 2, fontList.size)) {
                            val (valKey, nameResId, descResId) = fontList[j]
                            val isSelected = (fontFamily == valKey)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    if (isEinkMode) Color.White else if (isDark) Color(0xFF90CAF9).copy(alpha = 0.22f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    itemCardBg
                                },
                                border = if (isSelected) {
                                    BorderStroke(1.2.dp, if (isEinkMode) Color.Black else if (isDark) Color(0xFF90CAF9) else MaterialTheme.colorScheme.primary)
                                } else {
                                    BorderStroke(0.6.dp, if (isEinkMode) Color.LightGray else sheetSecondaryTextColor.copy(alpha = 0.2f))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onFontFamilyChange(valKey) }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(nameResId),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isEinkMode) Color.Black else if (isSelected) (if (isDark) Color(0xFF90CAF9) else MaterialTheme.colorScheme.primary) else sheetTextColor
                                        )
                                        if (isSelected) {
                                            Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isEinkMode) Color.Black else if (isDark) Color(0xFF90CAF9) else MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(descResId),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        color = sheetSecondaryTextColor.copy(alpha = 0.75f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 3. Font Weight ──
            Text(
                text = stringResource(R.string.reader_font_weight_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = sheetSecondaryTextColor
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                fontSize = 11.sp,
                                fontWeight = if (wKey == "700") FontWeight.Bold else if (wKey == "600") FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 4. Line Spacing (Compact / Normal / Relaxed) ──
            Text(
                text = stringResource(R.string.reader_line_height_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = sheetSecondaryTextColor
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val lineModes = listOf(
                    "compact" to R.string.reader_line_height_compact,
                    "standard" to R.string.reader_line_height_standard,
                    "relaxed" to R.string.reader_line_height_relaxed
                )
                lineModes.forEach { (lKey, lLabelId) ->
                    val isSel = (lineHeightMode == lKey)
                    FilterChip(
                        selected = isSel,
                        onClick = { onLineHeightChange(lKey) },
                        label = {
                            Text(
                                stringResource(lLabelId),
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 5. Layout Mode (Tablet / Foldable Dual Spread) ──
            Text(
                text = stringResource(R.string.reader_layout_mode_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = sheetSecondaryTextColor
            )
            Text(
                text = stringResource(R.string.reader_layout_auto_desc),
                fontSize = 10.5.sp,
                color = sheetSecondaryTextColor.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 1.dp, bottom = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 6. Page Animation Mode (Smooth vs Instant) ──
            Text(
                text = stringResource(R.string.reader_page_anim_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = sheetSecondaryTextColor
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val animModes = listOf(
                    "slide" to R.string.reader_page_anim_slide,
                    "instant" to R.string.reader_page_anim_instant
                )
                animModes.forEach { (aKey, aLabelId) ->
                    val isSel = (pageAnimationMode == aKey)
                    FilterChip(
                        selected = isSel,
                        onClick = { onPageAnimationModeChange(aKey) },
                        label = {
                            Text(
                                stringResource(aLabelId),
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
