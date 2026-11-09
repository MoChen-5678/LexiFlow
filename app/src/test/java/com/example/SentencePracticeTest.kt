package com.example

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.material3.Surface
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SentencePracticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sentenceComesBeforeMeaningAndAnswerStaysHiddenUntilSubmission() {
        var advanced = 0
        compose.setContent {
            var typed by remember { mutableStateOf("") }
            var reveal by remember { mutableStateOf(false) }
            MyApplicationTheme { Surface { SentenceQuestion("Please reserve a seat before Friday.", "预订，保留", "reserve", "请在周五前预订座位。",
                1, 10, typed, reveal, { typed = it }, { reveal = true }, { reveal = true }, {},
                { advanced++ }, false) } }
        }
        compose.onRoot().captureRoboImage("build/reports/sentence-before-answer.png")
        compose.onNodeWithTag("gap_sentence").assertTextEquals("Please ________ a seat before Friday.")
        compose.onNodeWithText("reserve").assertDoesNotExist()
        val sentence = compose.onNodeWithTag("gap_sentence").fetchSemanticsNode().boundsInRoot
        val meaning = compose.onNodeWithTag("word_meaning").fetchSemanticsNode().boundsInRoot
        assertTrue(sentence.bottom < meaning.top)
        compose.onNodeWithTag("sentence_input").performTextInput("reserve")
        compose.onNodeWithTag("sentence_submit").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("答对了").assertExists()
        compose.onNodeWithTag("sentence_next").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, advanced)
    }

    @Test fun englishSpeechPreservesNumericTextAndExcludesChineseDirections() {
        val text = englishSpeech("请听录音，回答 3 题。\nThe fee is 24.95 pounds. Meet at 3:30.")
        assertEquals("The fee is 24.95 pounds. Meet at 3:30.", text)
        assertTrue(speechChunks("This is a long reading passage. ".repeat(120)).all { it.length <= 650 })
        assertEquals("Use ________ carefully.", sentenceGap("Use C++ carefully.", "C++"))
    }
}
