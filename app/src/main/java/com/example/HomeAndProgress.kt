package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.*
import com.example.ui.*
import java.text.SimpleDateFormat
import java.util.*

internal data class HomeOverview(
    val course: String, val today: Int, val target: Int, val total: Int, val learned: Int,
    val due: Int, val newTarget: Int, val week: List<Int>, val weekdays: List<String>
)

internal fun dayKey(time: Long): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(time))

internal fun weeklyCounts(sessions: List<TypingSession>): List<Int> = (6 downTo 0).map { days ->
    val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
    sessions.filter { dayKey(it.timestamp) == dayKey(day.timeInMillis) }.sumOf { it.wordsTyped }
}

@Composable
internal fun HomeScreen(viewModel: QwertyViewModel) {
    val category by viewModel.selectedCategory.collectAsState()
    val words by viewModel.allWordsWithProgress.collectAsState()
    val sessions by viewModel.allSessions.collectAsState()
    val newTarget by viewModel.dailyNewWordsTarget.collectAsState()
    val reviewTarget by viewModel.dailyReviewTarget.collectAsState()
    val book = words.filter { it.word.belongsTo(category) }
    val history = sessions.filter { it.category == category }
    val now = System.currentTimeMillis()
    HomeContent(
        HomeOverview(category, history.filter { dayKey(it.timestamp) == dayKey(now) }.sumOf { it.wordsTyped },
            newTarget + reviewTarget, book.size, book.count { it.progress != null },
            book.count { (it.progress?.nextReviewTime ?: Long.MAX_VALUE) <= now }, newTarget,
            weeklyCounts(history), (6 downTo 0).map { days ->
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
                listOf("日", "一", "二", "三", "四", "五", "六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
            }),
        start = { viewModel.startPracticeSession(GameMode.TYPING, true) },
        courses = { viewModel.setScreen(ScreenState.WORD_BOOK_EXPLORER) },
        progress = { viewModel.setScreen(ScreenState.STATS_HISTORY) },
        exams = { viewModel.setScreen(ScreenState.EXAM_PRACTICE) },
        options = { viewModel.setScreen(ScreenState.PRACTICE_PREVIEW) },
        goals = { viewModel.setScreen(ScreenState.SETTINGS) }
    )
}

@Composable
internal fun HomeContent(data: HomeOverview, start: () -> Unit, courses: () -> Unit,
                         progress: () -> Unit, exams: () -> Unit, options: () -> Unit, goals: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("dashboard_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("今日目标", "调整目标", goals) }
        item {
            StudyCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(data.today.toString(), style = MaterialTheme.typography.displayMedium)
                        Text(" / ${data.target} 张", Modifier.padding(start = 6.dp, bottom = 7.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (data.today >= data.target) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
                        null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
                LinearProgressIndicator(progress = { (data.today.toFloat() / data.target.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {})
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    data.week.forEachIndexed { index, count ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(28.dp).background(
                                if (count > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.background, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (count > 0) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.tertiary)
                                else if (index == 6) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            }
                            Text(data.weekdays[index], style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Button(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("start_personalized_learning"),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background)) {
                Text("开始学习", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.ArrowForward, null)
            }
        }
        item { SectionTitle("正在学习", "切换课程", courses) }
        item {
            StudyCard {
                Text(courseName(data.course), style = MaterialTheme.typography.titleLarge)
                Text(data.course, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { data.learned.toFloat() / data.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.background, drawStopIndicator = {})
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已学习 ${data.learned} / ${data.total}", style = MaterialTheme.typography.bodySmall)
                    Text("${if (data.total > 0) data.learned * 100 / data.total else 0}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row {
                    Metric(data.newTarget.toString(), "计划新词", Modifier.weight(1f))
                    Metric(data.due.toString(), "到期复习", Modifier.weight(1f))
                }
            }
        }
        item { SectionTitle("继续练习", "学习进度", progress) }
        item { ActionRow("考试训练", "四六级真题 · 雅思阅读与听力", Icons.Outlined.Assignment, exams) }
        item { ActionRow("更多练习", "听写、词义选择与配对", Icons.Outlined.Tune, options) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
internal fun ProgressScreen(viewModel: QwertyViewModel) {
    val category by viewModel.selectedCategory.collectAsState()
    val allWords by viewModel.allWordsWithProgress.collectAsState()
    val allSessions by viewModel.allSessions.collectAsState()
    val words = allWords.filter { it.word.belongsTo(category) }
    val sessions = allSessions.filter { it.category == category }
    val week = weeklyCounts(sessions)
    var reset by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("stats_history_screen"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(courseName(category), style = MaterialTheme.typography.headlineMedium)
            Text(category, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            StudyCard {
                Row {
                    Metric(words.count { it.progress != null }.toString(), "已学习单词", Modifier.weight(1f))
                    Metric(words.count { it.progress?.isMastered == true }.toString(), "已掌握单词", Modifier.weight(1f))
                }
            }
        }
        item {
            StudyCard {
                Text("最近七天", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom) {
                    week.forEachIndexed { index, count ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.width(16.dp).height((8 + 80 * count / (week.maxOrNull() ?: 1).coerceAtLeast(1)).dp)
                                .clip(MaterialTheme.shapes.small).background(
                                    if (index == 6) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer))
                            Spacer(Modifier.height(8.dp))
                            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, index - 6) }
                            Text(SimpleDateFormat("M/d", Locale.ROOT).format(cal.time), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("词汇掌握情况", style = MaterialTheme.typography.titleMedium)
                val levels = (0..5).map { level -> words.count { (it.progress?.memoryLevel ?: 0) == level } }
                val colors = listOf(MaterialTheme.colorScheme.surfaceVariant, Color(0xFFF0B66C),
                    Color(0xFFD5ADDA), Color(0xFF8CB9C0), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
                    if (words.isEmpty()) Box(Modifier.fillMaxSize().background(colors[0]))
                    levels.forEachIndexed { level, count ->
                        if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(colors[level]))
                    }
                }
                Text("未学习 ${levels[0]}   巩固中 ${levels.subList(1, 5).sum()}   熟练 ${levels[5]}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { ActionRow("需要巩固的单词", "查看答错过的词汇", Icons.Outlined.BookmarkBorder,
            { viewModel.setScreen(ScreenState.MISTAKES_BOOK) }) }
        item { SectionTitle("最近练习") }
        if (sessions.isEmpty()) item { StudyCard { Text("完成第一次练习后，记录会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(sessions.take(15), key = { it.id }) { session ->
            StudyCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${session.wordsTyped} 个单词", style = MaterialTheme.typography.titleMedium)
                        Text(SimpleDateFormat("M月d日  HH:mm", Locale.CHINA).format(Date(session.timestamp)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${session.accuracy.toInt()}%", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { TextButton(onClick = { reset = true }, modifier = Modifier.testTag("clear_history")) { Text("清除学习记录") } }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("清除学习记录？") },
        text = { Text("所有词书的学习进度和练习记录将被清除。") },
        confirmButton = { TextButton(onClick = { viewModel.clearAllHistory(); reset = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
