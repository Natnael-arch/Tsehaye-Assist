package com.example.voicelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A confirm/cancel tool call can reach the app after the pending action is already gone — the user
 * cancelled first, or the 10s window closed. Answering "OK" in that case tells Gemini the call or
 * text went through, and it reads a success the phone never performed.
 */
class PendingActionResponseTest {

    @Test
    fun actedConfirmReportsOk() {
        assertEquals("OK", IntentDispatcher.pendingActionResult(acted = true))
    }

    @Test
    fun staleConfirmReportsNothingPending() {
        assertEquals("NOTHING_PENDING", IntentDispatcher.pendingActionResult(acted = false))
    }
}
