package com.zyagodin.booksound.core.sync

import kotlin.math.abs

/**
 * Resolves conflicting playback positions coming from several devices.
 *
 * Rules (three-way, using the last state both sides agreed on as [base]):
 * 1. If only one side changed since the base, that side wins.
 * 2. If both changed, the most recent update wins, unless both updates happened within
 *    [clockSkewToleranceMs] of each other; then the furthest position wins (users rarely
 *    deliberately go backwards on two devices at once).
 * 3. The losing position is reported as [Resolution.alternative] when it differs noticeably, so the
 *    UI can offer "Continue from 2:31:10 (Pixel)" instead of silently discarding it.
 */
class PlaybackConflictResolver(
    private val clockSkewToleranceMs: Long = 2 * 60_000L,
    private val significantDifferenceMs: Long = 30_000L,
) {
    data class Resolution(val winner: PlaybackRecord, val alternative: PlaybackRecord?)

    fun resolve(local: PlaybackRecord, remote: PlaybackRecord, base: PlaybackRecord?): Resolution {
        require(local.bookId == remote.bookId) { "Different books" }
        val localChanged = base == null || local.stamp.revision != base.stamp.revision || local.positionMs != base.positionMs
        val remoteChanged = base == null || remote.stamp.revision != base.stamp.revision || remote.positionMs != base.positionMs
        val winner = when {
            localChanged && !remoteChanged -> local
            remoteChanged && !localChanged -> remote
            abs(local.stamp.updatedAt - remote.stamp.updatedAt) <= clockSkewToleranceMs ->
                if (local.positionMs >= remote.positionMs) local else remote
            local.stamp.updatedAt > remote.stamp.updatedAt -> local
            else -> remote
        }
        val loser = if (winner === local) remote else local
        val alternative = loser.takeIf {
            localChanged && remoteChanged && abs(it.positionMs - winner.positionMs) >= significantDifferenceMs
        }
        // "finished" is sticky unless the winner explicitly restarted the book more recently.
        val finished = winner.finished || (loser.finished && loser.stamp.updatedAt >= winner.stamp.updatedAt)
        return Resolution(winner.copy(finished = finished), alternative)
    }
}
