package com.zyagodin.booksound.playback

/**
 * Tells when playing begins a new listening session, whose start is remembered so the listener can
 * go back to "where I started last time". A pause shorter than [gapMs] (a phone call, buffering, a
 * question from the next room) continues the session; a longer one, or another book, starts a new one.
 */
class ListeningSessions(private val gapMs: Long = SESSION_GAP_MS) {
    private var bookId: String? = null
    /** When the last session stopped playing; null while playing or before anything played. */
    private var stoppedAt: Long? = null

    /** [bookId] started playing at [now]; true when that begins a new session. */
    fun started(bookId: String, now: Long): Boolean {
        val stopped = stoppedAt
        val isNew = bookId != this.bookId || stopped == null || now - stopped >= gapMs
        this.bookId = bookId
        stoppedAt = null
        return isNew
    }

    /** Playing stopped at [now] (paused, buffering, ended). */
    fun stopped(now: Long) {
        if (bookId != null && stoppedAt == null) stoppedAt = now
    }

    companion object {
        const val SESSION_GAP_MS = 5 * 60_000L
    }
}
