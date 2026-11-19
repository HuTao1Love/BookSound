package com.zyagodin.booksound.watch.sync

import com.zyagodin.booksound.core.wear.WatchBookHeader
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchResult
import kotlinx.coroutines.CancellationException
import java.io.File

/** Only the phone transfer's temporary files; completed books and server downloads are separate. */
internal class IncomingBookFiles(private val directory: File) {
    fun header(id: String) = File(directory, "$id.header")
    fun part(id: String) = File(directory, "$id.part")
    fun done(id: String) = File(directory, "$id.done")

    fun discard(id: String) {
        header(id).delete()
        done(id).delete()
        part(id).delete()
    }

    suspend fun finish(id: String, save: suspend (WatchBookHeader, File) -> Unit): WatchResult? {
        if (!header(id).exists() || !done(id).exists()) return null
        return try {
            val header = WatchCodec.decodeHeader(header(id).readBytes())
            if (part(id).length() != header.fileSize) WatchResult.Failed("size")
            else {
                save(header, part(id))
                WatchResult.Saved
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WatchResult.Failed(e.javaClass.simpleName)
        } finally {
            discard(id)
        }
    }
}
