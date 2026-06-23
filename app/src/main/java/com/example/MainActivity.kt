@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
package com.example

import android.app.Application
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.*
import com.example.ui.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize standard TextToSpeech engine
        tts = TextToSpeech(this, this)
        
        enableEdgeToEdge()
        setContent {
            val database = AppDatabase.getDatabase(applicationContext)
            val repository = WordRepository(database.wordDao())
            val factory = QwertyViewModelFactory(applicationContext as Application, repository)
            val viewModel: QwertyViewModel = viewModel(factory = factory)
            val isDark by viewModel.isDarkTheme.collectAsState()

            MyApplicationTheme(darkTheme = isDark) {
                val context = LocalContext.current

                // TTS trigger collector
                LaunchedEffect(viewModel.speakEvent) {
                    viewModel.speakEvent.collect { word ->
                        speak(word, viewModel.pronunciationAccent.value, viewModel.pronunciationSpeed.value)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            }
        }
    }

    fun speak(text: String, accent: String = "US", speed: Float = 1.0f) {
        if (isTtsReady) {
            tts?.setLanguage(if (accent == "UK") Locale.UK else Locale.US)
            tts?.setSpeechRate(speed)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lexiflow_tts")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

// Global text sentence masker
fun maskSentence(sentence: String, target: String): String {
    if (sentence.isEmpty() || target.isEmpty()) return sentence
    val regex = "\\b${target}(?:ing|ed|s|es)?\\b".toRegex(RegexOption.IGNORE_CASE)
    return sentence.replace(regex, "_______")
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainScreen(viewModel: QwertyViewModel) {
    val screenState by viewModel.screenState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // Request key focus on practice screen to allow physical keyboards
    LaunchedEffect(screenState) {
        if (screenState == ScreenState.TYPING_ARENA) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val codePoint = keyEvent.utf16CodePoint
                    if (codePoint > 0) {
                        val char = codePoint.toChar()
                        if (char.isLetter()) {
                            viewModel.typeChar(char)
                            return@onKeyEvent true
                        }
                    } else if (keyEvent.key == Key.Backspace) {
                        viewModel.backspaceTypedText()
                        return@onKeyEvent true
                    }
                }
                false
            }
    ) {
        Scaffold(
            topBar = { AppHeader(viewModel) },
            bottomBar = { BottomNavBar(viewModel) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AnimatedContent(
                    targetState = screenState,
                    transitionSpec = {
                        fadeIn() with fadeOut()
                    }
                ) { targetScreen ->
                    when (targetScreen) {
                        ScreenState.ONBOARDING -> OnboardingScreen(viewModel)
                        ScreenState.DASHBOARD -> DashboardScreen(viewModel)
                        ScreenState.PRACTICE_PREVIEW -> PracticePreviewScreen(viewModel)
                        ScreenState.TYPING_ARENA -> TypingArenaScreen(viewModel)
                        ScreenState.MISTAKES_BOOK -> MistakesBookScreen(viewModel)
                        ScreenState.WORD_BOOK_EXPLORER -> WordBookExplorerScreen(viewModel)
                        ScreenState.STATS_HISTORY -> StatsHistoryScreen(viewModel)
                        ScreenState.SETTINGS -> SettingsScreen(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun AppHeader(viewModel: QwertyViewModel) {
    val screenState by viewModel.screenState.collectAsState()
    val category by viewModel.selectedCategory.collectAsState()
    val streak = viewModel.getStreakCount()

    val headerTitle = when (screenState) {
        ScreenState.ONBOARDING -> "首次使用引导"
        ScreenState.DASHBOARD -> "词流 LexiFlow"
        ScreenState.PRACTICE_PREVIEW -> "配置练习计划"
        ScreenState.TYPING_ARENA -> "学而时习之"
        ScreenState.MISTAKES_BOOK -> "错词本"
        ScreenState.WORD_BOOK_EXPLORER -> "词书探索"
        ScreenState.STATS_HISTORY -> "我的学习成长"
        ScreenState.SETTINGS -> "个性化设置"
    }

    CenterAlignedTopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF061033), Color(0xFF003399))
                            )
                        )
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "LexiFlow Logo",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        },
        actions = {
            if (screenState != ScreenState.ONBOARDING) {
                Row(
                    modifier = Modifier.padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Streak Fire",
                        tint = Color(0xFFFF5722),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "$streak 天",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5722)
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    SuggestionChip(
                        onClick = { viewModel.setScreen(ScreenState.SETTINGS) },
                        label = { Text(category) }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.testTag("app_header")
    )
}

@Composable
fun OnboardingScreen(viewModel: QwertyViewModel) {
    var selectedCategory by remember { mutableStateOf("CET-4") }
    var dailyNewWords by remember { mutableStateOf(10) }
    var dailyReviews by remember { mutableStateOf(15) }
    var wordsPerSession by remember { mutableStateOf(20) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("onboarding_screen"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF061033), Color(0xFF003399))
                        )
                    )
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "LexiFlow Logo",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        item {
            Text(
                text = "欢迎来到 词流 LexiFlow",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "基于物理盲打肌肉记忆与 SM-2 科学遗忘曲线算法的智能极速单词学习工具",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "1. 选择目标英语词书",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("CET-4", "CET-6", "IELTS", "TOEFL", "Coder").forEach { cat ->
                            val isSelected = selectedCategory == cat
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                    .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                    .clickable { selectedCategory = cat }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    cat,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "2. 设定每日新词量: $dailyNewWords 个",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = dailyNewWords.toFloat(),
                        onValueChange = { dailyNewWords = it.toInt() },
                        valueRange = 5f..50f,
                        steps = 8
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "3. 设定每日复习量: $dailyReviews 个",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = dailyReviews.toFloat(),
                        onValueChange = { dailyReviews = it.toInt() },
                        valueRange = 5f..60f,
                        steps = 11
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "4. 单场练习量上限: $wordsPerSession 个",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = wordsPerSession.toFloat(),
                        onValueChange = { wordsPerSession = it.toInt() },
                        valueRange = 20f..100f,
                        steps = 8
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    viewModel.saveOnboardingSettings(selectedCategory, dailyNewWords, dailyReviews, wordsPerSession)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("complete_onboarding"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("进入词流竞技场 🚀", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(viewModel: QwertyViewModel) {
    val category by viewModel.selectedCategory.collectAsState()
    val plan by viewModel.smartTodayPlan.collectAsState()
    val allWords by viewModel.allWordsWithProgress.collectAsState()

    val unlearned = plan.first
    val dueWrong = plan.second
    val dueCorrect = plan.third

    val totalCount = allWords.filter { it.word.category.equals(category, ignoreCase = true) }.size
    val learnedCount = allWords.filter { it.word.category.equals(category, ignoreCase = true) && it.progress != null }.size
    val progressPercent = if (totalCount > 0) (learnedCount.toFloat() / totalCount) else 0f

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcoming card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "嗨，词流学者！",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "极速敲击，瞬时记忆。今天依然是超越自我的一天！",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Text(
                        "🔥",
                        fontSize = 36.sp,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }

        // Active book stats
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "当前词书进度: $category",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            String.format("%.1f%%", progressPercent * 100f),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    
                    LinearProgressIndicator(
                        progress = progressPercent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("已掌握: $learnedCount 词", style = MaterialTheme.typography.labelMedium)
                        Text("未开始: ${totalCount - learnedCount} 词", style = MaterialTheme.typography.labelMedium)
                        Text("共计: $totalCount 词", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Today's task split & personalized study trigger
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "今日任务分析",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${unlearned.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("未学新词", style = MaterialTheme.typography.labelSmall)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${dueWrong.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ErrorRed)
                            Text("到期错词", style = MaterialTheme.typography.labelSmall)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${dueCorrect.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CorrectGreen)
                            Text("巩固复习", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.startPracticeSession(GameMode.TYPING, usePersonalizedPlan = true)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("start_personalized_learning"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("🧠 一键开始个性化极速复习", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }

        // 5 Game Modes breakdown
        item {
            Text(
                "全脑训练竞技场",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        item {
            val modes = listOf(
                Triple(GameMode.TYPING, "⌨ 键盘盲打拼写", "逐字母输入拼写，建立极速肌肉反射记忆。"),
                Triple(GameMode.CHOICE, "📝 四选一英汉测试", "快速反应中英文释义，考核语义熟练度。"),
                Triple(GameMode.CLOZE, "📖 语境例句挖空", "根据英文语境遮盖，填入拼写最契合的词汇。"),
                Triple(GameMode.DICTATION, "🔊 真人原声听写", "只听发音不看字幕拼写，打通听觉记忆链路。"),
                Triple(GameMode.MATCHING, "⭐ 趣味连线连连看", "连线连连看：在 4x4 中迅速配对英语和中文。")
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                modes.forEach { (mode, title, desc) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setMode(mode)
                                viewModel.setScreen(ScreenState.PRACTICE_PREVIEW)
                            }
                            .testTag("mode_${mode.name}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun PracticePreviewScreen(viewModel: QwertyViewModel) {
    val mode by viewModel.activeMode.collectAsState()
    val category by viewModel.selectedCategory.collectAsState()
    var wordLimit by remember { mutableStateOf(20) }

    val modeTitle = when (mode) {
        GameMode.TYPING -> "⌨ 键盘盲打拼写"
        GameMode.CHOICE -> "📝 四选一英汉测试"
        GameMode.CLOZE -> "📖 语境例句挖空"
        GameMode.DICTATION -> "🔊 真人原声听写"
        GameMode.MATCHING -> "⭐ 趣味连线连连看"
    }

    val modeDesc = when (mode) {
        GameMode.TYPING -> "在虚拟全键盘上敲击拼写，快速形成肌肉记忆。"
        GameMode.CHOICE -> "考核中英文互译，挑战你的英汉反射速度。"
        GameMode.CLOZE -> "将目标词形变填入英文例句的空格中，建立语境锚点。"
        GameMode.DICTATION -> "专为听力强化设计，纯发音拼写，不带视觉干扰。"
        GameMode.MATCHING -> "将左侧英语单词与右侧中文释义在网格连线相匹配。"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("practice_preview_screen"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = modeTitle,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = modeDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("目标分类:", fontWeight = FontWeight.Bold)
                        Text(category, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("每轮练习数量:", fontWeight = FontWeight.Bold)
                        Text("$wordLimit 个单词", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Slider(
                        value = wordLimit.toFloat(),
                        onValueChange = { wordLimit = it.toInt() },
                        valueRange = 20f..100f,
                        steps = 8
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    viewModel.wordsPerSession.value = wordLimit
                    viewModel.startPracticeSession(mode, usePersonalizedPlan = false)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("start_practice_arena_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("进入竞技场竞技 🚀", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun TypingArenaScreen(viewModel: QwertyViewModel) {
    val state by viewModel.typingState.collectAsState()
    val activeMode by viewModel.activeMode.collectAsState()
    val showPhoneticBefore by viewModel.showPhoneticBeforeAnswer.collectAsState()

    val currentWord = viewModel.currentWord

    if (state.isCompleted || currentWord == null) {
        SessionCompletedScreen(viewModel, state)
        return
    }

    val totalWords = state.currentWordList.size
    val currentIndex = state.currentIndex + 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("typing_arena_screen"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "单词进度: $currentIndex/$totalWords",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("${state.liveWpm} WPM") }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(String.format("%.0f%% 正确率", state.liveAccuracy)) }
                )
            }
        }

        // Active Card Board
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (activeMode) {
                        GameMode.TYPING, GameMode.DICTATION, GameMode.CLOZE -> {
                            val targetWord = currentWord.word
                            val isRevealed = state.isAnswerRevealed

                            // Masking sentence for cloze
                            val renderedText = if (activeMode == GameMode.CLOZE) {
                                maskSentence(currentWord.exampleSentence, targetWord)
                            } else if (activeMode == GameMode.DICTATION && !isRevealed) {
                                "🔊 [标准美/英真人发音已就绪，请聆听拼写]"
                            } else {
                                targetWord
                            }

                            // Cloze translation instruction
                            // Cloze translation instruction
                            if (activeMode == GameMode.CLOZE) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                ) {
                                    Text(
                                        text = "中文释义：${currentWord.translation}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "例句翻译：${currentWord.exampleTranslation}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Main word display
                            if (activeMode != GameMode.DICTATION || isRevealed) {
                                Text(
                                    text = renderedText,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                IconButton(
                                    onClick = { viewModel.triggerSpeakForCurrentWord() },
                                    modifier = Modifier.size(72.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Speak Pronunciation",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }

                            // Dynamic Phonetic reveal
                            if (isRevealed || showPhoneticBefore) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "/[ ${currentWord.phonetic} ]/",
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = { viewModel.triggerSpeakForCurrentWord() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play sound",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Word spell input text
                            if (activeMode == GameMode.CLOZE) {
                                if (!isRevealed) {
                                    OutlinedTextField(
                                        value = state.typedText,
                                        onValueChange = { viewModel.updateTypedText(it) },
                                        label = { Text("填入缺少的英文单词") },
                                        placeholder = { Text("输入完整单词") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                            .testTag("cloze_input"),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Done,
                                            keyboardType = KeyboardType.Text
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = { viewModel.submitClozeAnswer() }
                                        )
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = { 
                                                viewModel.updateTypedText("")
                                                viewModel.revealAnswerStageManually() 
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            modifier = Modifier.weight(1f).height(48.dp).testTag("cloze_dont_know")
                                        ) {
                                            Text("不知道 🔍", fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = { viewModel.submitClozeAnswer() },
                                            modifier = Modifier.weight(1f).height(48.dp).testTag("cloze_submit"),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            Text("提交 🚀", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                if (!isRevealed) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (state.isErrorFlashing) ErrorRed.copy(alpha = 0.15f)
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            targetWord.forEachIndexed { idx, char ->
                                                val isTyped = idx < state.typedText.length
                                                val color = if (isTyped) CorrectGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                val border = if (isTyped) BorderStroke(0.dp, Color.Transparent) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(if (isTyped) color.copy(alpha = 0.15f) else Color.Transparent)
                                                        .border(border.width, border.brush, RoundedCornerShape(4.dp)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (isTyped) state.typedText[idx].toString() else "",
                                                        fontWeight = FontWeight.Bold,
                                                        color = color
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Reveal answer phase definitions and sentences
                            if (isRevealed) {
                                Divider()

                                if (activeMode == GameMode.CLOZE) {
                                    val trimmedInput = state.typedText.trim()
                                    val isCorrect = trimmedInput.equals(targetWord, ignoreCase = true)
                                    val distance = viewModel.levenshteinDistance(trimmedInput.lowercase(), targetWord.lowercase())
                                    val isTypo = !isCorrect && distance == 1
                                    
                                    val resultText = when {
                                        isCorrect -> "🎉 完全正确！"
                                        isTypo -> "⚠️ 拼写相近（有 1 处拼写错误）"
                                        trimmedInput.isEmpty() -> "🔍 未填写答案"
                                        else -> "❌ 拼写错误"
                                    }
                                    
                                    val resultColor = when {
                                        isCorrect -> CorrectGreen
                                        isTypo -> Color(0xFFFF9800)
                                        else -> ErrorRed
                                    }

                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(containerColor = resultColor.copy(alpha = 0.08f)),
                                        border = BorderStroke(1.dp, resultColor.copy(alpha = 0.3f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("正确答案：$targetWord", fontWeight = FontWeight.Bold, color = CorrectGreen)
                                            if (trimmedInput.isNotEmpty()) {
                                                Text("你的答案：$trimmedInput", fontWeight = FontWeight.Bold, color = if (isCorrect) CorrectGreen else ErrorRed)
                                            }
                                            Text("评估结果：$resultText", fontWeight = FontWeight.Bold, color = resultColor)
                                        }
                                    }
                                }

                                Text(
                                    currentWord.translation,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    currentWord.exampleSentence,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    currentWord.exampleTranslation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Subjective grading choices
                                Text("自评估：你对这个单词的记忆程度如何？", style = MaterialTheme.typography.labelSmall)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.gradeMemoryFeedback("FORGOT") },
                                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                                        modifier = Modifier.weight(1f).testTag("grade_forgot")
                                    ) {
                                        Text("忘记", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { viewModel.gradeMemoryFeedback("HARD") },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                        modifier = Modifier.weight(1f).testTag("grade_hard")
                                    ) {
                                        Text("模糊", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { viewModel.gradeMemoryFeedback("GOOD") },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f).testTag("grade_good")
                                    ) {
                                        Text("认识", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { viewModel.gradeMemoryFeedback("EASY") },
                                        colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen),
                                        modifier = Modifier.weight(1f).testTag("grade_easy")
                                    ) {
                                        Text("秒杀", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                if (activeMode != GameMode.CLOZE) {
                                    // Manual show answer option
                                    TextButton(
                                        onClick = { viewModel.revealAnswerStageManually() },
                                        modifier = Modifier.testTag("show_answer_manual")
                                    ) {
                                        Text("不知道？显示答案并评估 🔍")
                                    }
                                }
                            }
                        }

                        GameMode.CHOICE -> {
                            Text(
                                text = currentWord.word,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )

                            if (state.isAnswerRevealed || showPhoneticBefore) {
                                Text(
                                    text = "/[ ${currentWord.phonetic} ]/",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                state.currentChoices.forEachIndexed { idx, option ->
                                    val isSelected = state.selectedChoiceIndex == idx
                                    val isCorrectOption = option == currentWord.translation
                                    
                                    val color = when {
                                        isSelected && state.isChoiceCorrect == true -> CorrectGreen
                                        isSelected && state.isChoiceCorrect == false -> ErrorRed
                                        state.selectedChoiceIndex != null && isCorrectOption -> CorrectGreen
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }

                                    val textColor = if (color == MaterialTheme.colorScheme.surfaceVariant) MaterialTheme.colorScheme.onSurfaceVariant
                                    else Color.White

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = state.selectedChoiceIndex == null) {
                                                viewModel.selectChoice(idx)
                                            }
                                            .testTag("choice_item_$idx"),
                                        colors = CardDefaults.cardColors(containerColor = color)
                                    ) {
                                        Text(
                                            text = option,
                                            modifier = Modifier.padding(14.dp),
                                            fontWeight = FontWeight.Bold,
                                            color = textColor,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        GameMode.MATCHING -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Left english column
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("英语单词", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    state.matchingLeft.forEach { item ->
                                        val isSolved = state.matchedPairs.any { it.first == item }
                                        val isSelected = state.selectedLeft == item
                                        val isWrong = state.wrongMatches.any { it.first == item }

                                        val bg = when {
                                            isSolved -> CorrectGreen.copy(alpha = 0.15f)
                                            isWrong -> ErrorRed.copy(alpha = 0.15f)
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }

                                        val border = when {
                                            isSolved -> BorderStroke(1.dp, CorrectGreen)
                                            isWrong -> BorderStroke(1.dp, ErrorRed)
                                            isSelected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                            else -> BorderStroke(1.dp, Color.Transparent)
                                        }

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = !isSolved) {
                                                    viewModel.selectMatchingItem(item, isEnglish = true)
                                                }
                                                .testTag("match_left_$item"),
                                            colors = CardDefaults.cardColors(containerColor = bg),
                                            border = border
                                        ) {
                                            Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                                                Text(item, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }

                                // Right chinese column
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("汉语译文", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    state.matchingRight.forEach { item ->
                                        val isSolved = state.matchedPairs.any { it.second == item }
                                        val isSelected = state.selectedRight == item
                                        val isWrong = state.wrongMatches.any { it.second == item }

                                        val bg = when {
                                            isSolved -> CorrectGreen.copy(alpha = 0.15f)
                                            isWrong -> ErrorRed.copy(alpha = 0.15f)
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }

                                        val border = when {
                                            isSolved -> BorderStroke(1.dp, CorrectGreen)
                                            isWrong -> BorderStroke(1.dp, ErrorRed)
                                            isSelected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                            else -> BorderStroke(1.dp, Color.Transparent)
                                        }

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = !isSolved) {
                                                    viewModel.selectMatchingItem(item, isEnglish = false)
                                                }
                                                .testTag("match_right_$item"),
                                            colors = CardDefaults.cardColors(containerColor = bg),
                                            border = border
                                        ) {
                                            Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                                                Text(item, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Virtual On-Screen Keyboard
        if ((activeMode == GameMode.TYPING || activeMode == GameMode.DICTATION || activeMode == GameMode.CLOZE) && !state.isAnswerRevealed) {
            VirtualQwertyKeyboard(
                onCharTyped = { char -> viewModel.typeChar(char) },
                onBackspace = { viewModel.backspaceTypedText() }
            )
        } else if (activeMode != GameMode.TYPING && activeMode != GameMode.DICTATION && activeMode != GameMode.CLOZE) {
            Button(
                onClick = { viewModel.startPracticeSession(activeMode) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("换一组单词重新挑战", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun VirtualQwertyKeyboard(
    onCharTyped: (Char) -> Unit,
    onBackspace: () -> Unit
) {
    val rows = listOf(
        listOf('Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'),
        listOf('A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'),
        listOf('Z', 'X', 'C', 'V', 'B', 'N', 'M')
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (rowIndex == 1) Spacer(modifier = Modifier.weight(0.5f))
                if (rowIndex == 2) Spacer(modifier = Modifier.weight(1f))

                row.forEach { char ->
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .aspectRatio(0.85f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .clickable { onCharTyped(char) }
                            .testTag("key_$char"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char.toString(),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }

                if (rowIndex == 2) {
                    // Custom Backspace button
                    Box(
                        modifier = Modifier
                            .weight(3f)
                            .aspectRatio(1.2f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            .clickable { onBackspace() }
                            .testTag("key_backspace"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Backspace",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (rowIndex == 1) Spacer(modifier = Modifier.weight(0.5f))
                if (rowIndex == 2) Spacer(modifier = Modifier.weight(0.2f))
            }
        }
    }
}

@Composable
fun SessionCompletedScreen(viewModel: QwertyViewModel, state: TypingArenaState) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("session_completed_screen"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    "🎉 极速词流，大获全胜！",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = CorrectGreen,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "本次练习的所有单词已写入 SM-2 遗忘曲线库中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${state.liveWpm}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("练习速度 WPM", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(String.format("%.0f%%", state.liveAccuracy), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CorrectGreen)
                            Text("平均正确率", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("本次竞技成果：", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("新学单词数量:")
                            Text("${viewModel.sessionNewCount.value} 个", fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("完成复习巩固:")
                            Text("${viewModel.sessionCorrectCount.value} 个", fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("强化历史错词:")
                            Text("${viewModel.sessionWrongCount.value} 个", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.setScreen(ScreenState.DASHBOARD) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("back_to_dashboard"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("返回主控制台", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun WordBookExplorerScreen(viewModel: QwertyViewModel) {
    val query by viewModel.searchQuery.collectAsState()
    val words by viewModel.filteredExplorerWords.collectAsState()
    val activeFilter by viewModel.selectedMemoryLevelFilter.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("word_book_explorer_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.searchQuery.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("explorer_search_bar"),
            placeholder = { Text("搜索单词、释义...") },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Filter chips list row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val filters = listOf(
                Pair("ALL", "全部"),
                Pair("DUE", "待复习"),
                Pair("NEW", "未学习"),
                Pair("1", "L1阶"),
                Pair("2", "L2阶"),
                Pair("3", "L3阶"),
                Pair("4", "L4阶"),
                Pair("5", "L5阶")
            )

            items(filters) { (id, label) ->
                val isSelected = activeFilter == id
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.selectedMemoryLevelFilter.value = id },
                    label = { Text(label) }
                )
            }
        }

        // List explorer
        if (words.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂未搜索到符合过滤条件的单词 🔍",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(words) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        item.word.word,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(
                                        onClick = { viewModel.speakWord(item.word.word) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play Pronunciation",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Text(
                                    "/[ ${item.word.phonetic} ]/",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    item.word.translation,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // Badge indicator
                            Column(horizontalAlignment = Alignment.End) {
                                val memoryBadgeText = when {
                                    item.progress == null -> "未背诵"
                                    item.progress.memoryLevel == 5 -> "L5熟练"
                                    else -> "L${item.progress.memoryLevel}级"
                                }
                                val badgeColor = when {
                                    item.progress == null -> MaterialTheme.colorScheme.surfaceVariant
                                    item.progress.memoryLevel >= 4 -> CorrectGreen.copy(alpha = 0.15f)
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }
                                val textBadgeColor = when {
                                    item.progress == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    item.progress.memoryLevel >= 4 -> CorrectGreen
                                    else -> MaterialTheme.colorScheme.primary
                                }

                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(memoryBadgeText, color = textBadgeColor, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MistakesBookScreen(viewModel: QwertyViewModel) {
    val mistakes by viewModel.mistakeWords.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("mistakes_book_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.08f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Mistakes",
                    tint = ErrorRed,
                    modifier = Modifier.size(32.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("高频错词强化中心", fontWeight = FontWeight.Bold, color = ErrorRed)
                    Text("收录了您在键盘拼写或测试中答错的单词，排序越靠前表示答错次数越多。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (mistakes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无答错单词记录。太棒了，请继续保持！💯",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("错词列表 (${mistakes.size} 词)", fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        viewModel.startPracticeSession(GameMode.TYPING, usePersonalizedPlan = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("🎯 一键强化练习错词", fontWeight = FontWeight.Bold)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mistakes) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.word.word,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed
                                )
                                Text(
                                    item.word.translation,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("答错 ${item.progress?.wrongAttempts ?: 0} 次", color = ErrorRed, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatsHistoryScreen(viewModel: QwertyViewModel) {
    val sessions by viewModel.allSessions.collectAsState()
    val allWords by viewModel.allWordsWithProgress.collectAsState()

    val totalWords = sessions.sumOf { it.wordsTyped }
    val averageWpm = if (sessions.isNotEmpty()) sessions.map { it.wpm }.average().toInt() else 0
    val averageAcc = if (sessions.isNotEmpty()) sessions.map { it.accuracy }.average().toFloat() else 100f

    // Calculations of Memory level distributions
    val level0 = allWords.filter { it.progress == null }.size
    val level1 = allWords.filter { it.progress != null && it.progress.memoryLevel == 1 }.size
    val level2 = allWords.filter { it.progress != null && it.progress.memoryLevel == 2 }.size
    val level3 = allWords.filter { it.progress != null && it.progress.memoryLevel == 3 }.size
    val level4 = allWords.filter { it.progress != null && it.progress.memoryLevel == 4 }.size
    val level5 = allWords.filter { it.progress != null && it.progress.memoryLevel == 5 }.size

    val maxLevelCount = listOf(level1, level2, level3, level4, level5).maxOrNull()?.coerceAtLeast(1) ?: 1

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("stats_history_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "全周期记忆阶层占比",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Custom Composable dynamic bar chart
                    val levels = listOf(
                        Pair("L1级 (初识)", level1),
                        Pair("L2级 (了解)", level2),
                        Pair("L3级 (稳固)", level3),
                        Pair("L4级 (熟练)", level4),
                        Pair("L5级 (大师)", level5)
                    )

                    levels.forEach { (label, count) ->
                        val ratio = count.toFloat() / maxLevelCount
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, style = MaterialTheme.typography.bodySmall)
                                Text("$count 词", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "打字学习效能统计",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$totalWords", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("累计练习词数", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$averageWpm", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("平均打字 WPM", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(String.format("%.0f%%", averageAcc), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CorrectGreen)
                        Text("平均正确率", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("历史练习日志记录", fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { viewModel.clearAllHistory() },
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed),
                    modifier = Modifier.testTag("clear_history")
                ) {
                    Text("清空历史数据")
                }
            }
        }

        if (sessions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("尚无历史通关日志。马上去练习一组单词吧！🚀", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(sessions) { session ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "词库: ${session.category}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "累计挑战 ${session.wordsTyped} 个单词",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${session.wpm} WPM",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                String.format("正确率 %.1f%%", session.accuracy),
                                style = MaterialTheme.typography.labelSmall.copy(color = CorrectGreen, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsScreen(viewModel: QwertyViewModel) {
    val category by viewModel.selectedCategory.collectAsState()
    val dailyNew by viewModel.dailyNewWordsTarget.collectAsState()
    val dailyReview by viewModel.dailyReviewTarget.collectAsState()
    val sessionSize by viewModel.wordsPerSession.collectAsState()
    val showPhoneticBefore by viewModel.showPhoneticBeforeAnswer.collectAsState()
    val isSound by viewModel.isSoundEnabled.collectAsState()
    val isTransVisible by viewModel.isTranslationVisible.collectAsState()
    val isDark by viewModel.isDarkTheme.collectAsState()
    val autoPlay by viewModel.autoPlaySpeech.collectAsState()
    val accent by viewModel.pronunciationAccent.collectAsState()
    val speed by viewModel.pronunciationSpeed.collectAsState()

    var backupText by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var showImportSuccess by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("核心词库设定", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前词书:", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CET-4", "CET-6", "IELTS", "TOEFL", "Coder").forEach { cat ->
                            val isSelected = category == cat
                            SuggestionChip(
                                onClick = { viewModel.selectCategory(cat) },
                                label = { Text(cat) },
                                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            )
                        }
                    }
                }
            }
        }

        item {
            Text("播放与发音设置", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用按键声效")
                        Switch(
                            checked = isSound,
                            onCheckedChange = {
                                viewModel.updateSettings(it, isTransVisible, accent, speed, autoPlay, showPhoneticBefore, isDark, sessionSize)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("单词自动原声播放")
                        Switch(
                            checked = autoPlay,
                            onCheckedChange = {
                                viewModel.updateSettings(isSound, isTransVisible, accent, speed, it, showPhoneticBefore, isDark, sessionSize)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("发音地区偏好 (US / UK)")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("US", "UK").forEach { acc ->
                                val isSelected = accent == acc
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            viewModel.updateSettings(isSound, isTransVisible, acc, speed, autoPlay, showPhoneticBefore, isDark, sessionSize)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(acc, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Column {
                        Text("发音播放速度: ${String.format("%.1f", speed)}x")
                        Slider(
                            value = speed,
                            onValueChange = {
                                viewModel.updateSettings(isSound, isTransVisible, accent, it, autoPlay, showPhoneticBefore, isDark, sessionSize)
                            },
                            valueRange = 0.5f..2.0f
                        )
                    }
                }
            }
        }

        item {
            Text("背诵策略与样式", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("答题前显示音标助记")
                            Text("默认关闭。开启后将助记发音，但会降低盲打难度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = showPhoneticBefore,
                            onCheckedChange = {
                                viewModel.updateSettings(isSound, isTransVisible, accent, speed, autoPlay, it, isDark, sessionSize)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("护眼暗黑主题模式")
                        Switch(
                            checked = isDark,
                            onCheckedChange = {
                                viewModel.updateSettings(isSound, isTransVisible, accent, speed, autoPlay, showPhoneticBefore, it, sessionSize)
                            }
                        )
                    }
                }
            }
        }

        item {
            Text("备份与数据恢复", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { backupText = viewModel.exportBackupJson() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("生成云端备份 JSON 密匙")
                    }

                    if (backupText.isNotEmpty()) {
                        OutlinedTextField(
                            value = backupText,
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            label = { Text("复制此备份密文") }
                        )
                    }

                    Divider()

                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("在此粘贴生成的备份 JSON 密文...") },
                        label = { Text("恢复数据密文") }
                    )

                    Button(
                        onClick = {
                            val success = viewModel.importBackupJson(importText)
                            showImportSuccess = success
                            if (success) importText = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen)
                    ) {
                        Text("一键恢复数据 💾")
                    }

                    if (showImportSuccess) {
                        Text("恢复备份成功！学情及个性化设置已同步。", color = CorrectGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun BottomNavBar(viewModel: QwertyViewModel) {
    val currentScreen by viewModel.screenState.collectAsState()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()
    
    if (!onboardingCompleted || currentScreen == ScreenState.ONBOARDING || currentScreen == ScreenState.TYPING_ARENA) {
        return
    }

    NavigationBar(
        modifier = Modifier.fillMaxWidth().testTag("bottom_nav_bar"),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        NavigationBarItem(
            selected = currentScreen == ScreenState.DASHBOARD || currentScreen == ScreenState.PRACTICE_PREVIEW,
            onClick = { viewModel.setScreen(ScreenState.DASHBOARD) },
            modifier = Modifier.testTag("nav_dashboard"),
            icon = { Icon(imageVector = Icons.Default.Home, contentDescription = "Home Dashboard") },
            label = { Text("控制台") }
        )

        NavigationBarItem(
            selected = currentScreen == ScreenState.WORD_BOOK_EXPLORER,
            onClick = { viewModel.setScreen(ScreenState.WORD_BOOK_EXPLORER) },
            modifier = Modifier.testTag("nav_explorer"),
            icon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Word Book Explorer") },
            label = { Text("词书浏览") }
        )

        NavigationBarItem(
            selected = currentScreen == ScreenState.MISTAKES_BOOK,
            onClick = { viewModel.setScreen(ScreenState.MISTAKES_BOOK) },
            modifier = Modifier.testTag("nav_mistakes"),
            icon = { Icon(imageVector = Icons.Default.Warning, contentDescription = "Mistakes Hub") },
            label = { Text("错词本") }
        )

        NavigationBarItem(
            selected = currentScreen == ScreenState.STATS_HISTORY,
            onClick = { viewModel.setScreen(ScreenState.STATS_HISTORY) },
            modifier = Modifier.testTag("nav_stats"),
            icon = { Icon(imageVector = Icons.Default.Info, contentDescription = "Statistics Logs") },
            label = { Text("学情分析") }
        )

        NavigationBarItem(
            selected = currentScreen == ScreenState.SETTINGS,
            onClick = { viewModel.setScreen(ScreenState.SETTINGS) },
            modifier = Modifier.testTag("nav_settings"),
            icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings Page") },
            label = { Text("设置") }
        )
    }
}
