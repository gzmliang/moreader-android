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
import com.moyue.ai.model.QuizQuestion
import com.moyue.ai.model.QuizReportRecord
import com.moyue.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReportsTabContent(
    bookId: String,
    repository: AiCacheRepository,
    isEink: Boolean,
    textSizeSp: Float
) {
    var reports by remember { mutableStateOf(repository.getQuizReports(bookId)) }
    var selectedReport by remember { mutableStateOf<QuizReportRecord?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedReport != null) {
            // Detailed Review View
            val report = selectedReport!!
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ai_reports_back_btn),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { selectedReport = null }
                    )

                    val percent = if (report.totalCount > 0) (report.score * 100) / report.totalCount else 0
                    Text(
                        text = stringResource(R.string.ai_quiz_score_summary, report.score, report.totalCount, percent),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                items(report.questions) { q ->
                    ReviewQuestionItem(
                        question = q,
                        userAnswer = report.userAnswers[q.id],
                        isEink = isEink,
                        textSizeSp = textSizeSp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        } else {
            // Reports List View
            if (reports.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.ai_reports_empty),
                        fontSize = 14.sp,
                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    items(reports) { r ->
                        ReportCardItem(
                            report = r,
                            dateFormat = dateFormat,
                            isEink = isEink,
                            textSizeSp = textSizeSp,
                            onReview = { selectedReport = r }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportCardItem(
    report: QuizReportRecord,
    dateFormat: SimpleDateFormat,
    isEink: Boolean,
    textSizeSp: Float,
    onReview: () -> Unit
) {
    val percent = if (report.totalCount > 0) (report.score * 100) / report.totalCount else 0
    val formattedTime = dateFormat.format(Date(report.timestamp))

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shadowElevation = if (isEink) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
            .clickable { onReview() }
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = report.chapterTitle,
                    fontSize = (textSizeSp).sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formattedTime,
                        fontSize = 11.sp,
                        color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• ${report.difficulty}",
                        fontSize = 11.sp,
                        color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${report.score}/${report.totalCount} ($percent%)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEink) Color.Black else if (percent >= 80) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ai_reports_review_btn) + " ›",
                    fontSize = 11.sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ReviewQuestionItem(
    question: QuizQuestion,
    userAnswer: String?,
    isEink: Boolean,
    textSizeSp: Float
) {
    val isCorrect = userAnswer != null && (userAnswer.equals(question.correctAnswer, ignoreCase = true) || question.correctAnswer.startsWith(userAnswer, ignoreCase = true))

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shadowElevation = if (isEink) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Title
            Text(
                text = "${question.id}. ${question.questionOriginal}",
                fontSize = (textSizeSp).sp,
                fontWeight = FontWeight.Bold,
                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
            )
            if (question.questionTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = question.questionTranslation,
                    fontSize = (textSizeSp * 0.9f).sp,
                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Options list
            question.options.forEach { opt ->
                val optPrefix = opt.take(1).uppercase()
                val isUserPick = (userAnswer == optPrefix)
                val isStandardCorrect = question.correctAnswer.startsWith(optPrefix, ignoreCase = true)

                val optBg = when {
                    isEink -> Color.White
                    isStandardCorrect -> Color(0xFF10B981).copy(alpha = 0.1f)
                    isUserPick && !isCorrect -> Color(0xFFEF4444).copy(alpha = 0.1f)
                    else -> Color.Transparent
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = optBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .then(
                            if (isStandardCorrect || isUserPick) Modifier.border(
                                1.dp,
                                if (isEink) Color.Black else (if (isStandardCorrect) Color(0xFF10B981) else Color(0xFFEF4444)),
                                RoundedCornerShape(4.dp)
                            ) else Modifier
                        )
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = when {
                            isStandardCorrect -> "✓"
                            isUserPick -> "✗"
                            else -> " "
                        }
                        Text(
                            text = icon,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEink) Color.Black else if (isStandardCorrect) Color(0xFF10B981) else Color(0xFFEF4444),
                            modifier = Modifier.width(18.dp)
                        )
                        Text(
                            text = opt,
                            fontSize = (textSizeSp * 0.9f).sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Explanation
            if (question.analysisOriginal.isNotBlank() || question.analysisTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = stringResource(R.string.ai_quiz_analysis_title),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                        )
                        if (question.analysisOriginal.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = question.analysisOriginal,
                                fontSize = (textSizeSp * 0.88f).sp,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (question.analysisTranslation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = question.analysisTranslation,
                                fontSize = (textSizeSp * 0.82f).sp,
                                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
