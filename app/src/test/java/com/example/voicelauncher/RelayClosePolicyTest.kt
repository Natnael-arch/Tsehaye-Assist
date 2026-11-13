package com.example.voicelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClosePolicyTest {

    private val refusals = listOf(4000, 4001, 4002, 4003, 4004, 4005)

    @Test
    fun `only the tenancy codes are refusals`() {
        refusals.forEach { assertTrue("code $it", RelayClosePolicy.isRefusal(it)) }
        listOf(1000, 1001, 1006, 1011, 3000, 4999).forEach {
            assertFalse("code $it", RelayClosePolicy.isRefusal(it))
        }
    }

    @Test
    fun `revoked paused and identity mismatch never reconnect on their own`() {
        listOf(4001, 4003, 4004).forEach {
            assertTrue("code $it", RelayClosePolicy.isTerminal(it))
            assertEquals(null, RelayClosePolicy.retryDelayMs(it))
        }
    }

    @Test
    fun `unpaired busy and unavailable retry slowly`() {
        listOf(4000, 4002, 4005).forEach {
            assertFalse("code $it", RelayClosePolicy.isTerminal(it))
            assertNotNull("code $it", RelayClosePolicy.retryDelayMs(it))
        }
        assertEquals(60_000L, RelayClosePolicy.retryDelayMs(4000))
        assertEquals(30_000L, RelayClosePolicy.retryDelayMs(4005))
    }

    @Test
    fun `busy retry outlives the relay lease so the phone can heal itself`() {
        // The relay's LEASE_TTL_MS default is 75000; retrying sooner would just be refused again.
        assertTrue(RelayClosePolicy.retryDelayMs(4002)!! > 75_000L)
    }

    @Test
    fun `every refusal has a bundled announcement`() {
        refusals.forEach { code ->
            val announcement = RelayClosePolicy.announcement(code)
            assertTrue("code $code -> ${announcement.clip}", Announcements.ALL.contains(announcement))
        }
    }

    @Test
    fun `a normal drop falls back to the connection lost clip`() {
        assertEquals(Announcements.CONNECTION_LOST, RelayClosePolicy.announcement(1006))
    }
}

class AnnouncementsTest {

    @Test
    fun `clip names are unique and resource safe`() {
        val names = Announcements.ALL.map { it.clip }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue(it, it.matches(Regex("^[a-z][a-z0-9_]*$"))) }
    }

    @Test
    fun `every announcement has an Amharic recording script`() {
        Announcements.ALL.forEach { a ->
            assertTrue("${a.clip} script is empty", a.amharic.isNotBlank())
            assertTrue("${a.clip} has no Ethiopic text", a.amharic.any { it in '\u1200'..'\u137F' })
            assertTrue("${a.clip} english gloss is empty", a.english.isNotBlank())
        }
    }

    @Test
    fun `the pilot outcomes the user must hear are all present`() {
        val clips = Announcements.ALL.map { it.clip }.toSet()
        listOf(
            "am_pending", "am_busy", "am_paused", "am_revoked", "am_identity_problem",
            "am_unavailable", "am_connection_lost", "am_nothing_pending", "am_cancelled",
            "am_call_placed", "am_call_not_placed", "am_sms_sent", "am_sms_failed", "am_sms_unconfirmed"
        ).forEach { assertTrue("$it is missing from the recording list", clips.contains(it)) }
    }

    @Test
    fun `all raw audio clips exist and are non-empty`() {
        val rawDir = java.io.File("src/main/res/raw")
        assertTrue("res/raw directory must exist", rawDir.isDirectory)
        for (a in Announcements.ALL) {
            val clipFile = java.io.File(rawDir, "${a.clip}.mp3")
            assertTrue("Missing audio file for ${a.clip}: ${clipFile.path}", clipFile.isFile)
            assertTrue("${a.clip}.mp3 is too small (${clipFile.length()} bytes)", clipFile.length() > 1000)
        }
    }
}
