package com.example

import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  // Helper for Levenshtein distance to test logic in unit tests
  private fun calculateLevenshtein(s1: String, s2: String): Int {
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

  // Helper for masking sentence to test logic in unit tests
  private fun maskSentence(sentence: String, target: String): String {
    if (sentence.isEmpty() || target.isEmpty()) return sentence
    val escapedTarget = Regex.escape(target)
    val regex = "\\b${escapedTarget}(?:ing|ed|s|es|ly)?\\b".toRegex(RegexOption.IGNORE_CASE)
    return sentence.replace(regex, "_______")
  }

  @Test
  fun testLevenshteinDistance() {
    // Identical
    assertEquals(0, calculateLevenshtein("maintain", "maintain"))
    
    // Typo - 1 character substitution
    assertEquals(1, calculateLevenshtein("mantain", "maintain"))
    
    // Typo - 1 character insertion
    assertEquals(1, calculateLevenshtein("maintainn", "maintain"))
    
    // Typo - 1 character deletion
    assertEquals(1, calculateLevenshtein("maintai", "maintain"))
    
    // Completely different
    assertEquals(5, calculateLevenshtein("apple", "banana"))
  }

  @Test
  fun testClozeSentenceMasking() {
    val sentence = "Regular exercise helps maintain good health, but maintaining is hard."
    
    // Simple word masking
    val masked = maskSentence(sentence, "maintain")
    assertEquals("Regular exercise helps _______ good health, but _______ is hard.", masked)
    
    // Plurals / forms masking
    val sentence2 = "He maintains several servers."
    val masked2 = maskSentence(sentence2, "maintain")
    assertEquals("He _______ several servers.", masked2)
  }
}
