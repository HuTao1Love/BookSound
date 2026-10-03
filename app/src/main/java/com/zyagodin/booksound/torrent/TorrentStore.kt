package com.zyagodin.booksound.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable storage of torrent imports: a JSON index of [TorrentRecord]s plus, per torrent, the
 * .torrent metadata, libtorrent resume data and the cover the user chose. Every change is written
 * atomically before it is published, so the state on disk always matches what the app last did.
 */
class TorrentStore(private val context: Context) {

    private val root = File(context.noBackupFilesDir, "torrents").apply { mkdirs() }
    private val indexFile = File(root, "torrents.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<TorrentRecord>> = _records

    val sessionStateFile: File get() = File(root, "session.state")
    val metadataTempDir: File get() = File(context.cacheDir, "magnet-metadata")

    fun dir(id: String): File = File(root, id)
    fun torrentFile(id: String): File = File(dir(id), "meta.torrent")
    fun resumeFile(id: String): File = File(dir(id), "resume.dat")
    fun coverFile(id: String): File = File(dir(id), "cover.img")

    /** Where a new torrent's files are downloaded: app-specific external storage when available. */
    fun newDataDir(id: String): File {
        val base = context.getExternalFilesDir(null)?.takeIf { it.canWrite() } ?: context.filesDir
        return File(base, "torrent-downloads/$id")
    }

    operator fun get(id: String): TorrentRecord? = _records.value.firstOrNull { it.id == id }

    fun add(record: TorrentRecord) = mutate { list -> list.filterNot { it.id == record.id } + record }

    /** Applies [change] to the record and returns the result, or null if it no longer exists. */
    fun update(id: String, change: (TorrentRecord) -> TorrentRecord): TorrentRecord? {
        var result: TorrentRecord? = null
        mutate { list ->
            list.map { if (it.id == id) change(it).also { r -> result = r } else it }
        }
        return result
    }

    /** Removes the record and everything stored for it, including downloaded files. */
    fun delete(id: String) {
        val record = get(id)
        mutate { list -> list.filterNot { it.id == id } }
        dir(id).deleteRecursively()
        record?.let { File(it.dataDir).deleteRecursively() }
    }

    fun writeTorrent(id: String, bytes: ByteArray) = TorrentEngine.writeAtomically(torrentFile(id), bytes)

    fun writeResume(id: String, bytes: ByteArray) {
        if (dir(id).isDirectory) TorrentEngine.writeAtomically(resumeFile(id), bytes)
    }

    private fun mutate(change: (List<TorrentRecord>) -> List<TorrentRecord>) = synchronized(lock) {
        val updated = change(_records.value)
        if (updated == _records.value) return@synchronized
        try {
            TorrentEngine.writeAtomically(indexFile, json.encodeToString(updated).toByteArray())
        } catch (e: Exception) {
            // Keep working in memory; the next successful write persists everything.
            Log.e(TAG, "Could not persist torrents", e)
        }
        _records.value = updated
    }

    private fun load(): List<TorrentRecord> = try {
        if (indexFile.isFile) json.decodeFromString<List<TorrentRecord>>(indexFile.readText()) else emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Unreadable torrent index", e)
        indexFile.renameTo(File(root, "torrents.json.unreadable"))
        emptyList()
    }

    companion object {
        private const val TAG = "TorrentStore"
    }
}
