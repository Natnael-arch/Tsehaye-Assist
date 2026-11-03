package com.example.voicelauncher

import android.util.Log
import kotlin.math.max
import kotlin.math.min

sealed class MatchResult {
    data class ExactMatch(val contact: Contact) : MatchResult()
    data class DisambiguationRequired(val candidates: List<Contact>) : MatchResult() {
        val labels: List<String> get() = CandidateLabels.forCandidates(candidates)
    }
    object NoMatch : MatchResult()
}

/**
 * Repeating the same name twice is useless when it is read aloud, so a label carries its number
 * ending when that name is repeated in the set, or when it differs from another candidate only by
 * a trailing number ("Dad" vs "Dad 2"). Endings widen from 3 to 4 digits, then to the whole number,
 * until no two labels match.
 */
object CandidateLabels {

    fun forCandidates(candidates: List<Contact>): List<String> {
        if (candidates.size < 2) return candidates.map { it.name }
        val decorated = decoratedNames(candidates)
        if (decorated.isEmpty()) return candidates.map { it.name }
        for (digits in listOf(3, 4, Int.MAX_VALUE)) {
            val labels = candidates.map { labelFor(it, decorated, digits) }
            if (labels.distinct().size == labels.size) return labels
        }
        return candidates.map { labelFor(it, decorated, Int.MAX_VALUE) }
    }

    private fun labelFor(contact: Contact, decorated: Set<String>, digits: Int) =
        if (contact.name in decorated)
            "${contact.name} ending ${contact.normalizedNumber.takeLast(digits)}"
        else contact.name

    private fun decoratedNames(candidates: List<Contact>): Set<String> {
        val counts = candidates.groupingBy { it.name }.eachCount()
        val decorated = candidates.map { it.name }.filter { counts.getValue(it) > 1 }.toMutableSet()
        val names = candidates.map { it.normalizedName }.distinct()
        for (short in names) for (long in names) {
            if (isNumberSuffixPair(short, long)) {
                candidates.filter { it.normalizedName == short || it.normalizedName == long }
                    .forEach { decorated.add(it.name) }
            }
        }
        return decorated
    }

    /** "Dad"/"Dad 2" and "Rodas"/"Rodas1" are hard to tell apart; "Hana"/"Hana Gorebet" are not. */
    private fun isNumberSuffixPair(short: String, long: String): Boolean {
        if (long == short || !long.startsWith(short)) return false
        val suffix = long.substring(short.length).trim()
        return suffix.isNotEmpty() && suffix.all { it.isDigit() }
    }
}

/**
 * The spoken answer to an ambiguity prompt: "<name> ending <digits>", optionally with the comma the
 * app itself speaks ("Mom, ending 294").
 */
data class EndingAnswer(val name: String, val digits: String) {
    companion object {
        private val SPOKEN = Regex("^(.*?)[,\\s]*ending\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)

        fun parse(query: String): EndingAnswer? {
            val match = SPOKEN.find(query.trim()) ?: return null
            val name = match.groupValues[1].trim().removeSuffix(",").trim()
            if (name.isBlank()) return null
            return EndingAnswer(name, match.groupValues[2])
        }

        /** A bare "294" is only an answer, never a name, so it takes two digits to count as one. */
        fun bareDigits(query: String): String? {
            val trimmed = query.trim()
            return if (trimmed.length >= 2 && trimmed.all { it.isDigit() }) trimmed else null
        }
    }
}

class ContactMatcher {

    /**
     * Finds the best matching contacts using token-set scoring and Levenshtein distance.
     * [rawName] is only used to label the temporary [MATCH] log line.
     */
    fun findBestMatches(targetName: String, contacts: List<Contact>, rawName: String = targetName): MatchResult {
        if (targetName.isBlank()) return MatchResult.NoMatch

        val normalizedTarget = targetName.lowercase().trim()
        val queryTokens = normalizedTarget.split(" ").filter { it.isNotBlank() }
        val scores = contacts.map { contact ->
            val contactTokens = contact.normalizedName.split(" ").filter { it.isNotBlank() }
            val score = tokenSetScore(queryTokens, contactTokens)
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
        val tiedAtBest = scores.count { it.second >= bestScore - 0.001 }
        Log.w("ContactMatcher", "║  bestScore=%.4f tiedAtBest=$tiedAtBest threshold_exact=0.9 threshold_disambig=0.6".format(bestScore))

        return when {
            bestScore >= 0.9 && tiedAtBest == 1 -> {
                Log.w("ContactMatcher", "╚══ RESULT: ExactMatch → '${scores[0].first.name}' (score=%.4f) ══".format(bestScore))
                MatchResult.ExactMatch(scores[0].first)
            }
            bestScore >= 0.6 || (bestScore >= 0.9 && tiedAtBest > 1) -> {
                // Return up to top 3 candidates that are reasonably close to the best score
                val shortlist = scores.take(3).filter { it.second >= 0.6 && it.second >= (bestScore * 0.8) }
                if (shortlist.isEmpty()) {
                    Log.w("ContactMatcher", "╚══ RESULT: NoMatch (candidates filtered out) ══")
                    MatchResult.NoMatch
                } else {
                    val candidates = collapseByNumber(shortlist, bestScore)
                    if (candidates.size == 1) {
                        Log.w("ContactMatcher", "╚══ RESULT: ExactMatch (collapsed ${shortlist.size} candidates sharing number '${candidates[0].normalizedNumber}') → '${candidates[0].name}' ══")
                        MatchResult.ExactMatch(candidates[0])
                    } else {
                        val labels = CandidateLabels.forCandidates(candidates)
                        if (BuildConfig.DEBUG) {
                            Log.i("ContactMatcher", "[MATCH] query='$normalizedTarget' raw='$rawName' afterDedupe=${contacts.size} labels=$labels")
                        }
                        Log.w("ContactMatcher", "╚══ RESULT: DisambiguationRequired → $labels ══")
                        MatchResult.DisambiguationRequired(candidates)
                    }
                }
            }
            else -> {
                Log.w("ContactMatcher", "╚══ RESULT: NoMatch (bestScore=%.4f < 0.6) ══".format(bestScore))
                MatchResult.NoMatch
            }
        }
    }

    /**
     * Resolves a spoken answer of the form "Mom ending 294": the name is scored on its own, then the
     * survivors are narrowed to the numbers that actually end with those digits.
     */
    fun findBestMatches(answer: EndingAnswer, contacts: List<Contact>, rawName: String): MatchResult {
        val scored = findBestMatches(answer.name, contacts, rawName)
        return narrowToNumberEnding(scored, answer.digits)
    }

    /** Resolves a bare "294" against the candidates the ambiguity prompt just offered. */
    fun matchByNumberEnding(candidates: List<Contact>, digits: String): MatchResult =
        narrowToNumberEnding(MatchResult.DisambiguationRequired(candidates), digits)

    private fun narrowToNumberEnding(result: MatchResult, digits: String): MatchResult = when (result) {
        is MatchResult.ExactMatch ->
            if (result.contact.normalizedNumber.endsWith(digits)) result else MatchResult.NoMatch
        is MatchResult.DisambiguationRequired -> {
            val kept = result.candidates.filter { it.normalizedNumber.endsWith(digits) }
            when (kept.size) {
                1 -> MatchResult.ExactMatch(kept[0])
                0 -> MatchResult.NoMatch
                else -> MatchResult.DisambiguationRequired(kept)
            }
        }
        is MatchResult.NoMatch -> MatchResult.NoMatch
    }

    /**
     * Tied-at-best rows reaching the same number are one person, so the best-named of them wins.
     * The losers are dropped before the looser-scoring rows are considered, so a query that matches
     * one spelling exactly can never be swallowed by a weaker match sharing that phone number.
     */
    private fun collapseByNumber(shortlist: List<Pair<Contact, Double>>, bestScore: Double): List<Contact> {
        val seenNumbers = HashSet<String>()
        val winners = shortlist.filter { it.second >= bestScore - 0.001 }
            .map { it.first }
            .filter { seenNumbers.add(it.normalizedNumber) }
        val rest = shortlist.filter { it.second < bestScore - 0.001 }
            .map { it.first }
            .filter { seenNumbers.add(it.normalizedNumber) }
        return winners + rest
    }

    private fun tokenSetScore(queryTokens: List<String>, contactTokens: List<String>): Double {
        if (queryTokens.isEmpty() || contactTokens.isEmpty()) return 0.0
        val perTokenBest = queryTokens.map { qt ->
            contactTokens.maxOf { ct -> calculateSimilarity(qt, ct) }
        }
        return perTokenBest.average()
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
