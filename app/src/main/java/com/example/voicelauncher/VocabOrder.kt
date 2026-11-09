package com.example.voicelauncher

/**
 * A contact as the vocabulary ranker sees it. Only the name travels to the relay, so the ranker
 * needs the ranking inputs kept separate from the display data.
 */
data class VocabEntry(
    val name: String,
    val starred: Boolean = false,
    val usageCount: Int = 0
)

/**
 * Shared by UsageStore and ContactProvider: the store writes under this key when an action is
 * confirmed, the ranker reads it back when building the header. contactId when the provider gave
 * us one, otherwise the same normalized name+number pair ContactDedupe would have grouped on.
 */
fun usageKeyFor(contactId: Long, normalizedName: String, normalizedNumber: String): String =
    if (contactId != NO_CONTACT_ID) "id:$contactId"
    else "name:$normalizedName|num:$normalizedNumber"

/**
 * The relay keeps only the first GEMINI_VOCAB_LIMIT names of the header as speech-recognition
 * hints, so the order of this list decides which contacts the recognizer can hear at all.
 */
object VocabOrder {

    private val TRAILING_PUNCTUATION = Regex("[\\s.,_\\-]+$")
    private val TRAILING_DIGITS = Regex("\\s*[0-9]+$")
    private val REPEATED_SPACES = Regex("\\s+")

    fun cleanName(raw: String): String {
        var name = raw.trim().replace(TRAILING_PUNCTUATION, "")
        val withoutDigits = name.replace(TRAILING_DIGITS, "")
        if (withoutDigits.count { it.isLetter() } >= 2) name = withoutDigits
        return name.replace(REPEATED_SPACES, " ").trim()
    }

    fun rank(entries: List<VocabEntry>): List<String> {
        val cleaned = entries
            .map { it.copy(name = cleanName(it.name)) }
            .filter { it.name.isNotBlank() }
        val order = compareByDescending<VocabEntry> { it.starred }
            .thenByDescending { it.usageCount }
            .thenBy { it.name.lowercase() }
        val seen = HashSet<String>()
        return cleaned.sortedWith(order)
            .filter { seen.add(it.name.lowercase()) }
            .map { it.name }
    }
}
