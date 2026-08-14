package com.example.voicelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverTest {

    private val sampleContacts = listOf(
        Contact("Abebe", "0911000001", "abebe"),
        Contact("አበበ", "0911000002", "አበበ"),
        Contact("Abebe Kebede", "0911000003", "abebe kebede"),
        Contact("አበበ በቀለ", "0911000004", "አበበ በቀለ"),
        Contact("Dawit Haile", "0911000005", "dawit haile"),
        Contact("ዳዊት ኃይሌ", "0911000006", "ዳዊት ኃይሌ"),
        Contact("Helen", "0911000007", "helen"),
        Contact("ሄለን", "0911000008", "ሄለን"),
        Contact("John Smith", "0911000011", "john smith"),
        Contact("John Smyth", "0911000012", "john smyth"),
        Contact("Alexandros", "0911000013", "alexandros"),
        Contact("Alexandra", "0911000014", "alexandra")
    )

    data class TestCase(
        val query: String,
        val contacts: List<Contact>,
        val expectedResultType: Class<out MatchResult>,
        val expectedContactName: String? = null
    )

    @Test
    fun testContactResolver() {
        val testCases = listOf(
            // Exact match, Latin script only
            TestCase("Abebe", sampleContacts, MatchResult.ExactMatch::class.java, "Abebe"),
            // Exact match, Amharic script only
            TestCase("አበበ", sampleContacts, MatchResult.ExactMatch::class.java, "አበበ"),
            // Dual-script query matching Latin
            TestCase("አበበ ከበደ, Abebe Kebede", sampleContacts, MatchResult.ExactMatch::class.java, "Abebe Kebede"),
            // Dual-script query matching Amharic
            TestCase("አበበ በቀለ, Abebe Bekele", sampleContacts, MatchResult.ExactMatch::class.java, "አበበ በቀለ"),
            // Dual-script query with slash
            TestCase("ዳዊት ኃይሌ / Dawit Haile", sampleContacts, MatchResult.ExactMatch::class.java, "ዳዊት ኃይሌ"),
            // Near-miss / fuzzy spelling short name -> triggers Disambiguation because score is 0.8
            TestCase("Heln", sampleContacts, MatchResult.DisambiguationRequired::class.java),
            // Near-miss / fuzzy spelling long name -> triggers ExactMatch because score is 0.9
            TestCase("Alexandrs", sampleContacts, MatchResult.ExactMatch::class.java, "Alexandros"),
            // Disambiguation Required - two similar names with score 0.8 ("Alexandr" vs "Alexandros" / "Alexandra")
            TestCase("Alexandr", sampleContacts, MatchResult.DisambiguationRequired::class.java),
            // No Match
            TestCase("XYZ", sampleContacts, MatchResult.NoMatch::class.java),
            // Disambiguation from dual-script overlapping multiple hits
            TestCase("Alexandr, Alexandr", sampleContacts, MatchResult.DisambiguationRequired::class.java)
        )

        for ((index, tc) in testCases.withIndex()) {
            val result = ContactResolver.resolve(tc.query, tc.contacts)
            val match = result.match
            assertTrue(
                "Test case $index failed: expected ${tc.expectedResultType.simpleName} but got ${match.javaClass.simpleName} for query '${tc.query}'",
                tc.expectedResultType.isInstance(match)
            )
            
            if (tc.expectedContactName != null && match is MatchResult.ExactMatch) {
                assertEquals(
                    "Test case $index failed on contact name",
                    tc.expectedContactName,
                    match.contact.name
                )
            }
        }
    }
}
