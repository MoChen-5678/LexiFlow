package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.QwertyViewModel

internal fun sentenceContains(sentence: String, answer: String): Boolean =
    Regex("(?<![A-Za-z])" + Regex.escape(answer) + "(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(sentence)

internal fun sentenceGap(sentence: String, answer: String): String =
    Regex("(?<![A-Za-z])" + Regex.escape(answer) + "(?![A-Za-z])", RegexOption.IGNORE_CASE).replace(sentence, "________")

@Composable
internal fun SentencePracticeScreen(viewModel: QwertyViewModel) {
    val state by viewModel.typingState.collectAsState()
    val word = viewModel.currentWord ?: return
    if (state.isCompleted) {
        SessionCompletedScreen(viewModel, state)
        return
    }
    SentenceQuestion(sentence = word.exampleSentence, meaning = word.translation, answer = word.word,
        translatedSentence = word.exampleTranslation, index = state.currentIndex + 1, total = state.currentWordList.size,
        input = state.typedText, revealed = state.isAnswerRevealed, update = viewModel::updateTypedText,
        submit = viewModel::submitClozeAnswer, reveal = viewModel::revealAnswerStageManually,
        speak = { viewModel.speakWord(word.exampleSentence) },
        next = { viewModel.gradeMemoryFeedback(if (state.typedText.trim().equals(word.word, true)) "GOOD" else "FORGOT") },
        busy = state.chosenQuality != null, level = viewModel.currentProgress?.memoryLevel ?: 0)
}

@Composable
internal fun SentenceQuestion(
    sentence: String, meaning: String, answer: String, translatedSentence: String,
    index: Int, total: Int, input: String, revealed: Boolean,
    update: (String) -> Unit, submit: () -> Unit, reveal: () -> Unit,
    speak: () -> Unit, next: () -> Unit, busy: Boolean, level: Int = 0
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val correct = input.trim().equals(answer, true)
    val primary = MaterialTheme.colorScheme.primary
    val textStyle = TextStyle(fontSize = 28.sp, lineHeight = 41.sp, fontFamily = FontFamily.Serif, color = primary)
    LaunchedEffect(index, revealed) { if (!revealed && sentenceContains(sentence, answer)) focus.requestFocus() }
    val matches = remember(sentence, answer) {
        Regex("(?<![A-Za-z])" + Regex.escape(answer) + "(?![A-Za-z])", RegexOption.IGNORE_CASE).findAll(sentence).toList()
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("sentence_practice")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$index / $total", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (index - 1).toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), drawStopIndicator = {})
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(5) { dot ->
                            Box(Modifier.size(width = 18.dp, height = 4.dp).background(
                                if (dot < level) primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                                MaterialTheme.shapes.small))
                        }
                    }
                    if (revealed || matches.isEmpty()) {
                        Text(sentence, style = textStyle, modifier = Modifier.testTag("gap_sentence"))
                    } else {
                        val annotated = buildAnnotatedString {
                            var start = 0
                            matches.forEachIndexed { i, match ->
                                append(sentence.substring(start, match.range.first))
                                appendInlineContent("blank$i", "________")
                                start = match.range.last + 1
                            }
                            append(sentence.substring(start))
                        }
                        val slots = matches.indices.associate { i ->
                            "blank$i" to InlineTextContent(Placeholder(
                                width = ((input.length + 1).coerceAtLeast(answer.length).coerceIn(6, 16) * 15).sp,
                                height = 42.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter)) {
                                BasicTextField(value = input, onValueChange = update, singleLine = true,
                                    textStyle = textStyle.copy(fontWeight = FontWeight.Medium), cursorBrush = SolidColor(primary),
                                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                        .then(if (i == 0) Modifier.focusRequester(focus).testTag("sentence_input").semantics { contentDescription = "填写缺少的单词" } else Modifier),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                                        autoCorrectEnabled = false, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (input.isNotBlank()) { keyboard?.hide(); submit() }
                                    }))
                            }
                        }
                        Text(annotated, style = textStyle, inlineContent = slots, modifier = Modifier.testTag("gap_sentence"))
                    }
                    if (revealed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (correct) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null,
                                Modifier.size(18.dp), tint = if (correct) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(if (correct) "答对了" else "正确答案  $answer", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(meaning, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("word_meaning"))
                    if (revealed && translatedSentence.isNotBlank()) Text(translatedSentence,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (revealed) TextButton(onClick = speak, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.VolumeUp, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("朗读例句")
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!revealed) TextButton(onClick = { keyboard?.hide(); reveal() }) { Text("显示答案") }
                else Text(if (correct) "已记住" else "会再次复习", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!revealed) Spacer(Modifier.weight(1f))
                Button(onClick = { keyboard?.hide(); if (revealed) next() else submit() },
                    enabled = !busy && (revealed || input.isNotBlank()),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (revealed) "sentence_next" else "sentence_submit"),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background)) {
                    Text(if (revealed) "继续" else "检查")
                    Spacer(Modifier.width(10.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(19.dp))
                }
            }
        }
    }
}
