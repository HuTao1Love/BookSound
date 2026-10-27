package com.zyagodin.booksound.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSessionsTest {
    private val minute = 60_000L

    @Test
    fun `the first play starts a session`() {
        assertTrue(ListeningSessions().started("a", 0))
    }

    @Test
    fun `a short pause continues the session`() {
        val sessions = ListeningSessions()
        sessions.started("a", 0)
        sessions.stopped(10 * minute)
        assertFalse(sessions.started("a", 12 * minute))
        // Measured from the last stop, not the first.
        sessions.stopped(30 * minute)
        assertFalse(sessions.started("a", 34 * minute))
    }

    @Test
    fun `a long pause starts a new session`() {
        val sessions = ListeningSessions()
        sessions.started("a", 0)
        sessions.stopped(10 * minute)
        assertTrue(sessions.started("a", 15 * minute))
    }

    @Test
    fun `another book starts a new session`() {
        val sessions = ListeningSessions()
        sessions.started("a", 0)
        sessions.stopped(minute)
        assertTrue(sessions.started("b", 2 * minute))
    }

    @Test
    fun `stopping twice keeps the first stop`() {
        val sessions = ListeningSessions()
        sessions.started("a", 0)
        sessions.stopped(minute)
        sessions.stopped(5 * minute)
        assertTrue(sessions.started("a", 7 * minute))
    }
}
