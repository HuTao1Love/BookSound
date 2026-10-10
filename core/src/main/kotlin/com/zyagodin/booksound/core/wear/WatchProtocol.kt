package com.zyagodin.booksound.core.wear

import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.PlaybackRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/*
 * What the phone and the watch app say to each other over the Wear OS Data Layer.
 *
 * Sending a book: the phone sends a [WatchBookHeader] as a message to HEADER_PREFIX + id, then
 * streams the audio file through a channel opened on BOOK_PREFIX + id. The watch keeps the book
 * once it has both, and answers on RESULT_PREFIX + id. The two may arrive in either order.
 *
 * Listening positions: each device publishes its [PlaybackRecord] for a book as a data item at
 * POSITION_PREFIX + id; the other side merges it with PlaybackConflictResolver.
 *
 * Updating the watch app: the phone streams the new APK through a channel opened on
 * APK_PREFIX + version; the watch answers on APK_RESULT_PREFIX + version and asks the user to
 * install it.
 *
 * Playing on the phone from the watch: the phone publishes the book it would resume as a
 * [PhoneBook] data item at PHONE_BOOK_PATH; the watch sends PHONE_COMMAND_PREFIX + "play" or
 * "pause" as a message to the phone that declares PHONE_CAPABILITY.
 */
object WatchPaths {
    /** Capability the watch app declares, so the phone finds watches that have it installed. */
    const val WATCH_CAPABILITY = "booksound_watch"
    const val HEADER_PREFIX = "/booksound/header/"
    const val BOOK_PREFIX = "/booksound/book/"
    const val RESULT_PREFIX = "/booksound/result/"
    const val POSITION_PREFIX = "/booksound/position/"

    /** Data item with the watch app's version name, so the phone can tell when it is outdated. */
    const val WATCH_INFO_PATH = "/booksound/watch-info"
    /** Channel carrying a new watch app APK; the last path segment is its version. */
    const val APK_PREFIX = "/booksound/apk/"
    const val APK_RESULT_PREFIX = "/booksound/apk-result/"

    /** Capability the phone app declares, so the watch finds a reachable phone with BookSound. */
    const val PHONE_CAPABILITY = "booksound_phone"
    /** Data item with the phone's [PhoneBook]. */
    const val PHONE_BOOK_PATH = "/booksound/phone-book"
    /** Message asking the phone to play or pause; the last path segment is the command. */
    const val PHONE_COMMAND_PREFIX = "/booksound/phone-command/"
    const val COMMAND_PLAY = "play"
    const val COMMAND_PAUSE = "pause"

    /** The book id at the end of a path that starts with [prefix], or null for another path. */
    fun bookId(path: String, prefix: String): BookId? =
        path.takeIf { it.startsWith(prefix) }?.substring(prefix.length)?.takeIf { it.isNotEmpty() && '/' !in it }?.let(::BookId)
}

/** Everything the watch needs to know about a book besides its audio. */
data class WatchBookHeader(
    val id: BookId,
    val metadata: BookMetadata,
    val durationMs: Long,
    val chapters: List<Chapter>,
    /** Exact size of the audio stream that follows, to tell a complete transfer from a cut one. */
    val fileSize: Long,
    val fileRevision: Int,
    /** Where the phone is in the book, so the watch continues from there. */
    val playback: PlaybackRecord?,
    /** Small JPEG cover, or null. */
    val cover: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is WatchBookHeader && other.id == id && other.metadata == metadata && other.durationMs == durationMs &&
            other.chapters == chapters && other.fileSize == fileSize && other.fileRevision == fileRevision &&
            other.playback == playback && (other.cover?.contentEquals(cover) ?: (cover == null))

    override fun hashCode(): Int = id.hashCode() * 31 + fileRevision
}

/** The book the phone plays or would resume, as the watch shows it. */
data class PhoneBook(
    val id: BookId,
    val title: String,
    val author: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/** The watch's answer to a sent book. */
sealed interface WatchResult {
    data object Saved : WatchResult
    data object NoSpace : WatchResult
    data class Failed(val reason: String) : WatchResult

    fun encode(): ByteArray = when (this) {
        Saved -> "saved"
        NoSpace -> "no_space"
        is Failed -> "failed:$reason"
    }.toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): WatchResult = when (val text = bytes.toString(Charsets.UTF_8)) {
            "saved" -> Saved
            "no_space" -> NoSpace
            else -> Failed(text.removePrefix("failed:"))
        }
    }
}

/** Binary encoding of the protocol's payloads. Versioned so either app can refuse what it can't read. */
object WatchCodec {
    private const val HEADER_MAGIC = 0x42535748 // "BSWH"
    private const val POSITION_MAGIC = 0x42535750 // "BSWP"
    private const val PHONE_BOOK_MAGIC = 0x4253504E // "BSPN"
    private const val VERSION = 1

    fun encodeHeader(header: WatchBookHeader): ByteArray = write(HEADER_MAGIC) {
        writeString(header.id.value)
        writeMetadata(header.metadata)
        writeLong(header.durationMs)
        writeInt(header.chapters.size)
        header.chapters.forEach {
            writeInt(it.index)
            writeString(it.title)
            writeLong(it.startMs)
            writeLong(it.endMs)
        }
        writeLong(header.fileSize)
        writeInt(header.fileRevision)
        writeBoolean(header.playback != null)
        header.playback?.let { writePlayback(it) }
        writeInt(header.cover?.size ?: -1)
        header.cover?.let { write(it) }
    }

    fun decodeHeader(bytes: ByteArray): WatchBookHeader = read(bytes, HEADER_MAGIC) {
        val id = BookId(readString())
        val metadata = readMetadata()
        val duration = readLong()
        val chapters = List(readCount()) { Chapter(readInt(), readString(), readLong(), readLong()) }
        val size = readLong()
        val revision = readInt()
        val playback = if (readBoolean()) readPlayback() else null
        val coverSize = readInt()
        val cover = if (coverSize < 0) null else ByteArray(coverSize).also { readFully(it) }
        WatchBookHeader(id, metadata, duration, chapters, size, revision, playback, cover)
    }

    fun encodePosition(record: PlaybackRecord): ByteArray = write(POSITION_MAGIC) { writePlayback(record) }

    fun decodePosition(bytes: ByteArray): PlaybackRecord = read(bytes, POSITION_MAGIC) { readPlayback() }

    fun encodePhoneBook(book: PhoneBook): ByteArray = write(PHONE_BOOK_MAGIC) {
        writeString(book.id.value)
        writeString(book.title)
        writeNullable(book.author)
        writeBoolean(book.playing)
        writeLong(book.positionMs)
        writeLong(book.durationMs)
    }

    fun decodePhoneBook(bytes: ByteArray): PhoneBook = read(bytes, PHONE_BOOK_MAGIC) {
        PhoneBook(BookId(readString()), readString(), readNullable(), readBoolean(), readLong(), readLong())
    }

    // ---------------------------------------------------------------- helpers

    private inline fun write(magic: Int, block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(magic)
            it.writeInt(VERSION)
            it.block()
        }
        return bytes.toByteArray()
    }

    private inline fun <T> read(bytes: ByteArray, magic: Int, block: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            if (it.readInt() != magic) throw IOException("Not a BookSound payload")
            val version = it.readInt()
            if (version != VERSION) throw IOException("Unsupported version $version")
            it.block()
        }

    private fun DataOutputStream.writePlayback(r: PlaybackRecord) {
        writeString(r.bookId.value)
        writeLong(r.positionMs)
        writeFloat(r.speed)
        writeBoolean(r.finished)
        writeLong(r.lastPlayedAt ?: -1)
        writeLong(r.stamp.revision)
        writeLong(r.stamp.updatedAt)
        writeString(r.stamp.updatedBy.value)
    }

    private fun DataInputStream.readPlayback(): PlaybackRecord {
        val id = BookId(readString())
        val position = readLong()
        val speed = readFloat()
        val finished = readBoolean()
        val lastPlayed = readLong().takeIf { it >= 0 }
        val stamp = SyncStamp(revision = readLong(), updatedAt = readLong(), updatedBy = DeviceId(readString()), dirty = false)
        return PlaybackRecord(id, position, speed, finished, lastPlayed, stamp)
    }

    private fun DataOutputStream.writeMetadata(m: BookMetadata) {
        writeString(m.title)
        listOf(m.author, m.narrator, m.series, m.seriesIndex, m.year, m.genre, m.description, m.language).forEach { writeNullable(it) }
    }

    private fun DataInputStream.readMetadata() = BookMetadata(
        title = readString(),
        author = readNullable(),
        narrator = readNullable(),
        series = readNullable(),
        seriesIndex = readNullable(),
        year = readNullable(),
        genre = readNullable(),
        description = readNullable(),
        language = readNullable(),
    )

    /** UTF-8 with an int length: writeUTF() is limited to 64 KB, a description may be longer. */
    private fun DataOutputStream.writeString(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.readString(): String = ByteArray(readCount()).also { readFully(it) }.toString(Charsets.UTF_8)

    private fun DataOutputStream.writeNullable(s: String?) {
        writeBoolean(s != null)
        s?.let { writeString(it) }
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readString() else null

    private fun DataInputStream.readCount(): Int = readInt().also { if (it !in 0..MAX_COUNT) throw IOException("Corrupt payload") }

    private const val MAX_COUNT = 16 * 1024 * 1024
}
