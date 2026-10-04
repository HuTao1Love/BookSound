package com.zyagodin.booksound.importer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A semaphore whose number of permits follows [limit] (a setting), even while it is in use: a
 * lower limit lets running holders finish and admits new ones only below it.
 */
class AdjustableGate(private val limit: StateFlow<Int>) {
    private val inUse = MutableStateFlow(0)
    private val lock = Mutex()

    suspend fun acquire() = take(1) { used, max -> used < max }

    fun release() = inUse.update { it - 1 }

    /** Waits until nobody holds a permit, then keeps everyone else out until [releaseAll]. */
    suspend fun acquireAll() = take(ALL) { used, _ -> used == 0 }

    fun releaseAll() = inUse.update { it - ALL }

    private suspend fun take(amount: Int, fits: (used: Int, max: Int) -> Boolean) {
        while (true) {
            lock.withLock {
                if (fits(inUse.value, limit.value.coerceAtLeast(1))) {
                    inUse.update { it + amount }
                    return
                }
            }
            combine(inUse, limit) { used, max -> fits(used, max.coerceAtLeast(1)) }.first { it }
        }
    }

    private companion object {
        /** Counts as every permit at once, whatever the limit. */
        const val ALL = 1_000_000
    }
}
