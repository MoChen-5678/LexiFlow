package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.data.*
import com.example.ui.*

@Composable
internal fun CourseLibraryScreen(viewModel: QwertyViewModel) {
    val category by viewModel.selectedCategory.collectAsState()
    val all by viewModel.allWordsWithProgress.collectAsState()
    val words by viewModel.filteredExplorerWords.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val filter by viewModel.selectedMemoryLevelFilter.collectAsState()
    var tab by rememberSaveable { mutableStateOf("courses") }
    Column(Modifier.fillMaxSize().testTag("word_book_explorer_screen")) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(tab == "courses", { tab = "courses" }, label = { Text("课程") })
            FilterChip(tab == "words", { tab = "words" }, label = { Text("我的词汇") })
        }
        if (tab == "courses") {
            LazyColumn(Modifier.testTag("course_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { SectionTitle("词汇课程") }
                items(viewModel.categories) { code ->
                    val book = all.filter { it.word.belongsTo(code) }
                    Surface(onClick = { viewModel.selectCategory(code); tab = "words" }, shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("course_$code")) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(courseName(code), style = MaterialTheme.typography.titleLarge)
                                    Text("$code · ${book.size} 词", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (category == code) Icon(Icons.Outlined.CheckCircle, "正在学习", tint = MaterialTheme.colorScheme.primary)
                                else Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LinearProgressIndicator(progress = { book.count { it.progress != null }.toFloat() / book.size.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.background, drawStopIndicator = {})
                        }
                    }
                }
                item { SectionTitle("考试训练") }
                item { ActionRow("四六级与雅思", "真题、阅读、听力与口语回忆", Icons.Outlined.Assignment,
                    { viewModel.setScreen(ScreenState.EXAM_PRACTICE) }) }
                item { ActionRow("其他练习方式", "听写、词义选择与配对", Icons.Outlined.Tune,
                    { viewModel.setScreen(ScreenState.PRACTICE_PREVIEW) }) }
            }
        } else {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(courseName(category), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(value = query, onValueChange = { viewModel.searchQuery.value = it },
                    placeholder = { Text("搜索单词或中文释义") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { viewModel.searchQuery.value = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } },
                    singleLine = true, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("explorer_search_bar"))
                ChoiceGroup(listOf("ALL" to "全部", "NEW" to "未学习", "DUE" to "待复习",
                    "1" to "初识", "2" to "复习中", "3" to "巩固中", "4" to "熟悉", "5" to "熟练"), filter) { viewModel.selectedMemoryLevelFilter.value = it }
            }
            if (words.isEmpty()) Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Text("没有找到符合条件的词汇", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(words, key = { it.word.id }) { WordListCard(it, viewModel::speakWord) }
            }
        }
    }
}

@Composable
internal fun WordListCard(item: WordWithProgress, speak: (String) -> Unit) {
    StudyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.word.word, style = MaterialTheme.typography.titleLarge)
                if (item.word.phonetic.isNotBlank()) Text(item.word.phonetic, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { speak(item.word.word) }) {
                Icon(Icons.Outlined.VolumeUp, "朗读 ${item.word.word}", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(item.word.translation)
        val progress = item.progress
        Text(when {
            progress == null -> "未学习"
            progress.isMastered -> "已掌握"
            progress.nextReviewTime <= System.currentTimeMillis() -> "待复习"
            else -> "巩固中"
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ReviewScreen(viewModel: QwertyViewModel) {
    val words by viewModel.mistakeWords.collectAsState()
    val category by viewModel.selectedCategory.collectAsState()
    val book = words.filter { it.word.belongsTo(category) }
    LazyColumn(Modifier.fillMaxSize().testTag("mistakes_book_screen"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("${book.size} 个词需要巩固", style = MaterialTheme.typography.headlineMedium) }
        if (book.isNotEmpty()) item {
            Button(onClick = { viewModel.startPracticeSession(GameMode.TYPING, true, mistakesOnly = true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("开始复习") }
        }
        if (book.isEmpty()) item { StudyCard { Text("答错的单词会收录到这里。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(book, key = { it.word.id }) { WordListCard(it, viewModel::speakWord) }
    }
}

@Composable
internal fun ChoiceGroup(values: List<Pair<String, String>>, selected: String, action: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { (key, label) -> FilterChip(selected == key, { action(key) }, label = { Text(label) }) }
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, action: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = action),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, null)
    }
}

@Composable
internal fun PreferencesScreen(viewModel: QwertyViewModel) {
    val category by viewModel.selectedCategory.collectAsState()
    val new by viewModel.dailyNewWordsTarget.collectAsState()
    val review by viewModel.dailyReviewTarget.collectAsState()
    val size by viewModel.wordsPerSession.collectAsState()
    val accent by viewModel.pronunciationAccent.collectAsState()
    val gender by viewModel.pronunciationGender.collectAsState()
    val speed by viewModel.pronunciationSpeed.collectAsState()
    val autoplay by viewModel.autoPlaySpeech.collectAsState()
    val sound by viewModel.isSoundEnabled.collectAsState()
    val translate by viewModel.isTranslationVisible.collectAsState()
    val phonetic by viewModel.showPhoneticBeforeAnswer.collectAsState()
    val dark by viewModel.isDarkTheme.collectAsState()
    var backup by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    fun settings(ac: String = accent, rate: Float = speed, auto: Boolean = autoplay, phon: Boolean = phonetic, night: Boolean = dark) {
        viewModel.updateSettings(sound || auto, translate, ac, rate, auto, phon, night, size)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("settings_screen"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ActionRow(courseName(category), "$category · 当前课程", Icons.Outlined.MenuBook,
            { viewModel.setScreen(ScreenState.WORD_BOOK_EXPLORER) }) }
        item { SectionTitle("每日计划") }
        item {
            StudyCard {
                PlanSlider("新单词", new, 5..50, "个") { viewModel.updateStudyPlan(it, review, size) }
                PlanSlider("复习", review, 5..60, "个") { viewModel.updateStudyPlan(new, it, size) }
                PlanSlider("每组练习", size, 20..100, "个") { viewModel.updateStudyPlan(new, review, it) }
            }
        }
        item { SectionTitle("朗读") }
        item {
            StudyCard {
                Text("英语口音", style = MaterialTheme.typography.titleSmall)
                ChoiceGroup(listOf("US" to "美式英语", "UK" to "英式英语"), accent) { settings(ac = it) }
                Text("合成声音", style = MaterialTheme.typography.titleSmall)
                ChoiceGroup(listOf("female" to "女声", "male" to "男声"), gender, viewModel::selectPronunciationGender)
                TextButton(onClick = { viewModel.speakWord("ability") }) {
                    Icon(Icons.Outlined.VolumeUp, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("试听当前声音")
                }
                Text("语速 · ${String.format(java.util.Locale.ROOT, "%.1f", speed)} 倍",
                    style = MaterialTheme.typography.titleSmall)
                Slider(speed, { settings(rate = it) }, valueRange = 0.5f..2f, steps = 5)
                SwitchRow("自动播放单词发音", autoplay, { settings(auto = it) })
            }
        }
        item { SectionTitle("显示与学习") }
        item {
            StudyCard {
                SwitchRow("深色外观", dark, { settings(night = it) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SwitchRow("显示音标提示", phonetic, { settings(phon = it) }, "适用于听写和词义练习")
            }
        }
        item { ActionRow("设置备份", "导出或恢复学习偏好", Icons.Outlined.SaveAlt, {
            backup = true; message = ""
        }) }
        item { Text("LexiFlow · 1.3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (backup) AlertDialog(onDismissRequest = { backup = false }, title = { Text("设置备份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("备份包含课程与每日学习目标。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                exported = viewModel.exportBackupJson()
                clipboard.setText(AnnotatedString(exported)); message = "设置备份已复制"
            }) { Text("复制设置备份") }
            OutlinedTextField(imported, { imported = it }, label = { Text("粘贴设置备份") }, maxLines = 5)
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = {
        val result = viewModel.importBackupJson(imported)
        message = if (result) "设置已恢复" else "无法读取这份设置备份"
        if (result) imported = ""
    }, enabled = imported.isNotBlank()) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { backup = false }) { Text("完成") } })
}

@Composable
internal fun PlanSlider(title: String, current: Int, range: IntRange, unit: String, update: (Int) -> Unit) {
    var value by remember(current) { mutableFloatStateOf(current.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat())) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text("${value.toInt()} $unit", color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, { value = it }, onValueChangeFinished = { update(value.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat())
    }
}
