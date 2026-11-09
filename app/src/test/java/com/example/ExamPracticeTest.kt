package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExamPracticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun offlineListeningCanBePlayedAnsweredAndGraded() {
        var spoken = ""
        compose.setContent {
            ExamPracticeScreen("IELTS", { spoken = it }, {}, load = { _, _ -> error("offline") })
        }
        compose.onNodeWithText("听力").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("朗读").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("当前使用内置练习。").assertExists()
        compose.onNodeWithText("朗读").performClick()
        compose.runOnIdle { assertEquals(true, spoken.contains("Thursday")) }
        compose.onNodeWithText("你的答案").performTextInput(" thursday ")
        compose.onNodeWithText("提交答案").performClick()
        compose.onNodeWithText("答对了").assertExists()
        compose.onNodeWithText("查看成绩").performClick()
        compose.onNodeWithText("本组答对 1 / 1 题").assertExists()
    }
}
