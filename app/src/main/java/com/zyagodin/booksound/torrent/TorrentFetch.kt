package com.zyagodin.booksound.torrent

import android.util.Log
import com.zyagodin.booksound.core.torrent.TorrentLink
import com.zyagodin.booksound.core.torrent.TorrentLinks
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A .torrent file behind a web link: its bytes, a magnet link the site redirected to, or nothing. */
internal sealed interface FetchedTorrent {
    class Bytes(val bytes: ByteArray) : FetchedTorrent
    class Magnet(val link: TorrentLink.Magnet) : FetchedTorrent
    data object Failed : FetchedTorrent
}

/** Downloads the .torrent file at [url], refusing one larger than [maxBytes]. */
internal fun fetchTorrentFile(http: OkHttpClient, url: String, maxBytes: Int): FetchedTorrent = try {
    http.newCall(Request.Builder().url(url).header("Accept", "application/x-bittorrent, */*").build()).execute().use { response ->
        // Some sites redirect a download link to a magnet link, which OkHttp doesn't follow.
        response.header("Location")?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?.let { TorrentLinks.parseMagnet(it) }
            ?.let { return FetchedTorrent.Magnet(it) }
        if (!response.isSuccessful) return FetchedTorrent.Failed
        val body = response.body.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) return FetchedTorrent.Failed
        }
        FetchedTorrent.Bytes(out.toByteArray())
    }
} catch (e: IOException) {
    Log.w("TorrentFetch", "Torrent download failed", e)
    FetchedTorrent.Failed
} catch (e: IllegalArgumentException) {
    FetchedTorrent.Failed
}
