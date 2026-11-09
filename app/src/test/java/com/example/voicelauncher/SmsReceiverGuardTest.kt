package com.example.voicelauncher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SMS sent/delivered callbacks unregister themselves when they fire, and a 30s sweep unregisters
 * anything still left. Without the guard the sweep double-unregisters and the platform logs
 * "Receiver not registered" — the carrier never answers delivered, so that fired on every send.
 */
class SmsReceiverGuardTest {

    @Test
    fun firstUnregisterRunsAndClearsTheFlag() {
        val registered = AtomicBoolean(true)
        val calls = AtomicInteger()
        assertTrue(IntentDispatcher.unregisterOnce(registered) { calls.incrementAndGet() })
        assertEquals(1, calls.get())
        assertFalse(registered.get())
    }

    @Test
    fun theThirtySecondSweepIsANoOpOnceTheCallbackAlreadyUnregistered() {
        val registered = AtomicBoolean(true)
        val calls = AtomicInteger()
        IntentDispatcher.unregisterOnce(registered) { calls.incrementAndGet() }  // sent callback
        val swept = IntentDispatcher.unregisterOnce(registered) { calls.incrementAndGet() }  // 30s later
        assertFalse(swept)
        assertEquals(1, calls.get())
    }

    @Test
    fun unregisterNeverRunsWhenTheRegistrationItselfFailed() {
        val registered = AtomicBoolean(false)
        val calls = AtomicInteger()
        assertFalse(IntentDispatcher.unregisterOnce(registered) { calls.incrementAndGet() })
        assertEquals(0, calls.get())
    }

    @Test
    fun concurrentUnregistersLetExactlyOneCallerThrough() {
        val registered = AtomicBoolean(true)
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            Thread {
                start.await()
                IntentDispatcher.unregisterOnce(registered) { calls.incrementAndGet() }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, calls.get())
    }
}
