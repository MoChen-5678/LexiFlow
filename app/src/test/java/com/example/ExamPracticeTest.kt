package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
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

    @Test fun recalledSpeakingHasAttributionAndNoAutomaticGrade() {
        val question = org.json.JSONObject().apply {
            put("exam", "ielts"); put("kind", "speaking"); put("answer", ""); put("choices", org.json.JSONArray())
            put("speech", "Where do you study?"); put("prompt", "Where do you study?")
            put("label", "考生回忆 · 2025-06 · UK"); put("source", "https://example.org")
        }
        val keyFile = java.io.File(compose.activity.noBackupFilesDir, "study-key")
        keyFile.writeText("test-only")
        try {
            compose.setContent {
                ExamPracticeScreen("IELTS", {}, {}, load = { query, key ->
                    if (query.origin == "past" && key == "test-only") org.json.JSONArray().put(question).toString() else "[]"
                })
            }
            compose.onNodeWithText("考生回忆").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5000) { compose.onAllNodesWithText("完成本题").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("考生回忆 · 2025-06 · UK").assertExists()
            compose.onNodeWithText("提交答案").assertDoesNotExist()
            compose.onNodeWithText("完成本题").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("已练习 1 道口语题").assertExists()
        } finally {
            keyFile.delete()
        }
    }

    @Test fun offlineListeningCanBePlayedAnsweredAndGraded() {
        var spoken = ""
        compose.setContent {
            ExamPracticeScreen("IELTS", { spoken = it }, {}, load = { _, _ -> error("offline") })
        }
        compose.onNodeWithText("听力").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10000) { compose.onAllNodesWithText("朗读").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("当前使用内置练习。").assertExists()
        compose.onNodeWithText("朗读").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(true, spoken.contains("Thursday")) }
        compose.onNodeWithText("你的答案").performScrollTo().performTextInput(" thursday ")
        compose.onNodeWithText("提交答案").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        org.junit.Assert.assertTrue(compose.onRoot().printToString(), compose.onAllNodesWithText("答对了").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("查看成绩").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5000) { compose.onAllNodesWithText("再练一组").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertTrue(compose.onRoot().printToString(), compose.onAllNodesWithText("本组答对 1 / 1 题").fetchSemanticsNodes().isNotEmpty())
    }
}
