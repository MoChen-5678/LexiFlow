package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.example.data.*
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun courseNavigationSettingsAndSentenceLearningWorkTogether() {
        val application = compose.activity.application as Application
        application.getSharedPreferences("lexiflow_preferences", 0).edit().clear()
            .putBoolean("onboarding_completed", true).apply()
        val database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        runBlocking { database.wordDao().insertWords(DefaultWords.list) }
        val repository = WordRepository(database.wordDao())
        val vm = QwertyViewModel(application, repository)
        val store = androidx.lifecycle.ViewModelStore().apply { put("study", vm) }
        java.io.File("build/reports/ui").mkdirs()
        try {
            compose.setContent {
                val dark by vm.isDarkTheme.collectAsState()
                MyApplicationTheme(darkTheme = dark) { MainScreen(vm) }
            }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/reports/ui/home-initial.png")
            compose.waitUntil(10000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                vm.allWordsWithProgress.value.size > 20
            }
            compose.onNodeWithTag("start_personalized_learning").assertExists()
            compose.onRoot().captureRoboImage("build/reports/ui/home.png")
            compose.onNodeWithText("调整目标").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("每日计划").assertExists()
            compose.onNodeWithTag("nav_dashboard").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("nav_explorer").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil { compose.onAllNodesWithTag("course_list").fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().captureRoboImage("build/reports/ui/courses.png")
            compose.onNodeWithTag("course_list").performScrollToNode(hasTestTag("course_IELTS"))
            compose.onNodeWithTag("course_IELTS").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil { vm.selectedCategory.value == "IELTS" }
            compose.onNodeWithTag("explorer_search_bar").performTextInput(DefaultWords.list.first { it.category == "IELTS" }.word)
            compose.waitUntil(5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                vm.filteredExplorerWords.value.isNotEmpty()
            }
            compose.onRoot().captureRoboImage("build/reports/ui/vocabulary.png")
            compose.onNodeWithTag("nav_stats").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil { compose.onAllNodesWithTag("stats_history_screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().captureRoboImage("build/reports/ui/progress.png")
            compose.onNodeWithTag("nav_settings").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("settings_screen").performScrollToNode(hasText("男声"))
            compose.onNodeWithText("男声").performSemanticsAction(SemanticsActions.OnClick)
            compose.runOnIdle { assertEquals("male", vm.pronunciationGender.value) }
            compose.onNodeWithTag("settings_screen").performScrollToNode(hasText("深色外观"))
            compose.onNodeWithText("深色外观").performSemanticsAction(SemanticsActions.OnClick)
            compose.runOnIdle { assertTrue(vm.isDarkTheme.value) }
            compose.onRoot().captureRoboImage("build/reports/ui/settings-dark.png")
            compose.runOnIdle {
                vm.updateSettings(true, true, "US", 1f, false, false, false, 20)
                vm.setScreen(ScreenState.DASHBOARD)
            }
            compose.runOnIdle { vm.setScreen(ScreenState.EXAM_PRACTICE) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("城市河流修复").fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().captureRoboImage("build/reports/ui/exams.png")
            compose.onNodeWithText("城市河流修复").performSemanticsAction(SemanticsActions.OnClick)
            compose.onRoot().captureRoboImage("build/reports/ui/reading.png")
            compose.runOnIdle { vm.setScreen(ScreenState.DASHBOARD) }
            compose.onNodeWithTag("nav_learn").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                compose.onAllNodesWithTag("sentence_input", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bottom_nav_bar").assertDoesNotExist()
            compose.onRoot().captureRoboImage("build/reports/ui/learning.png")
            val expected = vm.currentWord!!.word
            compose.onNodeWithTag("sentence_input", useUnmergedTree = true).performTextInput(expected)
            compose.onNodeWithTag("sentence_submit").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("答对了").assertExists()
            compose.onNodeWithTag("sentence_next").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                vm.typingState.value.currentIndex == 1
            }
            compose.onRoot().captureRoboImage("build/reports/ui/learning-next.png")
            val wrongWord = vm.currentWord!!.id
            runBlocking { repository.saveProgress(wrongWord, 1) }
            compose.runOnIdle { vm.setScreen(ScreenState.MISTAKES_BOOK) }
            compose.waitUntil(5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                vm.mistakeWords.value.any { it.word.id == wrongWord }
            }
            compose.onRoot().captureRoboImage("build/reports/ui/review.png")
            compose.onNodeWithText("开始复习").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                vm.screenState.value == ScreenState.TYPING_ARENA
            }
            assertTrue(vm.typingState.value.currentWordList.all { (it.progress?.wrongAttempts ?: 0) > 0 })
        } finally {
            store.clear()
            database.close()
        }
    }
}
