package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

enum class ScreenState {
    ONBOARDING,
    DASHBOARD,
    PRACTICE_PREVIEW,
    TYPING_ARENA,
    MISTAKES_BOOK,
    WORD_BOOK_EXPLORER,
    STATS_HISTORY,
    SETTINGS
}

enum class GameMode {
    TYPING,      // Standard character-by-character spelling
    CHOICE,      // Multiple Choice 4-option translation
    CLOZE,       // Fill-in-the-blank in context sentence
    DICTATION,   // Standard listen & spell writing test
    MATCHING     // Connect words with their Chinese translations
}

// State representing the active practice session
data class TypingArenaState(
    val currentWordList: List<WordWithProgress> = emptyList(),
    val currentIndex: Int = 0,
    val typedText: String = "",
    val totalKeystrokes: Int = 0,
    val errorKeystrokes: Int = 0,
    val startTime: Long = 0,
    val isCompleted: Boolean = false,
    val lastErrorChar: Char? = null,
    val isErrorFlashing: Boolean = false,
    val liveWpm: Int = 0,
    val liveAccuracy: Float = 100f,
    
    // Question phase controls
    val isAnswerRevealed: Boolean = false, // True once user submits or clicks show answer
    val chosenQuality: Int? = null,        // Quality selected (Forgot = 1, Hard = 3, Good = 4, Easy = 5)
    
    // Multiple Choice options
    val currentChoices: List<String> = emptyList(),
    val selectedChoiceIndex: Int? = null,
    val isChoiceCorrect: Boolean? = null,
    
    // Matching Mode State
    val matchingLeft: List<String> = emptyList(),          // English words
    val matchingRight: List<String> = emptyList(),         // Shuffled translations
    val selectedLeft: String? = null,
    val selectedRight: String? = null,
    val matchedPairs: Set<Pair<String, String>> = emptySet(), // Solved matches
    val wrongMatches: Set<Pair<String, String>> = emptySet()  // Failed attempts flashing red
)

class QwertyViewModel(
    application: Application,
    private val repository: WordRepository
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("lexiflow_preferences", Context.MODE_PRIVATE)

    // Current app screen
    private val _screenState = MutableStateFlow(ScreenState.DASHBOARD)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    // Game Mode selection
    private val _activeMode = MutableStateFlow(GameMode.TYPING)
    val activeMode: StateFlow<GameMode> = _activeMode.asStateFlow()

    // Active practice state
    private val _typingState = MutableStateFlow(TypingArenaState())
    val typingState: StateFlow<TypingArenaState> = _typingState.asStateFlow()

    // Word Category selection
    val categories = listOf("CET-4", "CET-6", "IELTS", "TOEFL", "Coder")
    
    private val _selectedCategory = MutableStateFlow(prefs.getString("selected_category", "CET-4") ?: "CET-4")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // Preferences configuration
    val dailyNewWordsTarget = MutableStateFlow(prefs.getInt("daily_new_words", 10))
    val dailyReviewTarget = MutableStateFlow(prefs.getInt("daily_reviews", 15))
    val wordsPerSession = MutableStateFlow(prefs.getInt("words_per_session", 20)) 
    
    // Settings configuration
    val showPhoneticBeforeAnswer = MutableStateFlow(prefs.getBoolean("show_phonetic_before_answer", false))
    val isSoundEnabled = MutableStateFlow(prefs.getBoolean("is_sound_enabled", true))
    val isTranslationVisible = MutableStateFlow(prefs.getBoolean("is_translation_visible", true))
    val isDarkTheme = MutableStateFlow(prefs.getBoolean("is_dark_theme", false))
    val autoPlaySpeech = MutableStateFlow(prefs.getBoolean("auto_play_speech", true))
    val pronunciationAccent = MutableStateFlow(prefs.getString("pronunciation_accent", "US") ?: "US") // US or UK
    val pronunciationSpeed = MutableStateFlow(prefs.getFloat("pronunciation_speed", 1.0f))

    // Onboarding status
    val onboardingCompleted = MutableStateFlow(prefs.getBoolean("onboarding_completed", false))

    // Text-To-Speech Pronunciation stream
    private val _speakEvent = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val speakEvent: SharedFlow<String> = _speakEvent.asSharedFlow()

    // DB Observables
    val allSessions: StateFlow<List<TypingSession>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Enriches a word structure dynamically to avoid empty strings
    private fun enrichWordWithProgress(item: WordWithProgress): WordWithProgress {
        val word = item.word
        if (word.exampleSentence.isNotEmpty()) return item

        val defaultMatch = DefaultWords.list.find { it.word.equals(word.word, ignoreCase = true) }
        val enrichedWord = if (defaultMatch != null && defaultMatch.exampleSentence.isNotEmpty()) {
            word.copy(
                exampleSentence = defaultMatch.exampleSentence,
                exampleTranslation = defaultMatch.exampleTranslation,
                exampleSentence2 = defaultMatch.exampleSentence2,
                exampleTranslation2 = defaultMatch.exampleTranslation2,
                exampleSentence3 = defaultMatch.exampleSentence3,
                exampleTranslation3 = defaultMatch.exampleTranslation3
            )
        } else {
            val templates = listOf(
                Pair("We need to understand the concept of [word] thoroughly.", "我们需要彻底理解 [word] 的概念。"),
                Pair("Can you help me translate the word [word] into Chinese?", "你能帮我把 [word] 这个单词翻译成中文吗？"),
                Pair("He made a beautiful sentence with [word] during the English class.", "他在英语课上用 [word] 造了一个漂亮的句子。"),
                Pair("Learning the term [word] is helpful for your academic study.", "学习 [word] 这个术语对你的学术研究很有帮助。"),
                Pair("Please write down the word [word] on your notebook.", "请在你的笔记本上写下 [word] 这个单词。"),
                Pair("The professor discussed the definition of [word] yesterday.", "教授昨天讨论了 [word] 的定义。"),
                Pair("She managed to remember the spelling of [word] easily.", "她很轻松地记住了 [word] 的拼写。"),
                Pair("This article explains how to use [word] in daily conversation.", "这篇文章解释了如何在日常对话中使用 [word] 这个词。")
            )
            val index = Math.abs(word.word.hashCode()) % templates.size
            val selectedTemplate = templates[index]
            val sentence = selectedTemplate.first.replace("[word]", word.word)
            val translation = selectedTemplate.second.replace("[word]", word.word)
            word.copy(
                exampleSentence = sentence,
                exampleTranslation = translation
            )
        }
        return item.copy(word = enrichedWord)
    }

    val allWordsWithProgress: StateFlow<List<WordWithProgress>> = repository.allWordsWithProgress
        .map { list -> list.map { enrichWordWithProgress(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered lists for Word Explorer Screen
    val searchQuery = MutableStateFlow("")
    val selectedMemoryLevelFilter = MutableStateFlow("ALL") // "ALL", "NEW" (0), "1", "2", "3", "4", "5", "DUE"
    
    val filteredExplorerWords = combine(allWordsWithProgress, searchQuery, selectedMemoryLevelFilter, _selectedCategory) { list, query, level, cat ->
        val now = System.currentTimeMillis()
        list.filter { item ->
            val matchesCategory = if (cat.equals("all", ignoreCase = true)) {
                true
            } else {
                val normalizedFilter = when (cat.uppercase()) {
                    "CET-4" -> "cet4"
                    "CET-6" -> "cet6"
                    "IELTS" -> "ielts"
                    "TOEFL" -> "toefl"
                    "CODER" -> "coder"
                    else -> cat.lowercase()
                }
                item.word.category.equals(cat, ignoreCase = true) ||
                item.word.bookIds.split(",").map { it.trim().lowercase() }.contains(normalizedFilter)
            }
            val matchesQuery = query.isEmpty() || item.word.word.contains(query, ignoreCase = true) || item.word.translation.contains(query, ignoreCase = true)
            
            val matchesLevel = when (level) {
                "ALL" -> true
                "NEW" -> item.progress == null
                "DUE" -> item.progress != null && item.progress.nextReviewTime <= now
                "1" -> item.progress?.memoryLevel == 1
                "2" -> item.progress?.memoryLevel == 2
                "3" -> item.progress?.memoryLevel == 3
                "4" -> item.progress?.memoryLevel == 4
                "5" -> item.progress?.memoryLevel == 5
                else -> true
            }
            matchesCategory && matchesQuery && matchesLevel
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Historical mistakes (wrong count > 0)
    val mistakeWords = allWordsWithProgress.map { list ->
        list.filter { it.progress != null && it.progress.wrongAttempts > 0 }
            .sortedByDescending { it.progress?.wrongAttempts ?: 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Computed smart list values for the Personalized Study Planner
    val smartTodayPlan = allWordsWithProgress.map { list ->
        val now = System.currentTimeMillis()
        
        // Split reviews into due incorrect (error history / low memory level) and due correct
        val dueReviews = list.filter { 
            it.progress != null && it.progress.nextReviewTime <= now
        }
        
        val dueWrongReviews = dueReviews.filter { 
            it.progress!!.consecutiveCorrect == 0 || it.progress!!.memoryLevel == 1
        }.sortedBy { it.progress!!.nextReviewTime }

        val dueCorrectReviews = dueReviews.filter { 
            it.progress!!.consecutiveCorrect > 0 && it.progress!!.memoryLevel > 1
        }.sortedBy { it.progress!!.nextReviewTime }

        // Unlearned words
        val unlearnedNew = list.filter { 
            it.progress == null
        }

        Triple(unlearnedNew, dueWrongReviews, dueCorrectReviews)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(emptyList(), emptyList(), emptyList()))

    // Active session queue statistics
    val sessionNewCount = MutableStateFlow(0)
    val sessionWrongCount = MutableStateFlow(0)
    val sessionCorrectCount = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            repository.ensureWordsPopulated(getApplication())
        }
        // Redirect to onboarding if not completed
        if (!onboardingCompleted.value) {
            _screenState.value = ScreenState.ONBOARDING
        }
    }

    // Navigation and setters
    fun setScreen(state: ScreenState) {
        _screenState.value = state
    }

    fun setMode(mode: GameMode) {
        _activeMode.value = mode
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        prefs.edit().putString("selected_category", category).apply()
    }

    fun saveOnboardingSettings(category: String, dailyNew: Int, dailyReview: Int, wordsSession: Int) {
        viewModelScope.launch {
            _selectedCategory.value = category
            dailyNewWordsTarget.value = dailyNew
            dailyReviewTarget.value = dailyReview
            wordsPerSession.value = wordsSession
            onboardingCompleted.value = true

            prefs.edit().apply {
                putString("selected_category", category)
                putInt("daily_new_words", dailyNew)
                putInt("daily_reviews", dailyReview)
                putInt("words_per_session", wordsSession)
                putBoolean("onboarding_completed", true)
            }.apply()

            _screenState.value = ScreenState.DASHBOARD
        }
    }

    fun updateSettings(
        sound: Boolean,
        trans: Boolean,
        accent: String,
        speed: Float,
        autoPlay: Boolean,
        phoneticBefore: Boolean,
        dark: Boolean,
        sessionSize: Int
    ) {
        isSoundEnabled.value = sound
        isTranslationVisible.value = trans
        pronunciationAccent.value = accent
        pronunciationSpeed.value = speed
        autoPlaySpeech.value = autoPlay
        showPhoneticBeforeAnswer.value = phoneticBefore
        isDarkTheme.value = dark
        wordsPerSession.value = sessionSize

        prefs.edit().apply {
            putBoolean("is_sound_enabled", sound)
            putBoolean("is_translation_visible", trans)
            putString("pronunciation_accent", accent)
            putFloat("pronunciation_speed", speed)
            putBoolean("auto_play_speech", autoPlay)
            putBoolean("show_phonetic_before_answer", phoneticBefore)
            putBoolean("is_dark_theme", dark)
            putInt("words_per_session", sessionSize)
        }.apply()
    }

    // Build LexiFlow study session queue with strict priority constraints:
    // 1. Due Wrong Reviews (highest priority)
    // 2. Due Correct Reviews
    // 3. Brand New Words (unlearned, up to custom target)
    // 4. Fill to minimum 20 words if total is insufficient
    fun startPracticeSession(mode: GameMode, usePersonalizedPlan: Boolean = false) {
        viewModelScope.launch {
            repository.ensureWordsPopulated(getApplication())
            
            val totalLimit = wordsPerSession.value.coerceAtLeast(20)
            val currentCategoryWords = repository.getWordsWithProgressByCategory(_selectedCategory.value).first()
            val enrichedCategoryWords = currentCategoryWords.map { enrichWordWithProgress(it) }

            val now = System.currentTimeMillis()
            val dueWrong = enrichedCategoryWords.filter { 
                it.progress != null && it.progress.nextReviewTime <= now && (it.progress.consecutiveCorrect == 0 || it.progress.memoryLevel == 1)
            }.shuffled()

            val dueCorrect = enrichedCategoryWords.filter { 
                it.progress != null && it.progress.nextReviewTime <= now && it.progress.consecutiveCorrect > 0 && it.progress.memoryLevel > 1
            }.shuffled()

            val unlearned = enrichedCategoryWords.filter { it.progress == null }.shuffled()

            val queue = mutableListOf<WordWithProgress>()
            var wrongAdded = 0
            var correctAdded = 0
            var newAdded = 0

            if (usePersonalizedPlan) {
                // Priority 1: Due Wrong reviews
                val wrongTake = dueWrong.take(dailyReviewTarget.value / 2)
                queue.addAll(wrongTake)
                wrongAdded = wrongTake.size

                // Priority 2: Due Correct reviews
                val correctTake = dueCorrect.take(dailyReviewTarget.value - wrongAdded)
                queue.addAll(correctTake)
                correctAdded = correctTake.size

                // Priority 3: New words (up to target)
                val newTake = unlearned.take(dailyNewWordsTarget.value)
                queue.addAll(newTake)
                newAdded = newTake.size
            } else {
                // Standard mode: Grab whatever fits best, balancing review and new words
                val wrongTake = dueWrong.take(totalLimit / 3)
                queue.addAll(wrongTake)
                wrongAdded = wrongTake.size

                val correctTake = dueCorrect.take(totalLimit / 3)
                queue.addAll(correctTake)
                correctAdded = correctTake.size

                val newTake = unlearned.take(totalLimit - wrongAdded - correctAdded)
                queue.addAll(newTake)
                newAdded = newTake.size
            }

            // Fallback requirement: Pad to minimum 20 words using other words from current category
            if (queue.size < totalLimit) {
                val addedIds = queue.map { it.word.id }.toSet()
                // Take unlearned first
                val padNew = unlearned.filter { it.word.id !in addedIds }.take(totalLimit - queue.size)
                queue.addAll(padNew)
                newAdded += padNew.size
            }

            if (queue.size < totalLimit) {
                val addedIds = queue.map { it.word.id }.toSet()
                // Take any already learned words that are not due
                val learnedNotDue = enrichedCategoryWords.filter { it.progress != null && it.word.id !in addedIds }.shuffled().take(totalLimit - queue.size)
                queue.addAll(learnedNotDue)
                correctAdded += learnedNotDue.size
            }

            // Final fallback to raw words if still empty
            if (queue.isEmpty()) {
                val fallbackList = enrichedCategoryWords.shuffled().take(totalLimit)
                queue.addAll(fallbackList)
                newAdded = fallbackList.size
            }

            sessionNewCount.value = newAdded
            sessionWrongCount.value = wrongAdded
            sessionCorrectCount.value = correctAdded

            _activeMode.value = mode

            if (mode == GameMode.MATCHING) {
                val subset = queue.take(4)
                val leftWords = subset.map { it.word.word }
                val rightTranslations = subset.map { it.word.translation }.shuffled()

                _typingState.value = TypingArenaState(
                    currentWordList = subset,
                    currentIndex = 0,
                    typedText = "",
                    startTime = System.currentTimeMillis(),
                    isCompleted = false,
                    matchingLeft = leftWords,
                    matchingRight = rightTranslations
                )
            } else {
                _typingState.value = TypingArenaState(
                    currentWordList = queue,
                    currentIndex = 0,
                    typedText = "",
                    startTime = System.currentTimeMillis(),
                    isCompleted = false,
                    isAnswerRevealed = false
                )
                generateChoicesForCurrentIndex()
                if (autoPlaySpeech.value) {
                    triggerSpeakForCurrentWord()
                }
            }

            _screenState.value = ScreenState.TYPING_ARENA
        }
    }

    val currentWord: Word?
        get() {
            val state = _typingState.value
            return if (state.currentIndex in state.currentWordList.indices) {
                state.currentWordList[state.currentIndex].word
            } else null
        }

    val currentProgress: WordProgress?
        get() {
            val state = _typingState.value
            return if (state.currentIndex in state.currentWordList.indices) {
                state.currentWordList[state.currentIndex].progress
            } else null
        }

    // Standard character-by-character spelling input (Typing / Dictation / Cloze)
    fun typeChar(char: Char) {
        val state = _typingState.value
        if (state.isCompleted || state.currentWordList.isEmpty() || state.isAnswerRevealed) return

        val wordData = currentWord ?: return
        val targetWord = wordData.word
        val targetIndex = state.typedText.length

        val updatedTotalKeys = state.totalKeystrokes + 1
        var updatedErrorKeys = state.errorKeystrokes
        var updatedTypedText = state.typedText

        if (targetIndex < targetWord.length) {
            val expectedChar = targetWord[targetIndex].lowercaseChar()
            if (char.lowercaseChar() == expectedChar) {
                updatedTypedText += targetWord[targetIndex]

                if (updatedTypedText.lowercase() == targetWord.lowercase()) {
                    // Correct complete spelling - automatically reveal answer phase so they can grade memory
                    revealSpellAnswer(updatedTypedText, updatedTotalKeys, updatedErrorKeys, true)
                } else {
                    _typingState.value = state.copy(
                        typedText = updatedTypedText,
                        totalKeystrokes = updatedTotalKeys
                    )
                    updateLiveStats()
                }
            } else {
                // Typo occurred
                updatedErrorKeys++
                _typingState.value = state.copy(
                    totalKeystrokes = updatedTotalKeys,
                    errorKeystrokes = updatedErrorKeys,
                    lastErrorChar = char,
                    isErrorFlashing = true
                )
                viewModelScope.launch {
                    delay(200)
                    _typingState.value = _typingState.value.copy(isErrorFlashing = false)
                }
                updateLiveStats()
            }
        }
    }

    // Backspace for typing corrector
    fun backspaceTypedText() {
        val state = _typingState.value
        if (state.isCompleted || state.typedText.isEmpty() || state.isAnswerRevealed) return
        _typingState.value = state.copy(
            typedText = state.typedText.dropLast(1),
            totalKeystrokes = state.totalKeystrokes + 1
        )
        updateLiveStats()
    }

    // Reveal spell answer
    private fun revealSpellAnswer(typed: String, totalKeys: Int, errors: Int, automatic: Boolean) {
        val state = _typingState.value
        _typingState.value = state.copy(
            typedText = typed,
            totalKeystrokes = totalKeys,
            errorKeystrokes = errors,
            isAnswerRevealed = true
        )
        triggerSpeakForCurrentWord()
        updateLiveStats()
    }

    fun revealAnswerStageManually() {
        val state = _typingState.value
        _typingState.value = state.copy(
            isAnswerRevealed = true
        )
        triggerSpeakForCurrentWord()
    }

    // Spaced repetition subjective memory feedback (Forgot, Hard, Good, Easy)
    fun gradeMemoryFeedback(rating: String) {
        val state = _typingState.value
        val wordData = currentWord ?: return
        
        // Quality conversions:
        // Forgot = 1 (fails review)
        // Hard = 3 (passes, stability grows slightly)
        // Good = 4 (passes, standard SM-2)
        // Easy = 5 (passes, swift stability grow)
        val quality = when (rating) {
            "FORGOT" -> 1
            "HARD" -> 3
            "GOOD" -> 4
            "EASY" -> 5
            else -> 4
        }

        viewModelScope.launch {
            repository.saveProgress(wordData.id, quality)

            if (state.currentIndex + 1 >= state.currentWordList.size) {
                finishSession(_typingState.value.copy(chosenQuality = quality))
            } else {
                _typingState.value = _typingState.value.copy(
                    currentIndex = state.currentIndex + 1,
                    typedText = "",
                    isAnswerRevealed = false,
                    chosenQuality = null
                )
                generateChoicesForCurrentIndex()
                if (autoPlaySpeech.value) {
                    triggerSpeakForCurrentWord()
                }
            }
        }
    }

    // Multiple Choice selection submission
    fun selectChoice(choiceIndex: Int) {
        val state = _typingState.value
        val wordData = currentWord ?: return
        val currentChoices = state.currentChoices
        
        if (choiceIndex !in currentChoices.indices || state.selectedChoiceIndex != null) return

        val isCorrect = currentChoices[choiceIndex] == wordData.translation
        val updatedTotal = state.totalKeystrokes + 1
        val updatedErrors = if (isCorrect) state.errorKeystrokes else state.errorKeystrokes + 1

        _typingState.value = state.copy(
            selectedChoiceIndex = choiceIndex,
            isChoiceCorrect = isCorrect,
            totalKeystrokes = updatedTotal,
            errorKeystrokes = updatedErrors,
            isAnswerRevealed = true
        )
        updateLiveStats()

        viewModelScope.launch {
            repository.saveProgress(wordData.id, if (isCorrect) 4 else 1)
            triggerSpeakForCurrentWord()
            delay(1200) // Visual answer display delay

            if (state.currentIndex + 1 >= state.currentWordList.size) {
                finishSession(_typingState.value)
            } else {
                _typingState.value = _typingState.value.copy(
                    currentIndex = state.currentIndex + 1,
                    typedText = "",
                    selectedChoiceIndex = null,
                    isChoiceCorrect = null,
                    isAnswerRevealed = false
                )
                generateChoicesForCurrentIndex()
                if (autoPlaySpeech.value) {
                    triggerSpeakForCurrentWord()
                }
            }
        }
    }

    // Matching connections
    fun selectMatchingItem(text: String, isEnglish: Boolean) {
        val state = _typingState.value
        if (state.isCompleted) return

        var newSelectedLeft = state.selectedLeft
        var newSelectedRight = state.selectedRight

        if (isEnglish) {
            newSelectedLeft = text
        } else {
            newSelectedRight = text
        }

        _typingState.value = state.copy(
            selectedLeft = newSelectedLeft,
            selectedRight = newSelectedRight
        )

        // Evaluate matchmaking
        if (newSelectedLeft != null && newSelectedRight != null) {
            val matchingWord = state.currentWordList.find { it.word.word == newSelectedLeft }
            val isMatch = matchingWord?.word?.translation == newSelectedRight

            viewModelScope.launch {
                if (isMatch) {
                    val pair = Pair(newSelectedLeft, newSelectedRight)
                    val updatedPairs = state.matchedPairs + pair
                    
                    _typingState.value = _typingState.value.copy(
                        matchedPairs = updatedPairs,
                        selectedLeft = null,
                        selectedRight = null
                    )

                    matchingWord?.let { repository.saveProgress(it.word.id, 4) }

                    if (updatedPairs.size >= state.currentWordList.size) {
                        finishSession(_typingState.value)
                    }
                } else {
                    val errorPair = Pair(newSelectedLeft, newSelectedRight)
                    _typingState.value = _typingState.value.copy(
                        wrongMatches = setOf(errorPair),
                        selectedLeft = null,
                        selectedRight = null,
                        errorKeystrokes = state.errorKeystrokes + 1
                    )
                    
                    matchingWord?.let { repository.saveProgress(it.word.id, 1) }

                    delay(600) // Flash red failure effect
                    _typingState.value = _typingState.value.copy(
                        wrongMatches = emptySet()
                    )
                }
            }
        }
    }

    private fun generateChoicesForCurrentIndex() {
        val state = _typingState.value
        val wordData = currentWord ?: return
        viewModelScope.launch {
            val allWords = repository.allWordsWithProgress.first().map { it.word }
            val distractors = allWords
                .filter { it.translation != wordData.translation }
                .shuffled()
                .take(3)
                .map { it.translation }

            val mergedChoices = (distractors + wordData.translation).shuffled()
            _typingState.value = _typingState.value.copy(
                currentChoices = mergedChoices
            )
        }
    }

    private fun updateLiveStats() {
        val state = _typingState.value
        if (state.startTime == 0L) return
        val elapsedSeconds = (System.currentTimeMillis() - state.startTime) / 1000f
        val wpm = if (elapsedSeconds > 1f) {
            ((state.totalKeystrokes / 5f) / (elapsedSeconds / 60f)).toInt()
        } else 0

        val accuracy = if (state.totalKeystrokes > 0) {
            ((state.totalKeystrokes - state.errorKeystrokes).toFloat() / state.totalKeystrokes) * 100f
        } else 100f

        _typingState.value = state.copy(
            liveWpm = wpm.coerceAtLeast(0),
            liveAccuracy = accuracy.coerceIn(0f, 100f)
        )
    }

    private fun finishSession(finalState: TypingArenaState) {
        val elapsedSeconds = (System.currentTimeMillis() - finalState.startTime) / 1000f
        val wpm = if (elapsedSeconds > 1f) {
            ((finalState.totalKeystrokes / 5f) / (elapsedSeconds / 60f)).toInt()
        } else 0

        val accuracy = if (finalState.totalKeystrokes > 0) {
            ((finalState.totalKeystrokes - finalState.errorKeystrokes).toFloat() / finalState.totalKeystrokes) * 100f
        } else 100f

        _typingState.value = finalState.copy(
            isCompleted = true,
            liveWpm = wpm.coerceAtLeast(0),
            liveAccuracy = accuracy.coerceIn(0f, 100f)
        )

        viewModelScope.launch {
            val session = TypingSession(
                category = _selectedCategory.value,
                wordsTyped = finalState.currentWordList.size,
                wpm = wpm.coerceAtLeast(0),
                accuracy = accuracy.coerceIn(0f, 100f),
                timestamp = System.currentTimeMillis()
            )
            repository.saveSession(session)
            
            // Increment streak if study completed today
            updateStudyStreak()
        }
    }

    private fun updateStudyStreak() {
        val lastDateStr = prefs.getString("last_study_date", "") ?: ""
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val todayStr = dateFormat.format(Date())

        if (lastDateStr != todayStr) {
            var currentStreak = prefs.getInt("streak_count", 0)
            if (lastDateStr.isNotEmpty()) {
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                val yesterdayStr = dateFormat.format(calendar.time)
                if (lastDateStr == yesterdayStr) {
                    currentStreak++
                } else {
                    currentStreak = 1
                }
            } else {
                currentStreak = 1
            }
            prefs.edit().apply {
                putInt("streak_count", currentStreak)
                putString("last_study_date", todayStr)
            }.apply()
        }
    }

    fun getStreakCount(): Int {
        return prefs.getInt("streak_count", 0)
    }

    fun triggerSpeakForCurrentWord() {
        if (!isSoundEnabled.value) return
        currentWord?.let {
            viewModelScope.launch {
                _speakEvent.emit(it.word)
            }
        }
    }

    fun speakWord(word: String) {
        viewModelScope.launch {
            _speakEvent.emit(word)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearProgress()
            prefs.edit().apply {
                putInt("streak_count", 0)
                putString("last_study_date", "")
            }.apply()
        }
    }

    // JSON Data Import & Export for full backup features
    fun exportBackupJson(): String {
        val result = JSONObject()
        try {
            result.put("backupVersion", 1)
            result.put("appName", "LexiFlow")
            result.put("exportedAt", System.currentTimeMillis())
            
            val settings = JSONObject().apply {
                put("category", _selectedCategory.value)
                put("dailyNew", dailyNewWordsTarget.value)
                put("dailyReview", dailyReviewTarget.value)
                put("wordsSession", wordsPerSession.value)
                put("showPhonetic", showPhoneticBeforeAnswer.value)
                put("sound", isSoundEnabled.value)
                put("translation", isTranslationVisible.value)
                put("streak", getStreakCount())
            }
            result.put("settings", settings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.toString()
    }

    fun importBackupJson(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            if (!root.has("appName") || root.getString("appName") != "LexiFlow") return false
            
            val settings = root.getJSONObject("settings")
            _selectedCategory.value = settings.optString("category", "CET-4")
            dailyNewWordsTarget.value = settings.optInt("dailyNew", 10)
            dailyReviewTarget.value = settings.optInt("dailyReview", 15)
            wordsPerSession.value = settings.optInt("wordsSession", 20)
            showPhoneticBeforeAnswer.value = settings.optBoolean("showPhonetic", false)
            isSoundEnabled.value = settings.optBoolean("sound", true)
            isTranslationVisible.value = settings.optBoolean("translation", true)

            prefs.edit().apply {
                putString("selected_category", _selectedCategory.value)
                putInt("daily_new_words", dailyNewWordsTarget.value)
                putInt("daily_reviews", dailyReviewTarget.value)
                putInt("words_per_session", wordsPerSession.value)
                putBoolean("show_phonetic_before_answer", showPhoneticBeforeAnswer.value)
                putBoolean("is_sound_enabled", isSoundEnabled.value)
                putBoolean("is_translation_visible", isTranslationVisible.value)
                putInt("streak_count", settings.optInt("streak", getStreakCount()))
            }.apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun updateTypedText(text: String) {
        _typingState.value = _typingState.value.copy(typedText = text)
    }

    fun submitClozeAnswer() {
        val state = _typingState.value
        val wordData = currentWord ?: return
        val trimmedInput = state.typedText.trim()
        val isCorrect = trimmedInput.equals(wordData.word.trim(), ignoreCase = true)
        
        val distance = levenshteinDistance(trimmedInput.lowercase(), wordData.word.trim().lowercase())

        val updatedTotal = state.totalKeystrokes + 1
        val updatedErrors = if (isCorrect) state.errorKeystrokes else state.errorKeystrokes + 1

        _typingState.value = state.copy(
            isAnswerRevealed = true,
            totalKeystrokes = updatedTotal,
            errorKeystrokes = updatedErrors
        )
        triggerSpeakForCurrentWord()
        updateLiveStats()
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                if (s1[i - 1] == s2[j - 1]) {
                    dp[j] = prev
                } else {
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + 1)
                }
                prev = temp
            }
        }
        return dp[s2.length]
    }
}

class QwertyViewModelFactory(
    private val application: Application,
    private val repository: WordRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(QwertyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return QwertyViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
