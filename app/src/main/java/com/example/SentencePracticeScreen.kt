package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.QwertyViewModel

internal fun sentenceContains(sentence: String, answer: String): Boolean =
    Regex("(?<![A-Za-z])" + Regex.escape(answer) + "(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(sentence)

internal fun sentenceGap(sentence: String, answer: String): String =
    Regex("(?<![A-Za-z])" + Regex.escape(answer) + "(?![A-Za-z])", RegexOption.IGNORE_CASE)
        .replace(sentence, "________")

@Composable
internal fun SentencePracticeScreen(viewModel: QwertyViewModel) {
    val state by viewModel.typingState.collectAsState()
    val word = viewModel.currentWord ?: return
    if (state.isCompleted) {
        SessionCompletedScreen(viewModel, state)
        return
    }
    SentenceQuestion(
        sentence = word.exampleSentence, meaning = word.translation, answer = word.word,
        translatedSentence = word.exampleTranslation, index = state.currentIndex + 1,
        total = state.currentWordList.size, input = state.typedText, revealed = state.isAnswerRevealed,
        update = viewModel::updateTypedText, submit = viewModel::submitClozeAnswer,
        reveal = viewModel::revealAnswerStageManually,
        speak = { viewModel.speakWord(word.exampleSentence) },
        next = { viewModel.gradeMemoryFeedback(if (state.typedText.trim().equals(word.word, true)) "GOOD" else "FORGOT") },
        busy = state.chosenQuality != null
    )
}

@Composable
internal fun SentenceQuestion(
    sentence: String, meaning: String, answer: String, translatedSentence: String,
    index: Int, total: Int, input: String, revealed: Boolean,
    update: (String) -> Unit, submit: () -> Unit, reveal: () -> Unit,
    speak: () -> Unit, next: () -> Unit, busy: Boolean
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("sentence_practice"),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("$index / $total", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = index.toFloat() / total.coerceAtLeast(1), modifier = Modifier.fillMaxWidth())
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(if (revealed) sentence else sentenceGap(sentence, answer),
                    fontSize = 25.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("gap_sentence"))
                HorizontalDivider()
                Text(meaning, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("word_meaning"))
            }
        }
        if (!revealed) {
            OutlinedTextField(
                value = input, onValueChange = update, singleLine = true,
                label = { Text("填入缺少的单词") }, modifier = Modifier.fillMaxWidth().testTag("sentence_input"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) { keyboard?.hide(); submit() } })
            )
            Button(onClick = { keyboard?.hide(); submit() }, enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("sentence_submit")) { Text("检查答案") }
            TextButton(onClick = { keyboard?.hide(); reveal() }) { Text("想不起来，查看答案") }
        } else {
            val correct = input.trim().equals(answer, ignoreCase = true)
            Text(if (correct) "答对了" else "正确答案  $answer", fontWeight = FontWeight.Bold,
                color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (input.isNotBlank() && !correct) Text("你的答案  $input")
            if (translatedSentence.isNotBlank()) Text(translatedSentence)
            OutlinedButton(onClick = speak) { Text("朗读完整例句") }
            Button(onClick = next, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("sentence_next")) {
                Text(if (correct) "下一词" else "加入复习，下一词")
            }
        }
    }
}
