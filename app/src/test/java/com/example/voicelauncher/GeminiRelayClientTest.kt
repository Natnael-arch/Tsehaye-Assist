package com.example.voicelauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiRelayClientTest {

    @Test
    fun testInitialStateNotConnectedOrConnecting() {
        val client = GeminiRelayClient("wss://example.com/ws")
        assertFalse("Client should not be connected initially", client.isConnected())
    }
}
