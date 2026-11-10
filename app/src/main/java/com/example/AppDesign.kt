package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.QwertyViewModel
import com.example.ui.ScreenState
import com.example.ui.GameMode

internal fun courseName(code: String) = when (code) {
    "CET-4" -> "四级英语"
    "CET-6" -> "六级英语"
    "IELTS" -> "雅思英语"
    "TOEFL" -> "托福英语"
    else -> "编程英语"
}

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun StudyCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ActionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit,
                       modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LexiHeader(viewModel: QwertyViewModel) {
    val screen by viewModel.screenState.collectAsState()
    val category by viewModel.selectedCategory.collectAsState()
    val edition by viewModel.selectedBookEdition.collectAsState()
    var chooseCourse by remember { mutableStateOf(false) }
    if (screen == ScreenState.ONBOARDING) return
    val studying = screen in listOf(ScreenState.TYPING_ARENA, ScreenState.EXAM_PRACTICE, ScreenState.PRACTICE_PREVIEW)
    val title = when (screen) {
        ScreenState.DASHBOARD -> "LexiFlow"
        ScreenState.WORD_BOOK_EXPLORER -> "课程与词汇"
        ScreenState.MISTAKES_BOOK -> "需要巩固"
        ScreenState.STATS_HISTORY -> "学习进度"
        ScreenState.SETTINGS -> "我的"
        ScreenState.EXAM_PRACTICE -> "考试训练"
        ScreenState.PRACTICE_PREVIEW -> "练习设置"
        else -> courseName(category)
    }
    TopAppBar(title = {
        Text(title, style = if (screen == ScreenState.DASHBOARD) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.titleMedium)
    }, navigationIcon = {
        if (screen != ScreenState.DASHBOARD && screen !in listOf(ScreenState.WORD_BOOK_EXPLORER, ScreenState.STATS_HISTORY, ScreenState.SETTINGS)) {
            IconButton(onClick = { viewModel.speakWord(""); viewModel.setScreen(ScreenState.DASHBOARD) }) {
                Icon(Icons.Outlined.Close, "返回学习首页")
            }
        }
    }, actions = {
        if (!studying) {
            Box {
                TextButton(onClick = { chooseCourse = true }) {
                    Text("$category · ${editionName(edition)}")
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = chooseCourse, onDismissRequest = { chooseCourse = false }) {
                    viewModel.categories.forEach { code ->
                        DropdownMenuItem(text = { Text(courseName(code)) }, onClick = {
                            viewModel.selectCategory(code)
                            chooseCourse = false
                        }, trailingIcon = {
                            if (code == category) Icon(Icons.Outlined.Check, "正在学习")
                        })
                    }
                }
            }
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.testTag("app_header"))
}

@Composable
internal fun LexiNavigation(viewModel: QwertyViewModel) {
    val screen by viewModel.screenState.collectAsState()
    val onboard by viewModel.onboardingCompleted.collectAsState()
    if (!onboard || screen in listOf(ScreenState.ONBOARDING, ScreenState.TYPING_ARENA, ScreenState.EXAM_PRACTICE, ScreenState.PRACTICE_PREVIEW)) return
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 76.dp).testTag("bottom_nav_bar"),
            verticalAlignment = Alignment.CenterVertically) {
            NavigationButton("学习", Icons.Outlined.Home, screen == ScreenState.DASHBOARD, "nav_dashboard", Modifier.weight(1f)) {
                viewModel.setScreen(ScreenState.DASHBOARD)
            }
            NavigationButton("课程", Icons.Outlined.LibraryBooks, screen in listOf(ScreenState.WORD_BOOK_EXPLORER, ScreenState.MISTAKES_BOOK),
                "nav_explorer", Modifier.weight(1f)) { viewModel.setScreen(ScreenState.WORD_BOOK_EXPLORER) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = { viewModel.startPracticeSession(GameMode.TYPING, true) },
                    modifier = Modifier.size(52.dp).testTag("nav_learn"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background)) {
                    Icon(Icons.Outlined.ArrowForward, "开始学习")
                }
                Text("开始", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
            }
            NavigationButton("进度", Icons.Outlined.BarChart, screen == ScreenState.STATS_HISTORY, "nav_stats", Modifier.weight(1f)) {
                viewModel.setScreen(ScreenState.STATS_HISTORY)
            }
            NavigationButton("我的", Icons.Outlined.PersonOutline, screen == ScreenState.SETTINGS, "nav_settings", Modifier.weight(1f)) {
                viewModel.setScreen(ScreenState.SETTINGS)
            }
        }
    }
}

@Composable
private fun NavigationButton(label: String, icon: ImageVector, selected: Boolean, tag: String,
                             modifier: Modifier, action: () -> Unit) {
    TextButton(onClick = action, modifier = modifier.testTag(tag), contentPadding = PaddingValues(vertical = 12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
