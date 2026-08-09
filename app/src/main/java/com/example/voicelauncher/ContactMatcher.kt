package com.example.voicelauncher

import android.util.Log
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

        // ── DIAGNOSTIC: Log ALL scored candidates (top 10 + any ≥0.4) ──
        Log.w("ContactMatcher", "╔══ MATCH SCORES for query='$normalizedTarget' (${contacts.size} contacts) ══")
        val interesting = scores.take(10).toMutableList()
        scores.drop(10).filter { it.second >= 0.4 }.forEach { interesting.add(it) }
        interesting.sortedByDescending { it.second }.forEachIndexed { i, (contact, score) ->
            val marker = when {
                score >= 0.9 -> "✅ EXACT"
                score >= 0.6 -> "🟡 DISAMBIG"
                else -> "❌ BELOW"
            }
            Log.w("ContactMatcher", "║  #${i+1} score=%.4f $marker name='${contact.name}' normalized='${contact.normalizedName}'".format(score))
        }

        if (scores.isEmpty()) {
            Log.w("ContactMatcher", "║  (no contacts to score)")
            Log.w("ContactMatcher", "╚══ RESULT: NoMatch ══")
            return MatchResult.NoMatch
        }

        val bestScore = scores[0].second
        Log.w("ContactMatcher", "║  bestScore=%.4f threshold_exact=0.9 threshold_disambig=0.6".format(bestScore))

        return when {
            bestScore >= 0.9 -> {
                Log.w("ContactMatcher", "╚══ RESULT: ExactMatch → '${scores[0].first.name}' (score=%.4f) ══".format(bestScore))
                MatchResult.ExactMatch(scores[0].first)
            }
            bestScore >= 0.6 -> {
                // Return up to top 3 candidates that are reasonably close to the best score
                val candidates = scores.take(3)
                    .filter { it.second >= 0.6 && it.second >= (bestScore * 0.8) }
                    .map { it.first }
                if (candidates.isEmpty()) {
                    Log.w("ContactMatcher", "╚══ RESULT: NoMatch (candidates filtered out) ══")
                    MatchResult.NoMatch
                } else {
                    Log.w("ContactMatcher", "╚══ RESULT: DisambiguationRequired → ${candidates.map { "'${it.name}'" }} ══")
                    MatchResult.DisambiguationRequired(candidates)
                }
            }
            else -> {
                Log.w("ContactMatcher", "╚══ RESULT: NoMatch (bestScore=%.4f < 0.6) ══".format(bestScore))
                MatchResult.NoMatch
            }
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
