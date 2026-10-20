package io.relimus.zflow.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RequestGateTest {
    @Test fun rejectedRecentDuplicateDoesNotPoisonInFlight() {
        val gate = RequestGate()
        assertTrue(gate.acquire("pkg|0", 0, 1200))
        gate.complete("pkg|0")
        assertFalse(gate.acquire("pkg|0", 100, 1200))
        assertTrue(gate.acquire("pkg|0", 1200, 1200))
    }

    @Test fun inFlightBlocksEvenAfterTimeWindow() {
        val gate = RequestGate()
        assertTrue(gate.acquire("pkg|0", 0, 1200))
        assertFalse(gate.acquire("pkg|0", 5000, 1200))
        gate.complete("pkg|0")
        assertTrue(gate.acquire("pkg|0", 5000, 1200))
    }

    @Test fun rejectedQueueEntryCanRetryImmediately() {
        val gate = RequestGate()
        assertTrue(gate.acquire("pkg|0", 0, 1200))
        gate.complete("pkg|0", retryable = true)
        assertTrue(gate.acquire("pkg|0", 1, 1200))
    }

    @Test fun userProfilesAreSeparate() {
        val gate = RequestGate()
        assertTrue(gate.acquire(RequestGate.key("pkg", 0), 0, 1200))
        assertTrue(gate.acquire(RequestGate.key("pkg", 10), 0, 1200))
    }

    @Test fun concurrentDuplicatesHaveOneWinner() {
        val gate = RequestGate()
        val start = CountDownLatch(1)
        val done = CountDownLatch(32)
        val winners = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(32) {
                executor.execute {
                    try {
                        start.await()
                        if (gate.acquire("pkg|0", 0, 1200)) winners.incrementAndGet()
                    } finally { done.countDown() }
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, winners.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun recentHistoryIsBoundedWithoutLosingInFlightProtection() {
        val gate = RequestGate(2)
        assertTrue(gate.acquire("first", 0, 1200))
        repeat(10) { assertTrue(gate.acquire("pkg$it", 0, 1200)); gate.complete("pkg$it") }
        assertFalse(gate.acquire("first", 5000, 1200))
    }
}
