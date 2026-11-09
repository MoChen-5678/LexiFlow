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

    @Test fun offlineIeltsSetCanBePlayedAndSubmitted() {
        var spoken = ""
        compose.setContent { IeltsSetBrowser({ spoken = it }, load = { error("offline") }) }
        compose.onNodeWithText("听力").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("图书馆入会咨询").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("ielts_questions").performScrollToNode(hasTestTag("ielts_play"))
        compose.onNodeWithTag("ielts_play").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            org.junit.Assert.assertTrue(spoken.contains("twenty-four pounds ninety-five"))
            org.junit.Assert.assertFalse(spoken.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        }
        compose.onNodeWithTag("ielts_questions").performScrollToNode(hasTestTag("ielts_input_1"))
        compose.onNodeWithTag("ielts_input_1").performTextInput("Bell")
        compose.onNodeWithTag("ielts_questions").performScrollToNode(hasTestTag("ielts_submit"))
        compose.onNodeWithTag("ielts_submit").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("ielts_questions").performScrollToNode(hasText("本组答对 1 / 10 题"))
        compose.onNodeWithText("本组答对 1 / 10 题").assertExists()
    }
}
