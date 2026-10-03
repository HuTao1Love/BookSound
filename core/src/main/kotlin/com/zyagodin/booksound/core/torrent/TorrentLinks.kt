package com.zyagodin.booksound.core.torrent

import java.net.URI
import java.net.URLDecoder

/** A torrent reference the user typed or pasted. */
sealed interface TorrentLink {
    /**
     * A magnet link. [infoHash] is the lowercase hex BitTorrent v1 info-hash ("btih"), or
     * "btmh:<hex>" for v2-only links; it identifies the torrent before its metadata is known.
     */
    data class Magnet(val uri: String, val infoHash: String, val displayName: String?) : TorrentLink

    /** An http(s) URL that should return a .torrent file. */
    data class Web(val url: String) : TorrentLink
}

object TorrentLinks {

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32_32 = Regex("^[A-Za-z2-7]{32}$")
    private val MULTIHASH = Regex("^1220[0-9a-fA-F]{64}$")

    /** Parses a magnet link, an http(s) .torrent URL or a bare 40-character info-hash. */
    fun parse(text: String): TorrentLink? {
        val value = text.trim().removeSurrounding("<", ">").trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        if (HEX40.matches(value)) {
            val hash = value.lowercase()
            return TorrentLink.Magnet("magnet:?xt=urn:btih:$hash", hash, null)
        }
        if (value.startsWith("magnet:", ignoreCase = true)) return parseMagnet(value)
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) return TorrentLink.Web(value)
        return null
    }

    fun parseMagnet(uri: String): TorrentLink.Magnet? {
        val query = uri.substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
        var v1: String? = null
        var v2: String? = null
        var name: String? = null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').lowercase()
            val raw = pair.substringAfter('=', "")
            when {
                key == "xt" || key.startsWith("xt.") -> {
                    val xt = decode(raw) ?: continue
                    when {
                        xt.startsWith("urn:btih:", ignoreCase = true) -> v1 = v1 ?: normalizeBtih(xt.substring(9))
                        xt.startsWith("urn:btmh:", ignoreCase = true) ->
                            v2 = v2 ?: xt.substring(9).takeIf { MULTIHASH.matches(it) }?.lowercase()
                    }
                }
                key == "dn" -> name = decode(raw)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        val hash = v1 ?: v2?.let { "btmh:$it" } ?: return null
        return TorrentLink.Magnet(uri, hash, name)
    }

    private fun normalizeBtih(value: String): String? = when {
        HEX40.matches(value) -> value.lowercase()
        BASE32_32.matches(value) -> base32ToHex(value.uppercase())
        else -> null
    }

    private fun base32ToHex(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder(40)
        var buffer = 0L
        var bits = 0
        for (c in value) {
            buffer = (buffer shl 5) or alphabet.indexOf(c).toLong()
            bits += 5
            while (bits >= 4) {
                bits -= 4
                out.append("0123456789abcdef"[((buffer shr bits) and 0xF).toInt()])
            }
        }
        return out.toString()
    }

    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
}
