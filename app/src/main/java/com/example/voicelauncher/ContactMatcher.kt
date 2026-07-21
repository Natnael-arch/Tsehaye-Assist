package com.example.voicelauncher

import kotlin.math.max
import kotlin.math.min

sealed class MatchResult {
    data class ExactMatch(val contact: Contact) : MatchResult()
    data class DisambiguationRequired(val candidates: List<Contact>) : MatchResult()
    object NoMatch : MatchResult()
}

class ContactMatcher {

    /**
     * Finds the best matching contacts using Levenshtein distance.
     */
    fun findBestMatches(targetName: String, contacts: List<Contact>): MatchResult {
        if (targetName.isBlank()) return MatchResult.NoMatch

        val normalizedTarget = targetName.lowercase().trim()
        val scores = contacts.map { contact ->
            val score = calculateSimilarity(normalizedTarget, contact.normalizedName)
            Pair(contact, score)
        }.sortedByDescending { it.second }

        if (scores.isEmpty()) return MatchResult.NoMatch

        val bestScore = scores[0].second

        return when {
            bestScore >= 0.9 -> MatchResult.ExactMatch(scores[0].first)
            bestScore >= 0.6 -> {
                // Return up to top 3 candidates that are reasonably close to the best score
                val candidates = scores.take(3)
                    .filter { it.second >= 0.6 && it.second >= (bestScore * 0.8) }
                    .map { it.first }
                if (candidates.isEmpty()) MatchResult.NoMatch
                else MatchResult.DisambiguationRequired(candidates)
            }
            else -> MatchResult.NoMatch
        }
    }

    /**
     * Calculates similarity between 0.0 and 1.0 using Levenshtein distance.
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val maxLen = max(s1.length, s2.length)
        val distance = levDistance(s1, s2)
        return (maxLen - distance).toDouble() / maxLen.toDouble()
    }

    private fun levDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
