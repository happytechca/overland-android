package io.github.happytechca.overland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun readyUntilFirstFailure() {
        assertTrue(Backoff().ready(0))
    }

    @Test
    fun doublesUpToTheCap() {
        val backoff = Backoff(baseMs = 60_000, maxMs = 15 * 60_000)
        val waits = (1..7).map {
            backoff.onFailure(0)
            backoff.retryAtMs
        }
        assertEquals(listOf(1, 2, 4, 8, 15, 15, 15).map { it * 60_000L }, waits)
    }

    @Test
    fun waitsThenRetries() {
        val backoff = Backoff(baseMs = 60_000)
        backoff.onFailure(1_000)
        assertFalse(backoff.ready(60_999))
        assertTrue(backoff.ready(61_000))
    }

    @Test
    fun resetAfterSuccess() {
        val backoff = Backoff(baseMs = 60_000)
        repeat(3) { backoff.onFailure(0) }
        backoff.reset()
        assertTrue(backoff.ready(0))
        backoff.onFailure(0)
        assertEquals(60_000L, backoff.retryAtMs)
    }

    @Test
    fun manyFailuresDoNotOverflow() {
        val backoff = Backoff(baseMs = 60_000, maxMs = 15 * 60_000)
        repeat(100) { backoff.onFailure(0) }
        assertEquals(15 * 60_000L, backoff.retryAtMs)
    }
}
