package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal fun ieltsAnswerMatches(input: String, question: JSONObject): Boolean {
    val typed = input.trim()
    val limit = question.optInt("wordLimit")
    if (limit > 0 && typed.split(Regex("\\s+")).size > limit) return false
    val accepted = question.getJSONArray("answers")
    return (0 until accepted.length()).any { answerMatches(typed, accepted.getString(it)) }
}

private fun downloadIeltsSets(): String {
    val c = URL(BuildConfig.API_BASE + "/api/ielts/sets").openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 4000
        c.readTimeout = 6000
        check(c.responseCode == 200)
        val body = c.inputStream.bufferedReader().use { it.readText() }
        require(JSONArray(body).length() > 0)
        return body
    } finally { c.disconnect() }
}

@Composable
internal fun IeltsSetBrowser(speak: (String) -> Unit, modifier: Modifier = Modifier,
                             load: () -> String = ::downloadIeltsSets) {
    val context = LocalContext.current
    val bundled = remember { context.assets.open("ielts_sets.json").bufferedReader().use { it.readText() } }
    var raw by remember { mutableStateOf(bundled) }
    var selected by rememberSaveable { mutableStateOf("") }
    var skill by rememberSaveable { mutableStateOf("reading") }
    LaunchedEffect(Unit) {
        try {
            raw = withContext(Dispatchers.IO) { load() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Bundled sets remain available offline. */ }
    }
    val sets = remember(raw) { val all = JSONArray(raw); (0 until all.length()).map { all.getJSONObject(it) } }
    val active = remember(selected) { if (selected.isEmpty()) null else JSONObject(selected) }
    if (active != null) {
        androidx.activity.compose.BackHandler { speak(""); selected = "" }
        IeltsSetPlayer(active, speak, { speak(""); selected = "" }, modifier)
        return
    }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(skill == "reading", { skill = "reading" }, label = { Text("学术阅读") })
                FilterChip(skill == "listening", { skill = "listening" }, label = { Text("听力") })
            }
        }
        items(sets.filter { it.getString("skill") == skill }, key = { it.getString("id") }) { set ->
            ElevatedCard(onClick = { selected = set.toString() }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(set.getString("title"), style = MaterialTheme.typography.titleLarge)
                    Text("${set.getJSONArray("questions").length()} 题 · 原创专项练习")
                    Text(if (skill == "reading") "判断 · 选择 · 摘要填空" else "信息填空 · 选择")
                }
            }
        }
    }
}

@Composable
internal fun IeltsSetPlayer(set: JSONObject, speak: (String) -> Unit, back: () -> Unit, modifier: Modifier = Modifier) {
    val id = set.getString("id")
    var responses by rememberSaveable(id) { mutableStateOf("{}") }
    var submitted by rememberSaveable(id) { mutableStateOf(false) }
    val answers = remember(responses) { JSONObject(responses) }
    val questions = remember(id) { val all = set.getJSONArray("questions"); (0 until all.length()).map { all.getJSONObject(it) } }
    val listening = set.getString("skill") == "listening"
    val answered = questions.count { answers.optString(it.getInt("id").toString()).isNotBlank() }
    val correct = questions.count { ieltsAnswerMatches(answers.optString(it.getInt("id").toString()), it) }
    DisposableEffect(id) { onDispose { speak("") } }
    LazyColumn(modifier.padding(horizontal = 16.dp).imePadding().testTag("ielts_questions"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            TextButton(onClick = back) { Text("返回题组") }
            Text(set.getString("title"), style = MaterialTheme.typography.headlineSmall)
            Text(set.getString("instructions"))
            Text(if (submitted) "本组答对 $correct / ${questions.size} 题" else "已作答 $answered / ${questions.size} 题")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { speak(set.getString("speech")) }, modifier = Modifier.testTag("ielts_play")) {
                    Text(if (listening) "播放听力" else "朗读文章")
                }
                TextButton(onClick = { speak("") }) { Text("停止") }
            }
        }
        if (!listening || submitted) {
            item {
                Card {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (listening) Text("听力原文", fontWeight = FontWeight.Bold)
                        Text(set.getString(if (listening) "script" else "passage"), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        items(questions, key = { it.getInt("id") }) { question ->
            val key = question.getInt("id").toString()
            val response = answers.optString(key)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$key. " + question.getString("prompt"), style = MaterialTheme.typography.titleMedium)
                    Text(question.getString("instruction"), style = MaterialTheme.typography.bodySmall)
                    val options = question.getJSONArray("choices")
                    if (options.length() > 0) {
                        for (i in 0 until options.length()) {
                            val value = options.getString(i)
                            OutlinedButton(onClick = {
                                responses = JSONObject(responses).put(key, value).toString()
                            }, enabled = !submitted, modifier = Modifier.fillMaxWidth().testTag("ielts_${key}_option_$i")) {
                                Text(if (response == value) "✓ $value" else value)
                            }
                        }
                    } else {
                        OutlinedTextField(value = response, onValueChange = {
                            responses = JSONObject(responses).put(key, it).toString()
                        }, readOnly = submitted, label = { Text("答案") }, modifier = Modifier.fillMaxWidth().testTag("ielts_input_$key"),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    }
                    if (submitted) {
                        Text(if (ieltsAnswerMatches(response, question)) "答对了" else "正确答案  " + question.getJSONArray("answers").getString(0),
                            fontWeight = FontWeight.Bold)
                        Text(question.getString("explanation"))
                        Text(question.getString("evidence"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Button(onClick = { submitted = true; speak("") }, enabled = !submitted,
                modifier = Modifier.fillMaxWidth().testTag("ielts_submit")) {
                Text(if (submitted) "已提交" else "提交整组答案（$answered/${questions.size}）")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
