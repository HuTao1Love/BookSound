package com.zyagodin.booksound.importer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdjustableGateTest {

    @Test
    fun `admits up to the limit and follows a changed limit`() = runTest {
        val limit = MutableStateFlow(2)
        val gate = AdjustableGate(limit)
        var running = 0
        val jobs = List(4) { launch { gate.acquire(); running++ } }
        runCurrent()
        assertEquals(2, running)

        // A higher limit admits more right away…
        limit.value = 3
        runCurrent()
        assertEquals(3, running)

        // …a lower one lets the running ones finish and admits the next only below it.
        limit.value = 1
        gate.release()
        gate.release()
        runCurrent()
        assertEquals(3, running)
        gate.release()
        runCurrent()
        assertEquals(4, running)
        jobs.forEach { it.join() }
    }

    @Test
    fun `acquireAll waits for everyone and keeps everyone out`() = runTest {
        val gate = AdjustableGate(MutableStateFlow(4))
        gate.acquire()
        var exclusive = false
        launch { gate.acquireAll(); exclusive = true }
        runCurrent()
        assertFalse(exclusive)
        gate.release()
        runCurrent()
        assertTrue(exclusive)

        var other = false
        launch { gate.acquire(); other = true }
        runCurrent()
        assertFalse(other)
        gate.releaseAll()
        runCurrent()
        assertTrue(other)
    }
}
