package com.example.voicelauncher

import android.util.Log

object AmharicCleaner {
    fun clean(input: String): String {
        return input.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .trim()
    }
}

object ContactResolver {
    private val contactMatcher = ContactMatcher()

    data class ResolvedContact(val match: MatchResult, val path: String)

    fun resolve(rawName: String, contacts: List<Contact>): ResolvedContact {
        Log.w("ContactResolver", "┌── resolveContactFromDualScriptQuery ──")
        Log.w("ContactResolver", "│  rawName='$rawName'")

        var parts = rawName.split(Regex("[,|/]")).map { it.trim() }.filter { it.isNotEmpty() }
        Log.w("ContactResolver", "│  split by [,|/] → parts=$parts")
        if (parts.size < 2) {
            parts = rawName.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
            Log.w("ContactResolver", "│  split by space → parts=$parts")
        }

        val rawAmharic = parts.firstOrNull { p -> p.any { it in '\u1200'..'\u137F' } }
                         ?: parts.firstOrNull() ?: rawName

        val rawLatin = parts.lastOrNull { p ->
            p.any { it in 'A'..'z' } && p != rawAmharic
        } ?: if (parts.size > 1) parts.last() else null

        val amharicQuery = AmharicCleaner.clean(rawAmharic)
        val latinQuery = rawLatin?.trim()

        Log.w("ContactResolver", "│  rawAmharic='$rawAmharic' → cleaned='$amharicQuery'")
        Log.w("ContactResolver", "│  rawLatin='$rawLatin' → trimmed='$latinQuery'")

        if (amharicQuery.isNotBlank()) {
            Log.w("ContactResolver", "│  ATTEMPTING amharic path with query='$amharicQuery'")
            val match = contactMatcher.findBestMatches(amharicQuery, contacts)
            if (match !is MatchResult.NoMatch) {
                Log.w("ContactResolver", "└── RESOLVED via amharic path")
                return ResolvedContact(match, "amharic:$amharicQuery")
            }
            Log.w("ContactResolver", "│  amharic path → NoMatch, falling through")
        }

        if (!latinQuery.isNullOrBlank()) {
            Log.w("ContactResolver", "│  ATTEMPTING latin path with query='$latinQuery'")
            val match = contactMatcher.findBestMatches(latinQuery, contacts)
            if (match !is MatchResult.NoMatch) {
                Log.w("ContactResolver", "└── RESOLVED via latin path")
                return ResolvedContact(match, "latin:$latinQuery")
            }
            Log.w("ContactResolver", "│  latin path → NoMatch")
        }

        val usedQuery = amharicQuery.ifBlank { latinQuery } ?: "unknown"
        Log.w("ContactResolver", "└── RESOLVED: NoMatch (both paths failed)")
        return ResolvedContact(MatchResult.NoMatch, "none:$usedQuery")
    }
}
