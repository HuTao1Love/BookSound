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
 *
 * Each step is recorded before (or, for a URI only known afterwards, right after) it happens:
 * the book being replaced is recorded by its folder and temporary name before it is moved, so it
 * is found again even if the app dies during the move.
 */
class ImportJournal(context: Context, private val documents: DocumentStore) {

    @Serializable
    private data class Entry(
        val jobId: String,
        val partialUri: String? = null,
        /** The replaced book after the move, as journals written before [asideName] recorded it. */
        val asideUri: String? = null,
        val asideOriginalName: String? = null,
        /** Folder of the replaced book, and the temporary name it is moved aside to. */
        val treeUri: String? = null,
        val directoryId: String? = null,
        val asideName: String? = null,
        /** The new book under its final name, before the database knows it. */
        val committedUri: String? = null,
        /** The database has the new book: only the replaced one is left to delete. */
        val saved: Boolean = false,
    )

    private val file = File(context.noBackupFilesDir, "import-journal.json")
    val workRoot = File(context.noBackupFilesDir, "import-work")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun workDir(jobId: String): File = File(workRoot, jobId)

    fun recordPartial(jobId: String, uri: Uri) = update(jobId) { it.copy(partialUri = uri.toString()) }

    /** Before the book at [originalName] in [directoryId] is renamed to [asideName]. */
    fun recordAside(jobId: String, treeUri: Uri, directoryId: String, asideName: String, originalName: String) = update(jobId) {
        it.copy(treeUri = treeUri.toString(), directoryId = directoryId, asideName = asideName, asideOriginalName = originalName)
    }

    /** The partial file now has its final name, [uri]. */
    fun recordCommitted(jobId: String, uri: Uri) = update(jobId) { it.copy(partialUri = null, committedUri = uri.toString()) }

    /** The new book is in the database. */
    fun recordSaved(jobId: String) = update(jobId) { it.copy(saved = true) }

    fun remove(jobId: String) = synchronized(lock) { write(read().filterNot { it.jobId == jobId }) }

    /** Undoes leftovers of jobs that never finished. Must run before any import starts. */
    fun recover() {
        val entries = synchronized(lock) { read() }
        for (e in entries) {
            try {
                val aside = asideOf(e)
                if (e.saved) {
                    // The new book is in the library: the one it replaced is no longer needed.
                    aside?.let { documents.delete(it) }
                } else {
                    e.partialUri?.let { documents.delete(Uri.parse(it)) }
                    // Before the replaced book takes its name back.
                    e.committedUri?.let { documents.delete(Uri.parse(it)) }
                    if (aside != null && e.asideOriginalName != null) {
                        runCatching { documents.rename(aside, e.asideOriginalName) }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Recovery of ${e.jobId} failed", t)
            }
        }
        synchronized(lock) { write(emptyList()) }
        workRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** The replaced book under its temporary name, if it was moved aside. */
    private fun asideOf(e: Entry): Uri? {
        e.asideUri?.let { return Uri.parse(it) }
        val tree = e.treeUri ?: return null
        val directory = e.directoryId ?: return null
        val name = e.asideName ?: return null
        return documents.children(Uri.parse(tree), directory).firstOrNull { !it.isDirectory && it.name == name }?.uri
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
