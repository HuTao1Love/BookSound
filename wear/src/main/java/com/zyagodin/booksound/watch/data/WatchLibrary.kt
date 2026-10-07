package com.zyagodin.booksound.watch.data

import android.content.Context
import android.util.Log
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.PlaybackConflictResolver
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.wear.WatchBookHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A book stored on the watch. */
data class WatchBook(
    val id: String,
    val metadata: BookMetadata,
    val durationMs: Long,
    val chapters: List<Chapter>,
    val fileSize: Long,
    val fileRevision: Int,
    val addedAt: Long,
    /** Where the listener is, on this watch or as last heard from the phone. */
    val playback: PlaybackRecord?,
) {
    val positionMs: Long get() = playback?.positionMs ?: 0L
    val finished: Boolean get() = playback?.finished == true
    val progress: Float get() = if (finished) 1f else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The books on the watch: audio files and covers in app storage, the list itself in a small JSON
 * file. A watch holds a handful of books, so the whole list is rewritten on every change.
 */
class WatchLibrary(context: Context, private val device: () -> DeviceId) {
    private val root = context.filesDir
    private val booksDir = File(root, "books").apply { mkdirs() }
    private val coversDir = File(root, "covers").apply { mkdirs() }
    private val indexFile = File(root, "library.json")
    private val mutex = Mutex()
    private val resolver = PlaybackConflictResolver()

    private val _books = MutableStateFlow(load())
    /** Most recently listened first. */
    val books: StateFlow<List<WatchBook>> = _books

    fun book(id: String): WatchBook? = _books.value.firstOrNull { it.id == id }

    fun audioFile(id: String) = File(booksDir, "$id.m4b")
    fun coverFile(id: String) = File(coversDir, "$id.jpg")

    /** Free space for new books. */
    fun freeBytes(): Long = root.usableSpace

    /** Keeps a received book; [audio] is moved into the library. A book sent again is replaced. */
    suspend fun add(header: WatchBookHeader, audio: File) = edit { books ->
        val id = header.id.value
        if (!audio.renameTo(audioFile(id))) throw IOException("Can't move $audio")
        val cover = coverFile(id)
        header.cover?.let { cover.writeBytes(it) } ?: cover.delete()
        val old = books.firstOrNull { it.id == id }
        val here = old?.playback
        val sent = header.playback
        val playback = if (here != null && sent != null) resolver.resolve(here, sent, base = null).winner else sent ?: here
        val book = WatchBook(
            id = id,
            metadata = header.metadata,
            durationMs = header.durationMs,
            chapters = header.chapters,
            fileSize = header.fileSize,
            fileRevision = header.fileRevision,
            addedAt = old?.addedAt ?: System.currentTimeMillis(),
            playback = playback,
        )
        books.filterNot { it.id == id } + book
    }

    suspend fun remove(id: String) = edit { books ->
        audioFile(id).delete()
        coverFile(id).delete()
        books.filterNot { it.id == id }
    }

    /** Saves the position listened to here; returns the new record to publish. */
    suspend fun savePosition(id: String, positionMs: Long, speed: Float, finished: Boolean? = null): PlaybackRecord? {
        var saved: PlaybackRecord? = null
        edit { books ->
            books.map { book ->
                if (book.id != id) return@map book
                val now = System.currentTimeMillis()
                val old = book.playback
                val record = PlaybackRecord(
                    bookId = BookId(id),
                    positionMs = positionMs.coerceAtLeast(0),
                    speed = speed,
                    finished = finished ?: old?.finished ?: false,
                    lastPlayedAt = now,
                    stamp = SyncStamp((old?.stamp?.revision ?: 0) + 1, now, device(), dirty = false),
                )
                saved = record
                book.copy(playback = record)
            }
        }
        return saved
    }

    /** Merges positions from the phone; returns those that changed a book here. */
    suspend fun mergeRemote(records: List<PlaybackRecord>): List<PlaybackRecord> {
        val changed = mutableListOf<PlaybackRecord>()
        edit { books ->
            books.map { book ->
                val remote = records.lastOrNull { it.bookId.value == book.id } ?: return@map book
                val local = book.playback
                val winner = if (local == null) remote else resolver.resolve(local, remote, base = null).winner
                if (winner == local) book else book.copy(playback = winner).also { changed += winner }
            }
        }
        return changed
    }

    private suspend fun edit(change: (List<WatchBook>) -> List<WatchBook>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = change(_books.value).sortedByDescending { it.playback?.lastPlayedAt ?: it.addedAt }
            if (updated != _books.value) {
                save(updated)
                _books.value = updated
            }
        }
    }

    // ---------------------------------------------------------------- JSON

    private fun load(): List<WatchBook> = try {
        if (!indexFile.exists()) emptyList() else {
            val array = JSONArray(indexFile.readText())
            List(array.length()) { readBook(array.getJSONObject(it)) }
                .filter { audioFile(it.id).exists() }
                .sortedByDescending { it.playback?.lastPlayedAt ?: it.addedAt }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Library index unreadable", e)
        emptyList()
    }

    private fun save(books: List<WatchBook>) {
        val array = JSONArray()
        books.forEach { array.put(writeBook(it)) }
        val tmp = File(root, "library.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(indexFile)) throw IOException("Can't save the library")
    }

    private fun writeBook(b: WatchBook) = JSONObject().apply {
        put("id", b.id)
        put("title", b.metadata.title)
        putOpt("author", b.metadata.author)
        putOpt("narrator", b.metadata.narrator)
        putOpt("series", b.metadata.series)
        putOpt("seriesIndex", b.metadata.seriesIndex)
        put("durationMs", b.durationMs)
        put("fileSize", b.fileSize)
        put("fileRevision", b.fileRevision)
        put("addedAt", b.addedAt)
        put("chapters", JSONArray().apply {
            b.chapters.forEach { c -> put(JSONObject().put("index", c.index).put("title", c.title).put("start", c.startMs).put("end", c.endMs)) }
        })
        b.playback?.let { p ->
            put("playback", JSONObject().apply {
                put("position", p.positionMs)
                put("speed", p.speed.toDouble())
                put("finished", p.finished)
                putOpt("lastPlayedAt", p.lastPlayedAt)
                put("revision", p.stamp.revision)
                put("updatedAt", p.stamp.updatedAt)
                put("updatedBy", p.stamp.updatedBy.value)
            })
        }
    }

    private fun readBook(o: JSONObject): WatchBook {
        val id = o.getString("id")
        val chapters = o.getJSONArray("chapters").let { a ->
            List(a.length()) { a.getJSONObject(it).run { Chapter(getInt("index"), getString("title"), getLong("start"), getLong("end")) } }
        }
        val playback = o.optJSONObject("playback")?.run {
            PlaybackRecord(
                bookId = BookId(id),
                positionMs = getLong("position"),
                speed = getDouble("speed").toFloat(),
                finished = getBoolean("finished"),
                lastPlayedAt = if (has("lastPlayedAt")) getLong("lastPlayedAt") else null,
                stamp = SyncStamp(getLong("revision"), getLong("updatedAt"), DeviceId(getString("updatedBy")), dirty = false),
            )
        }
        return WatchBook(
            id = id,
            metadata = BookMetadata(
                title = o.getString("title"),
                author = o.optStringOrNull("author"),
                narrator = o.optStringOrNull("narrator"),
                series = o.optStringOrNull("series"),
                seriesIndex = o.optStringOrNull("seriesIndex"),
            ),
            durationMs = o.getLong("durationMs"),
            chapters = chapters,
            fileSize = o.getLong("fileSize"),
            fileRevision = o.getInt("fileRevision"),
            addedAt = o.getLong("addedAt"),
            playback = playback,
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

    companion object {
        private const val TAG = "WatchLibrary"
    }
}
