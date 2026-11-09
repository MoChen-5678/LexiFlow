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

private class StudyKeyError : Exception()

private val pastTypes = mapOf("all" to "综合", "reading" to "阅读", "listening" to "听力",
    "cloze" to "选词填空", "matching" to "段落匹配", "speaking" to "口语")

internal data class ExamQuery(val exam: String, val kind: String, val origin: String, val paper: String, val after: Int)

private fun listPapers(exam: String): List<String> {
    val c = URL(BuildConfig.API_BASE + "/api/papers?exam=$exam").openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 5000
        c.readTimeout = 8000
        val data = c.inputStream.bufferedReader().use { JSONObject(it.readText()).getJSONArray("papers") }
        return (0 until data.length()).map { data.getString(it) }.filter { it.matches(Regex("20[0-9]{2}_[0-9]{2}_[1-3]")) }
    } finally { c.disconnect() }
}

private fun fetchBatch(query: ExamQuery, studyKey: String): String {
    val (exam, kind, origin, paper, after) = query
    val path = "/api/questions?exam=$exam&kind=$kind&limit=10&origin=$origin&paper=$paper&after=$after"
    val connection = URL(BuildConfig.API_BASE + path)
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        if (origin == "past") {
            val stamp = (System.currentTimeMillis() / 1000).toString()
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(studyKey.toByteArray(), "HmacSHA256"))
            val signature = mac.doFinal((stamp + "\n" + path).toByteArray()).joinToString("") { "%02x".format(it) }
            connection.setRequestProperty("X-Study-Time", stamp)
            connection.setRequestProperty("X-Study-Signature", signature)
        }
        if (connection.responseCode == 403) throw StudyKeyError()
        check(connection.responseCode == 200)
        val raw = connection.inputStream.bufferedReader().use { it.readText() }
        val questions = JSONObject(raw).getJSONArray("questions")
        check(questions.length() in 0..10)
        for (i in 0 until questions.length()) {
            val q = questions.getJSONObject(i)
            check(q.getString("exam") == exam && q.getString("kind") in (types + pastTypes))
            for (field in listOf("prompt", "answer", "explanation", "speech")) q.getString(field)
            q.getJSONArray("choices")
        }
        return questions.toString()
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun ExamPracticeScreen(initialBook: String, speak: (String) -> Unit, exit: () -> Unit,
                      load: (ExamQuery, String) -> String = ::fetchBatch) {
    val context = LocalContext.current
    val cache = remember { context.getSharedPreferences("exam_batches", 0) }
    var exam by rememberSaveable { mutableStateOf(initialBook.replace("-", "").lowercase(Locale.ROOT).takeIf { it in exams } ?: "cet4") }
    var origin by rememberSaveable { mutableStateOf("practice") }
    var kind by rememberSaveable(exam, origin) { mutableStateOf("all") }
    val keyFile = remember { java.io.File(context.noBackupFilesDir, "study-key") }
    var studyKey by remember { mutableStateOf(if (keyFile.exists()) keyFile.readText().trim() else "") }
    var editKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var paper by rememberSaveable(origin, exam) { mutableStateOf("") }
    var after by rememberSaveable(origin, exam, kind, paper) { mutableIntStateOf(0) }
    var papers by remember(origin, exam) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(origin, exam) {
        if (origin == "past" && exam != "ielts") {
            papers = withContext(Dispatchers.IO) { try { listPapers(exam) } catch (_: java.io.IOException) { emptyList() } catch (_: org.json.JSONException) { emptyList() } }
        }
    }
    var round by rememberSaveable(origin, exam, kind, paper, after) { mutableIntStateOf(0) }
    var batch by rememberSaveable(origin, exam, kind, paper, after, round) { mutableStateOf("") }
    var notice by rememberSaveable(origin, exam, kind, paper, after, round) { mutableStateOf("") }
    var index by rememberSaveable(origin, exam, kind, paper, after, round) { mutableIntStateOf(0) }
    var score by rememberSaveable(origin, exam, kind, paper, after, round) { mutableIntStateOf(0) }
    var input by rememberSaveable(origin, exam, kind, paper, after, round, index) { mutableStateOf("") }
    var graded by rememberSaveable(origin, exam, kind, paper, after, round, index) { mutableStateOf(false) }
    BackHandler(onBack = exit)

    if (exam == "ielts" && origin == "practice") {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                FilterRow(exams, exam) { exam = it }
                FilterRow(mapOf("practice" to "模拟练习", "past" to "考生回忆"), origin) { origin = it }
            }
            IeltsSetBrowser(speak, Modifier.weight(1f))
        }
        return
    }

    LaunchedEffect(origin, exam, kind, paper, after, round, studyKey) {
        if (batch.isNotEmpty()) return@LaunchedEffect
        if (origin == "past" && studyKey.isBlank()) return@LaunchedEffect
        val key = "$exam-$kind"
        try {
            val loaded = withContext(Dispatchers.IO) { load(ExamQuery(exam, kind, origin, paper, after), studyKey) }
            if (origin == "practice") cache.edit().putString(key, loaded).apply()
            batch = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: StudyKeyError) {
            editKey = true
            notice = "学习码校验失败，请检查学习码和手机时间。"
        } catch (_: Exception) {
            if (origin == "past") {
                notice = "真题库暂时无法连接。"
                return@LaunchedEffect
            }
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
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterRow(exams, exam) { exam = it }
        FilterRow(mapOf("practice" to "模拟练习", "past" to if (exam == "ielts") "考生回忆" else "历年真题"), origin) { origin = it }
        val filters = if (origin == "practice") types else if (exam == "ielts") mapOf("all" to "口语") else pastTypes - "speaking"
        FilterRow(filters, kind) { kind = it }
        if (origin == "past" && (studyKey.isEmpty() || editKey)) {
            OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, label = { Text("个人学习码") },
                singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Text("请输入部署时生成的个人学习码。")
            Button(onClick = {
                studyKey = keyInput.trim()
                keyFile.writeText(studyKey)
                keyInput = ""
                editKey = false
                round++
            }, enabled = keyInput.isNotBlank()) { Text("进入题库") }
            if (notice.isNotEmpty()) Text(notice)
            return@Column
        }
        if (origin == "past") {
            TextButton(onClick = { editKey = true }) { Text("更换学习码") }
            if (papers.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(if (paper.isEmpty()) "全部试卷 · 随机抽题" else paper.replace("_", " / ")) }
                    DropdownMenu(expanded, { expanded = false }) {
                        (listOf("") + papers).forEach { id ->
                            DropdownMenuItem(text = { Text(if (id.isEmpty()) "全部试卷 · 随机抽题" else id.replace("_", " / ")) },
                                onClick = { paper = id; expanded = false })
                        }
                    }
                }
            }
        }
        if (notice.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(notice)
                TextButton(onClick = { round++ }) { Text("重新连接") }
            }
        }
        when {
            batch.isEmpty() && notice.isEmpty() -> CircularProgressIndicator()
            batch.isEmpty() -> Button(onClick = { round++ }) { Text("重试") }
            questions.length() == 0 -> Text(if (after > 0) "本卷所选题型已练完。" else "这一分类暂时没有题目，首次同步可能仍在进行。")
            index >= questions.length() -> {
                Text(if (origin == "past" && exam == "ielts") "已练习 " + questions.length() + " 道口语题" else "本组答对 $score / " + questions.length() + " 题", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { if (paper.isEmpty()) round++ else after = questions.getJSONObject(questions.length() - 1).getInt("number") }) { Text(if (paper.isEmpty()) "再练一组" else "继续本卷") }
            }
            else -> {
                val q = questions.getJSONObject(index)
                val answer = q.getString("answer")
                val options = q.getJSONArray("choices")
                val speech = q.getString("speech").ifBlank { if (q.getString("kind") == "reading") q.optString("context").ifBlank { q.getString("prompt") } else "" }
                val audioPrompt = q.getString("kind") in listOf("dictation", "listening")
                Text("第 ${index + 1} / ${questions.length()} 题 · ${(types + pastTypes)[q.getString("kind")]}")
                if (origin == "past") {
                    Text(q.getString("label"), fontWeight = FontWeight.Bold)
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    TextButton(onClick = { runCatching { uri.openUri(q.getString("source")) }.onFailure {
                        android.widget.Toast.makeText(context, "无法打开来源链接。", android.widget.Toast.LENGTH_SHORT).show()
                    } }) { Text("查看题目来源") }
                    if (q.optString("context").isNotBlank()) Text(q.getString("context"))
                    if (q.optString("audio").isNotBlank()) {
                        val mirrors = q.optJSONArray("audioMirrors")
                        val urls = if (mirrors == null) listOf(q.getString("audio")) else (0 until mirrors.length()).map { mirrors.getString(it) }
                        PaperRecording(urls)
                    }
                }
                Text(q.getString("prompt"), style = MaterialTheme.typography.titleMedium)
                if (speech.isNotBlank() && (audioPrompt || graded || q.getString("kind") in listOf("vocabulary", "reading", "speaking"))) {
                    OutlinedButton(onClick = { speak(speech) }) { Text("朗读") }
                }
                if (q.getString("kind") == "speaking") {
                    Text("口头作答后进入下一题。")
                    Button(onClick = { index++ }) { Text("完成本题") }
                    return@Column
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
                    if (audioPrompt && speech.isNotBlank()) Text("听力原文  $speech")
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
