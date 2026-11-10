@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
package com.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.*
import com.example.ui.*
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {
    private lateinit var speech: SpeechPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        speech = SpeechPlayer(this)
        
        enableEdgeToEdge()
        setContent {
            val database = AppDatabase.getDatabase(applicationContext)
            val repository = WordRepository(database.wordDao())
            val factory = QwertyViewModelFactory(applicationContext as Application, repository)
            val viewModel: QwertyViewModel = viewModel(factory = factory)
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, viewModel) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_START) viewModel.refreshVocabulary()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val isDark by viewModel.isDarkTheme.collectAsState()
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }

            MyApplicationTheme(darkTheme = isDark) {
                // TTS trigger collector
                LaunchedEffect(viewModel.speakEvent) {
                    viewModel.speakEvent.collect { word ->
                        speech.play(word, viewModel.pronunciationAccent.value, viewModel.pronunciationSpeed.value, viewModel.pronunciationGender.value)
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

    override fun onStop() {
        speech.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speech.close()
        super.onDestroy()
    }
}

// Global text sentence masker
fun maskSentence(sentence: String, target: String): String {
    if (sentence.isEmpty() || target.isEmpty()) return sentence
    val regex = "\\b${Regex.escape(target)}(?:ing|ed|s|es)?\\b".toRegex(RegexOption.IGNORE_CASE)
    return sentence.replace(regex, "_______")
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainScreen(viewModel: QwertyViewModel) {
    val screenState by viewModel.screenState.collectAsState()
    androidx.activity.compose.BackHandler(enabled = screenState != ScreenState.DASHBOARD && screenState != ScreenState.ONBOARDING) {
        viewModel.speakWord("")
        viewModel.setScreen(ScreenState.DASHBOARD)
    }
    Scaffold(
        topBar = { AppHeader(viewModel) },
        bottomBar = { BottomNavBar(viewModel) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            AnimatedContent(targetState = screenState, transitionSpec = { fadeIn() togetherWith fadeOut() }) { target ->
                when (target) {
                    ScreenState.ONBOARDING -> OnboardingScreen(viewModel)
                    ScreenState.DASHBOARD -> DashboardScreen(viewModel)
                    ScreenState.PRACTICE_PREVIEW -> PracticePreviewScreen(viewModel)
                    ScreenState.EXAM_PRACTICE -> ExamPracticeScreen(viewModel.selectedCategory.value, viewModel::speakWord,
                        exit = { viewModel.setScreen(ScreenState.DASHBOARD) })
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

@Composable
fun AppHeader(viewModel: QwertyViewModel) {
    LexiHeader(viewModel)
}

@Composable
fun OnboardingScreen(viewModel: QwertyViewModel) {
    WelcomeScreen(viewModel)
}

@Composable
fun DashboardScreen(viewModel: QwertyViewModel) {
    HomeScreen(viewModel)
}

@Composable
fun PracticePreviewScreen(viewModel: QwertyViewModel) {
    PracticeSetupScreen(viewModel)
}

@Composable
fun TypingArenaScreen(viewModel: QwertyViewModel) {
    val mode by viewModel.activeMode.collectAsState()
    if (mode == GameMode.TYPING || mode == GameMode.CLOZE) SentencePracticeScreen(viewModel)
    else AlternativePracticeScreen(viewModel)
}

@Composable
fun SessionCompletedScreen(viewModel: QwertyViewModel, state: TypingArenaState) {
    SessionSummaryScreen(viewModel, state)
}

@Composable
fun WordBookExplorerScreen(viewModel: QwertyViewModel) {
    CourseLibraryScreen(viewModel)
}

@Composable
fun MistakesBookScreen(viewModel: QwertyViewModel) {
    ReviewScreen(viewModel)
}

@Composable
fun StatsHistoryScreen(viewModel: QwertyViewModel) {
    ProgressScreen(viewModel)
}

@Composable
fun SettingsScreen(viewModel: QwertyViewModel) {
    PreferencesScreen(viewModel)
}

@Composable
fun BottomNavBar(viewModel: QwertyViewModel) {
    LexiNavigation(viewModel)
}
