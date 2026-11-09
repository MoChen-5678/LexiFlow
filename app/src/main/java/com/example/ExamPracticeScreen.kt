package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val exams = mapOf("cet4" to "四级", "cet6" to "六级", "ielts" to "雅思")
private val types = mapOf("all" to "综合", "reading" to "阅读", "listening" to "听力",
    "cloze" to "填空", "vocabulary" to "词义", "dictation" to "听写")

internal fun answerMatches(input: String, answer: String): Boolean {
    fun normalize(s: String) = s.trim().lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\s+"), " ")
    return normalize(input) == normalize(answer)
}

private fun fetchBatch(exam: String, kind: String): String {
    val connection = URL(BuildConfig.API_BASE + "/api/questions?exam=$exam&kind=$kind&limit=10")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        check(connection.responseCode == 200)
        val raw = connection.inputStream.bufferedReader().use { it.readText() }
        val questions = JSONObject(raw).getJSONArray("questions")
        check(questions.length() in 1..10)
        for (i in 0 until questions.length()) {
            val q = questions.getJSONObject(i)
            check(q.getString("exam") == exam && q.getString("kind") in types)
            for (field in listOf("prompt", "answer", "explanation", "speech")) q.getString(field)
            q.getJSONArray("choices")
        }
        return questions.toString()
    } finally {
        connection.disconnect()
    }
}

@Composable
fun ExamPracticeScreen(initialBook: String, speak: (String) -> Unit, exit: () -> Unit,
                      load: (String, String) -> String = ::fetchBatch) {
    val context = LocalContext.current
    val cache = remember { context.getSharedPreferences("exam_batches", 0) }
    var exam by rememberSaveable { mutableStateOf(initialBook.replace("-", "").lowercase(Locale.ROOT).takeIf { it in exams } ?: "cet4") }
    var kind by rememberSaveable { mutableStateOf("all") }
    var round by rememberSaveable(exam, kind) { mutableIntStateOf(0) }
    var batch by rememberSaveable(exam, kind, round) { mutableStateOf("") }
    var notice by rememberSaveable(exam, kind, round) { mutableStateOf("") }
    var index by rememberSaveable(exam, kind, round) { mutableIntStateOf(0) }
    var score by rememberSaveable(exam, kind, round) { mutableIntStateOf(0) }
    var input by rememberSaveable(exam, kind, round, index) { mutableStateOf("") }
    var graded by rememberSaveable(exam, kind, round, index) { mutableStateOf(false) }
    BackHandler(onBack = exit)

    LaunchedEffect(exam, kind, round) {
        if (batch.isNotEmpty()) return@LaunchedEffect
        val key = "$exam-$kind"
        try {
            val loaded = withContext(Dispatchers.IO) { load(exam, kind) }
            cache.edit().putString(key, loaded).apply()
            batch = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            val cached = cache.getString(key, null)
            batch = cached ?: withContext(Dispatchers.IO) {
                val all = context.assets.open("exam_questions.json").bufferedReader().use { JSONArray(it.readText()) }
                val subset = JSONArray()
                for (i in 0 until all.length()) {
                    val q = all.getJSONObject(i)
                    if (q.getString("exam") == exam && (kind == "all" || q.getString("kind") == kind)) subset.put(q)
                }
                subset.toString()
            }
            notice = if (cached == null) "当前使用内置练习。" else "当前使用上次下载的题组。"
        }
    }

    val questions = remember(batch) { if (batch.isEmpty()) JSONArray() else JSONArray(batch) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterRow(exams, exam) { exam = it }
        FilterRow(types, kind) { kind = it }
        if (notice.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(notice)
                TextButton(onClick = { round++ }) { Text("重新连接") }
            }
        }
        when {
            batch.isEmpty() && notice.isEmpty() -> CircularProgressIndicator()
            batch.isEmpty() -> Button(onClick = { round++ }) { Text("重试") }
            index >= questions.length() -> {
                Text("本组答对 $score / " + questions.length() + " 题", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { round++ }) { Text("再练一组") }
            }
            else -> {
                val q = questions.getJSONObject(index)
                val answer = q.getString("answer")
                val options = q.getJSONArray("choices")
                val speech = q.getString("speech").ifBlank { if (q.getString("kind") == "reading") q.getString("prompt") else "" }
                val audioPrompt = q.getString("kind") in listOf("dictation", "listening")
                Text("第 ${index + 1} / ${questions.length()} 题 · ${types[q.getString("kind")]}")
                Text(q.getString("prompt"), style = MaterialTheme.typography.titleMedium)
                if (speech.isNotBlank() && (audioPrompt || graded || q.getString("kind") in listOf("vocabulary", "reading"))) {
                    OutlinedButton(onClick = { speak(speech) }) { Text("朗读") }
                }
                if (options.length() > 0) {
                    for (i in 0 until options.length()) {
                        val value = options.getString(i)
                        OutlinedButton(onClick = { input = value }, enabled = !graded, modifier = Modifier.fillMaxWidth()) {
                            Text(if (input == value) "✓  $value" else value)
                        }
                    }
                } else {
                    OutlinedTextField(value = input, onValueChange = { input = it }, readOnly = graded,
                        label = { Text("你的答案") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (graded) {
                    Text(if (answerMatches(input, answer)) "答对了" else "正确答案  $answer", fontWeight = FontWeight.Bold)
                    Text(q.getString("explanation"))
                    if (audioPrompt) Text("听力原文  $speech")
                    Button(onClick = { index++ }) { Text(if (index + 1 == questions.length()) "查看成绩" else "下一题") }
                } else {
                    Button(onClick = {
                        if (!graded) {
                            if (answerMatches(input, answer)) score++
                            graded = true
                        }
                    }, enabled = input.isNotBlank()) { Text("提交答案") }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(items: Map<String, String>, selected: String, select: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (key, label) ->
            FilterChip(selected = selected == key, onClick = { select(key) }, label = { Text(label) })
        }
    }
}
