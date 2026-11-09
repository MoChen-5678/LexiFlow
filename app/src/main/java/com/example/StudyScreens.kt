package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ui.*

@Composable
internal fun WelcomeScreen(viewModel: QwertyViewModel) {
    var course by rememberSaveable { mutableStateOf("CET-4") }
    var fresh by rememberSaveable { mutableIntStateOf(10) }
    var review by rememberSaveable { mutableIntStateOf(15) }
    LazyColumn(Modifier.fillMaxSize().testTag("onboarding_screen"), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("LexiFlow", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("选择你的学习课程", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text("在语境中学习单词，按记忆进度安排复习。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            StudyCard {
                Text("选择课程", style = MaterialTheme.typography.titleMedium)
                viewModel.categories.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(course == code, { course = code })
                        TextButton(onClick = { course = code }) { Text(courseName(code)) }
                        Spacer(Modifier.weight(1f))
                        Text(code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("每日计划", style = MaterialTheme.typography.titleMedium)
                PlanSlider("新单词", fresh, 5..50, "个") { fresh = it }
                PlanSlider("复习", review, 5..60, "个") { review = it }
            }
        }
        item {
            Button(onClick = { viewModel.saveOnboardingSettings(course, fresh, review, 20) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("complete_onboarding"),
                shape = MaterialTheme.shapes.medium) { Text("开始学习") }
        }
    }
}

@Composable
internal fun PracticeSetupScreen(viewModel: QwertyViewModel) {
    val mode by viewModel.activeMode.collectAsState()
    val size by viewModel.wordsPerSession.collectAsState()
    var count by remember(size) { mutableIntStateOf(size) }
    val modes = listOf(GameMode.TYPING to "例句填空", GameMode.DICTATION to "听音拼写",
        GameMode.CHOICE to "词义选择", GameMode.MATCHING to "词义配对")
    LazyColumn(Modifier.fillMaxSize().testTag("practice_preview_screen"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("选择练习方式", style = MaterialTheme.typography.headlineMedium) }
        item {
            StudyCard {
                modes.forEach { (value, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == value || mode == GameMode.CLOZE && value == GameMode.TYPING,
                            { viewModel.setMode(value) })
                        TextButton(onClick = { viewModel.setMode(value) }) { Text(title) }
                    }
                }
            }
        }
        item { StudyCard { PlanSlider("每组练习", count, 20..100, "词") { count = it } } }
        item {
            Button(onClick = {
                viewModel.updateStudyPlan(viewModel.dailyNewWordsTarget.value, viewModel.dailyReviewTarget.value, count)
                viewModel.startPracticeSession(mode)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("start_practice_arena_button"),
                shape = MaterialTheme.shapes.medium) { Text("开始练习") }
        }
    }
}

@Composable
internal fun SessionSummaryScreen(viewModel: QwertyViewModel, state: TypingArenaState) {
    LazyColumn(Modifier.fillMaxSize().testTag("session_completed_screen"), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Spacer(Modifier.height(36.dp))
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Outlined.Check, null, Modifier.padding(20.dp).size(42.dp), tint = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(24.dp))
            Text("这一组，完成了", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            StudyCard {
                Row {
                    Metric(state.currentWordList.size.toString(), "本组单词", Modifier.weight(1f))
                    Metric("${state.liveAccuracy.toInt()}%", "正确率", Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text("复习进度已保存", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(onClick = { viewModel.startPracticeSession(viewModel.activeMode.value, true) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) { Text("再学一组") }
            TextButton(onClick = { viewModel.setScreen(ScreenState.DASHBOARD) },
                modifier = Modifier.fillMaxWidth().testTag("back_to_dashboard")) { Text("返回首页") }
        }
    }
}

@Composable
internal fun AlternativePracticeScreen(viewModel: QwertyViewModel) {
    val state by viewModel.typingState.collectAsState()
    val mode by viewModel.activeMode.collectAsState()
    val showPhonetic by viewModel.showPhoneticBeforeAnswer.collectAsState()
    val word = viewModel.currentWord
    if (state.isCompleted || word == null) {
        SessionSummaryScreen(viewModel, state)
        return
    }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        LinearProgressIndicator(progress = {
            if (mode == GameMode.MATCHING) state.matchedPairs.size.toFloat() / state.currentWordList.size.coerceAtLeast(1)
            else state.currentIndex.toFloat() / state.currentWordList.size.coerceAtLeast(1)
        }, modifier = Modifier.fillMaxWidth())
        if (mode == GameMode.MATCHING) {
            Text("将单词与释义配对", style = MaterialTheme.typography.headlineSmall)
            if (state.wrongMatches.isNotEmpty()) Text("配对不正确，再试一次", color = MaterialTheme.colorScheme.error)
            Row(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.matchingLeft.forEach { text ->
                        val done = state.matchedPairs.any { it.first == text }
                        OutlinedButton(onClick = { viewModel.selectMatchingItem(text, true) }, enabled = !done,
                            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.selectedLeft == text)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Text(if (done) "✓ $text" else text)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.matchingRight.forEach { text ->
                        val done = state.matchedPairs.any { it.second == text }
                        OutlinedButton(onClick = { viewModel.selectMatchingItem(text, false) }, enabled = !done,
                            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.selectedRight == text)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Text(if (done) "✓ $text" else text)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    StudyCard {
                        Text("${state.currentIndex + 1} / ${state.currentWordList.size}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (mode == GameMode.CHOICE || state.isAnswerRevealed) {
                            Text(word.word, style = MaterialTheme.typography.headlineLarge)
                        }
                        if ((showPhonetic || state.isAnswerRevealed) && word.phonetic.isNotBlank()) {
                            Text(word.phonetic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { viewModel.triggerSpeakForCurrentWord(manual = true) }) {
                            Icon(Icons.Outlined.VolumeUp, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("播放发音")
                        }
                        if (state.isAnswerRevealed) Text(word.translation)
                    }
                }
                if (mode == GameMode.CHOICE) {
                    state.currentChoices.forEachIndexed { index, value ->
                        item {
                            OutlinedButton(onClick = { viewModel.selectChoice(index) }, enabled = !state.isAnswerRevealed,
                                modifier = Modifier.fillMaxWidth().testTag("choice_item_$index"), shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.selectedChoiceIndex == index)
                                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                                Text(value)
                            }
                        }
                    }
                } else {
                    item {
                        OutlinedTextField(state.typedText, viewModel::updateTypedText, readOnly = state.isAnswerRevealed,
                            label = { Text("输入听到的单词") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); viewModel.submitClozeAnswer() }))
                    }
                    if (state.isAnswerRevealed) item {
                        Text(if (state.typedText.trim().equals(word.word, true)) "答对了" else "正确答案  ${word.word}",
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (mode == GameMode.DICTATION) {
                Button(onClick = {
                    keyboard?.hide()
                    if (state.isAnswerRevealed) viewModel.gradeMemoryFeedback(
                        if (state.typedText.trim().equals(word.word, true)) "GOOD" else "FORGOT")
                    else viewModel.submitClozeAnswer()
                }, enabled = (state.isAnswerRevealed || state.typedText.isNotBlank()) && state.chosenQuality == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                    Text(if (state.isAnswerRevealed) "下一词" else "检查答案")
                }
                if (!state.isAnswerRevealed) TextButton(onClick = { viewModel.revealAnswerStageManually() }) { Text("显示答案") }
            }
        }
    }
}
