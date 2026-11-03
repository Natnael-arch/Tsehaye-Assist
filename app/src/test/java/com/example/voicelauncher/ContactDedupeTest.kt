package com.example.voicelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate contacts arrive as several rows of the data/phones view (Google + SIM + local account
 * for one person), which used to make the matcher answer AMBIGUITY with a list of identical names.
 */
class ContactDedupeTest {

    private fun resolve(
        query: String,
        rows: List<Contact>,
        lastCandidates: List<Contact>? = null
    ): MatchResult = ContactResolver.resolve(query, ContactDedupe.byContact(rows), lastCandidates).match

    @Test
    fun sameContactIdAndNumberAcrossThreeRowsCollapsesToOneCandidate() {
        val rows = listOf(
            Contact("Abera", "0911222345", "abera", 101L),
            Contact("Abera", "0911222345", "abera", 101L),
            Contact("Abera", "+251911222345", "abera", 101L)
        )
        val deduped = ContactDedupe.byContact(rows)
        assertEquals(1, deduped.size)
        assertEquals(101L, deduped[0].contactId)
        assertEquals("911222345", deduped[0].normalizedNumber)
    }

    @Test
    fun sameNameDifferentContactIdsStaysAmbiguousWithNumberEndings() {
        val rows = listOf(
            Contact("Mom", "0911000294", "mom", 201L),
            Contact("Mom", "0911000807", "mom", 202L)
        )
        val match = resolve("Mom", rows)
        assertTrue(match is MatchResult.DisambiguationRequired)
        assertEquals(listOf("Mom ending 294", "Mom ending 807"), (match as MatchResult.DisambiguationRequired).labels)
    }


    @Test
    fun localAndInternationalFormatOfOneNumberDedupesToOneRow() {
        val rows = listOf(
            Contact("Yonas", "+251716886327", "yonas", 401L),
            Contact("Yonas", "0716886327", "yonas", 401L)
        )
        assertEquals(1, ContactDedupe.byContact(rows).size)
    }

    @Test
    fun singleExactMatchStaysUnambiguous() {
        val rows = listOf(Contact("Dad", "0911000555", "dad", 501L))
        val match = resolve("Dad", rows)
        assertTrue(match is MatchResult.ExactMatch)
        assertEquals("Dad", (match as MatchResult.ExactMatch).contact.name)
    }

    @Test
    fun labelsWidenToFourDigitsWhenThreeStillCollide() {
        val rows = listOf(
            Contact("Hana", "0911111129", "hana", 601L),
            Contact("Hana", "0911222129", "hana", 602L),
            Contact("Hana", "0911333129", "hana", 603L)
        )
        val labels = CandidateLabels.forCandidates(rows)
        assertEquals(listOf("Hana ending 1129", "Hana ending 2129", "Hana ending 3129"), labels)
    }

    @Test
    fun tiedCandidatesSharingOneNumberCollapseToASingleCandidate() {
        val rows = listOf(
            Contact("Abera", "0911222345", "abera", 801L),
            Contact("Abera", "0911222345", "abera", 802L)
        )
        val match = resolve("Abera", rows)
        assertTrue(match is MatchResult.ExactMatch)
    }

    @Test
    fun nestedNamesBothCarryTheirOwnNumberEnding() {
        val rows = listOf(
            Contact("Dad 2", "+251902436643", "dad 2", 276L),
            Contact("Dad", "+251902436643", "dad", 5090L),
            Contact("Dad", "0910442281", "dad", 2901L)
        )
        val match = resolve("Dad", rows)
        assertTrue(match is MatchResult.DisambiguationRequired)
        assertEquals(listOf("Dad 2 ending 643", "Dad ending 281"), (match as MatchResult.DisambiguationRequired).labels)
    }

    @Test
    fun exactQueryOnRodas1SurvivesTheCollapse() {
        val match = resolve("Rodas1", rodasRows())
        assertTrue(match is MatchResult.ExactMatch)
        assertEquals("Rodas1", (match as MatchResult.ExactMatch).contact.name)
    }

    @Test
    fun bareRodasCollapsesItsDuplicateRowsToOneCandidate() {
        val match = resolve("Rodas", rodasRows())
        assertTrue(match is MatchResult.ExactMatch)
        assertEquals("Rodas", (match as MatchResult.ExactMatch).contact.name)
    }

    private fun rodasRows() = listOf(
        Contact("Rodas", "+251716886327", "rodas", 84L),
        Contact("Rodas", "0716886327", "rodas", 4856L),
        Contact("Rodas1", "+251716886327", "rodas1", 4855L)
    )

    @Test
    fun aWordSuffixDoesNotDragTheWholeSetIntoNumberEndings() {
        val rows = listOf(
            Contact("Hana", "+251701421215", "hana", 4854L),
            Contact("Hana", "0941405488", "hana", 336L),
            Contact("Hana Gorebet", "0942792704", "hana gorebet", 4775L)
        )
        val match = resolve("Hana", rows)
        assertTrue(match is MatchResult.DisambiguationRequired)
        assertEquals(
            listOf("Hana ending 215", "Hana ending 488", "Hana Gorebet"),
            (match as MatchResult.DisambiguationRequired).labels
        )

        val nati = listOf(
            Contact("Nati Ayalew", "+251940399521", "nati ayalew", 337L),
            Contact("Nati Abebaw", "+251962275933", "nati abebaw", 295L),
            Contact("Nati", "+251963293187", "nati", 4054L)
        )
        val natiMatch = resolve("Nati", nati)
        assertTrue(natiMatch is MatchResult.DisambiguationRequired)
        assertEquals(
            listOf("Nati Ayalew", "Nati Abebaw", "Nati"),
            (natiMatch as MatchResult.DisambiguationRequired).labels
        )
    }

    @Test
    fun spokenAnswerPicksTheMomWhoseNumberEndsWithTheSpokenDigits() {
        val rows = momRows()
        val first = resolve("Mom ending 294", rows)
        assertTrue(first is MatchResult.ExactMatch)
        assertEquals("0911000294", (first as MatchResult.ExactMatch).contact.phoneNumber)

        val second = resolve("Mom ending 807", rows)
        assertTrue(second is MatchResult.ExactMatch)
        assertEquals("0911000807", (second as MatchResult.ExactMatch).contact.phoneNumber)
    }

    @Test
    fun commaFormOfTheSpokenAnswerWorksToo() {
        val match = resolve("Mom, ending 294", momRows())
        assertTrue(match is MatchResult.ExactMatch)
        assertEquals("0911000294", (match as MatchResult.ExactMatch).contact.phoneNumber)
    }

    @Test
    fun answerNamingTheNumberedDadResolvesToThatContact() {
        val rows = listOf(
            Contact("Dad 2", "+251902436643", "dad 2", 276L),
            Contact("Dad", "+251902436643", "dad", 5090L),
            Contact("Dad", "0910442281", "dad", 2901L)
        )
        val match = resolve("Dad 2 ending 643", rows)
        assertTrue(match is MatchResult.ExactMatch)
        assertEquals("Dad 2", (match as MatchResult.ExactMatch).contact.name)
    }

    @Test
    fun plainMomStillOffersBothNumbers() {
        val match = resolve("Mom", momRows())
        assertTrue(match is MatchResult.DisambiguationRequired)
        assertEquals(2, (match as MatchResult.DisambiguationRequired).candidates.size)
    }

    @Test
    fun answerDigitsMatchingNothingIsNoMatchNotACrash() {
        assertEquals(MatchResult.NoMatch, resolve("Mom ending 1111", momRows()))
        assertEquals(MatchResult.NoMatch, resolve("ending 294", momRows()))
        assertEquals(MatchResult.NoMatch, resolve("294", momRows(), emptyList()))
    }

    @Test
    fun bareDigitsAnswerUsesTheCandidatesJustOffered() {
        val offered = ContactDedupe.byContact(momRows())
        val match = ContactResolver.resolve("294", offered, offered).match
        assertTrue(match is MatchResult.ExactMatch)
        assertEquals("0911000294", (match as MatchResult.ExactMatch).contact.phoneNumber)
    }

    private fun momRows() = listOf(
        Contact("Mom", "0911000294", "mom", 201L),
        Contact("Mom", "0911000807", "mom", 202L)
    )

    @Test
    fun labelsFallBackToTheWholeNumberWhenFourDigitsStillCollide() {
        val labels = CandidateLabels.forCandidates(
            listOf(
                Contact("Yohannes", "0911000129", "yohannes", 901L),
                Contact("Yohannes", "0922000129", "yohannes", 902L)
            )
        )
        assertEquals(listOf("Yohannes ending 911000129", "Yohannes ending 922000129"), labels)
        assertEquals(2, labels.distinct().size)
    }

    @Test
    fun uniqueNamesKeepTheirPlainLabel() {
        val labels = CandidateLabels.forCandidates(
            listOf(Contact("Menen", "0911000111", "menen", 701L), Contact("Aster", "0911000222", "aster", 702L))
        )
        assertEquals(listOf("Menen", "Aster"), labels)
    }
}
