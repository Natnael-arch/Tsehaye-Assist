package com.example.voicelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The relay only reads the first GEMINI_VOCAB_LIMIT names of the header as speech hints, so these
 * tests pin down which contacts earn a slot: starred and used-first, with the duplicate-looking
 * digit-suffixed names collapsed so they stop spending slots on the same person.
 */
class VocabOrderTest {

    private fun entry(name: String, starred: Boolean = false, usage: Int = 0) =
        VocabEntry(name, starred, usage)

    @Test
    fun starredContactsComeFirstRegardlessOfAlphabetOrUsage() {
        val ranked = VocabOrder.rank(
            listOf(
                entry("Abebe"),
                entry("Zola", usage = 40),
                entry("Setot Beret", starred = true)
            )
        )
        assertEquals(listOf("Setot Beret", "Zola", "Abebe"), ranked)
    }

    @Test
    fun higherUsageOutranksLowerUsageWithinTheSameStarredTier() {
        val ranked = VocabOrder.rank(
            listOf(
                entry("Gecho", usage = 3),
                entry("Hana", usage = 12),
                entry("Abera", usage = 0),
                entry("Nati", starred = true, usage = 1)
            )
        )
        assertEquals(listOf("Nati", "Hana", "Gecho", "Abera"), ranked)
    }

    @Test
    fun everythingWithoutAStarOrUsageFallsBackToCaseInsensitiveAlphabetical() {
        val ranked = VocabOrder.rank(listOf(entry("banana"), entry("Cherry"), entry("Apple")))
        assertEquals(listOf("Apple", "banana", "Cherry"), ranked)
    }

    @Test
    fun trailingDigitSuffixesAreStripped() {
        listOf(
            "Rodas1" to "Rodas",
            "Fere1" to "Fere",
            "Ie Cafe2" to "Ie Cafe",
            "Motuma 1" to "Motuma",
            "Nati Keleme2" to "Nati Keleme",
            "Bayesa1" to "Bayesa"
        ).forEach { (raw, expected) ->
            assertEquals(expected, VocabOrder.cleanName(raw))
        }
    }

    @Test
    fun trailingPunctuationAndSpacesAreStrippedBeforeTheDigitRule() {
        listOf(
            "Mahi." to "Mahi",
            "Abera," to "Abera",
            "Hana -" to "Hana",
            "Mom_" to "Mom",
            "Dad 2. " to "Dad"
        ).forEach { (raw, expected) ->
            assertEquals(expected, VocabOrder.cleanName(raw))
        }
    }

    @Test
    fun digitStripIsRejectedWhenFewerThanTwoLettersWouldRemain() {
        assertEquals("A1", VocabOrder.cleanName("A1"))
        assertEquals("1", VocabOrder.cleanName("1"))
        assertEquals("Ab", VocabOrder.cleanName("Ab1"))
    }

    @Test
    fun repeatedSpacesAreCollapsedAndBlankNamesAreDropped() {
        assertEquals("Nati Keleme", VocabOrder.cleanName("Nati   Keleme"))
        assertEquals(listOf("Abebe"), VocabOrder.rank(listOf(entry("Abebe"), entry("   "), entry("."))))
    }

    @Test
    fun caseInsensitiveDedupeKeepsTheBestRankedEntry() {
        val ranked = VocabOrder.rank(
            listOf(
                entry("ABERA", starred = true),
                entry("Abera1", usage = 99),
                entry("Wossen")
            )
        )
        assertEquals(listOf("ABERA", "Wossen"), ranked)
    }

    @Test
    fun rankingIsCappedToHeaderLimitByDefault() {
        val many = (1..150).map { entry("User $it a") }
        val ranked = VocabOrder.rank(many)
        assertEquals(VocabOrder.MAX_HEADER_ENTRIES, ranked.size)
        assertEquals(100, ranked.size)
    }

    @Test
    fun equalRankKeepsTheFirstListedNameSoOrderingStaysDeterministic() {
        val ranked = VocabOrder.rank(listOf(entry("rodas"), entry("Rodas"), entry("Abebe")))
        assertEquals(listOf("Abebe", "rodas"), ranked)
    }

    @Test
    fun usageKeyUsesContactIdAndFallsBackToNameAndNumber() {
        assertEquals("id:374", usageKeyFor(374L, "setot beret", "922754951"))
        assertEquals(
            "name:mom|num:911000807",
            usageKeyFor(NO_CONTACT_ID, "mom", "911000807")
        )
    }
}
