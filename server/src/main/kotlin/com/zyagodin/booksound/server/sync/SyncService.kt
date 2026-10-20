package com.zyagodin.booksound.server.sync

import com.zyagodin.booksound.api.ChangeSetDto
import com.zyagodin.booksound.api.EventDto
import com.zyagodin.booksound.api.EventTypes
import com.zyagodin.booksound.api.PlaybackDto
import com.zyagodin.booksound.api.PreferencesDto
import com.zyagodin.booksound.api.PullResponse
import com.zyagodin.booksound.api.PushResponse
import com.zyagodin.booksound.api.StampDto
import com.zyagodin.booksound.api.toDto
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.sync.PlaybackConflictResolver
import com.zyagodin.booksound.server.events.Events
import com.zyagodin.booksound.server.library.LibraryException
import com.zyagodin.booksound.server.library.LibraryService
import org.slf4j.LoggerFactory

/**
 * The change feed behind `SyncBackend` on the phone and watch, and the web app's progress saving.
 *
 * Every change to a book, a position or preferences takes the next number of one server-wide sequence;
 * a client pulls everything above the highest number it has seen. Books are shared by all users,
 * positions and preferences belong to one.
 */
class SyncService(
    private val library: LibraryService,
    private val events: Events,
    private val resolver: PlaybackConflictResolver = PlaybackConflictResolver(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(SyncService::class.java)
    private val repo get() = library.repo

    fun pull(userId: String, since: Long, limit: Int): PullResponse {
        val size = limit.coerceIn(1, MAX_PAGE)
        // Take up to one page from each source, then keep the lowest numbers so the cursor never skips a change.
        val books = repo.bookChanges(since, size + 1).map { it.seq to it }
        val playback = repo.playbackChanges(userId, since, size + 1).map { it.seq to it }
        val prefs = repo.preferences(userId)?.takeIf { it.second > since }
        val all = (books.map { it.first } + playback.map { it.first } + listOfNotNull(prefs?.second)).sorted()
        val taken = all.take(size)
        val cursor = taken.lastOrNull() ?: since
        val hasMore = all.size > size
        val chapters = repo.allChapters()
        return PullResponse(
            changes = ChangeSetDto(
                books = books.filter { it.first <= cursor }.map { (_, b) -> b.toDto(chapters[b.id].orEmpty()) },
                playback = playback.filter { it.first <= cursor }.map { it.second.dto },
                preferences = prefs?.takeIf { it.second <= cursor }?.first,
            ),
            cursor = cursor.toString(),
            hasMore = hasMore,
        )
    }

    fun push(userId: String, device: String, changes: ChangeSetDto): PushResponse {
        val accepted = LinkedHashMap<String, Long>()
        val conflicts = mutableListOf<String>()
        for (p in changes.playback) {
            if (repo.book(p.bookId) == null) continue
            savePlayback(userId, p)?.let { accepted["playback:${p.bookId}"] = it.stamp.revision }
        }
        // Metadata edits made on a device are written into the shared file. A device deleting its copy of a
        // book removes nothing here: the library is shared, only an admin deletes from it.
        for (b in changes.books) {
            val current = repo.book(b.id) ?: continue
            if (b.stamp.deleted || b.metadata == current.metadata) continue
            if (b.stamp.updatedAt < current.updatedAt) {
                conflicts += b.id
                continue
            }
            try {
                val saved = library.updateMetadata(b.id, b.metadata, null, device)
                accepted[b.id] = saved.stamp.revision
            } catch (e: LibraryException) {
                log.warn("Metadata of {} from {} not applied: {}", b.id, device, e.message)
                conflicts += b.id
            }
        }
        changes.preferences?.let { incoming ->
            val current = repo.preferences(userId)?.first
            if (current == null || incoming.stamp.updatedAt >= current.stamp.updatedAt) {
                val stored = incoming.copy(stamp = incoming.stamp.copy(revision = maxOf(incoming.stamp.revision, (current?.stamp?.revision ?: 0) + 1)))
                repo.savePreferences(userId, stored)
                accepted["preferences"] = stored.stamp.revision
            }
        }
        return PushResponse(accepted, conflicts)
    }

    /**
     * Merges a position into the user's stored one; returns what is stored now, or null when the incoming one lost.
     * A newer save from the device that made the stored one always wins (a seek back is not a conflict); between
     * devices the shared conflict rules apply.
     */
    fun savePlayback(userId: String, incoming: PlaybackDto): PlaybackDto? {
        val current = repo.playback(userId, incoming.bookId)
        val winner = when {
            current == null -> incoming
            incoming.stamp.updatedBy == current.stamp.updatedBy ->
                if (incoming.stamp.updatedAt >= current.stamp.updatedAt) incoming else return null
            else -> resolver.resolve(current.toModel(), incoming.toModel(), null).winner.toDto()
        }
        if (current != null && winner == current) return null
        val stored = winner.copy(
            stamp = StampDto(
                revision = maxOf(winner.stamp.revision, (current?.stamp?.revision ?: 0) + 1),
                updatedAt = winner.stamp.updatedAt,
                updatedBy = winner.stamp.updatedBy,
                deleted = false,
            ),
        )
        repo.savePlayback(userId, stored)
        return stored
    }

    fun preferences(userId: String): PreferencesDto = repo.preferences(userId)?.first ?: PreferencesDto(
        defaultSpeed = 1f,
        sleepTimerMinutes = 30,
        skipBackSeconds = 15,
        skipForwardSeconds = 30,
        stamp = StampDto(0, 0, library.serverDevice),
    )

    fun savePreferences(userId: String, prefs: PreferencesDto, device: String): PreferencesDto {
        val current = repo.preferences(userId)?.first
        val stored = prefs.copy(stamp = StampDto((current?.stamp?.revision ?: 0) + 1, clock(), device))
        repo.savePreferences(userId, stored)
        events.emit(EventDto(EventTypes.LIBRARY))
        return stored
    }

    companion object {
        const val MAX_PAGE = 500
    }
}
