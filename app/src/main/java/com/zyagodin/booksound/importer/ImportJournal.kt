package com.zyagodin.booksound.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zyagodin.booksound.storage.DocumentStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Crash-safe record of files an import has created or moved aside in the library folder.
 * If the process dies mid-import, [recover] (run at startup) deletes the partial file, puts back
 * a book that was being replaced, and removes the job's temporary directory — so an interrupted
 * import never leaves a damaged book or garbage behind.
 */
class ImportJournal(context: Context, private val documents: DocumentStore) {

    @Serializable
    private data class Entry(
        val jobId: String,
        val partialUri: String? = null,
        val asideUri: String? = null,
        val asideOriginalName: String? = null,
    )

    private val file = File(context.noBackupFilesDir, "import-journal.json")
    val workRoot = File(context.noBackupFilesDir, "import-work")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun workDir(jobId: String): File = File(workRoot, jobId)

    fun recordPartial(jobId: String, uri: Uri) = update(jobId) { it.copy(partialUri = uri.toString()) }

    fun recordAside(jobId: String, uri: Uri, originalName: String) =
        update(jobId) { it.copy(asideUri = uri.toString(), asideOriginalName = originalName) }

    fun clearPartial(jobId: String) = update(jobId) { it.copy(partialUri = null) }

    fun remove(jobId: String) = synchronized(lock) { write(read().filterNot { it.jobId == jobId }) }

    /** Undoes leftovers of jobs that never finished. Must run before any import starts. */
    fun recover() {
        val entries = synchronized(lock) { read() }
        for (e in entries) {
            try {
                e.partialUri?.let { documents.delete(Uri.parse(it)) }
                if (e.asideUri != null && e.asideOriginalName != null) {
                    runCatching { documents.rename(Uri.parse(e.asideUri), e.asideOriginalName) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Recovery of ${e.jobId} failed", t)
            }
        }
        synchronized(lock) { write(emptyList()) }
        workRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun update(jobId: String, change: (Entry) -> Entry) = synchronized(lock) {
        val entries = read().toMutableList()
        val index = entries.indexOfFirst { it.jobId == jobId }
        if (index >= 0) entries[index] = change(entries[index]) else entries += change(Entry(jobId))
        write(entries)
    }

    private fun read(): List<Entry> = try {
        if (file.exists()) json.decodeFromString<List<Entry>>(file.readText()) else emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "Unreadable journal", t)
        emptyList()
    }

    private fun write(entries: List<Entry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(entries))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(entries))
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "ImportJournal"
    }
}
