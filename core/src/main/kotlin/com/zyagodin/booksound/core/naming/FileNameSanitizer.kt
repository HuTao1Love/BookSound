package com.zyagodin.booksound.core.naming

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Turns arbitrary metadata into a single path component that is valid on ext4, F2FS, FAT32,
 * exFAT and NTFS (so files survive being copied to an SD card or a PC). Unicode letters, including
 * Cyrillic and emoji, are preserved.
 */
object FileNameSanitizer {

    /** Max bytes per component on ext4/F2FS; FAT/exFAT limit 255 UTF-16 units, which is never smaller. */
    const val MAX_COMPONENT_BYTES = 255

    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * Sanitizes [raw]. Returns null when nothing usable remains, so callers can apply a
     * deterministic fallback. [maxBytes] limits the UTF-8 length of the result.
     */
    fun sanitize(raw: String?, maxBytes: Int = 120): String? {
        if (raw == null) return null
        var s = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp < 0x20 || cp == 0x7F || cp in 0x80..0x9F -> sb.append(' ')
                cp == ':'.code -> sb.append(" - ")
                cp == '/'.code || cp == '\\'.code || cp == '|'.code -> sb.append(" - ")
                cp == '"'.code -> sb.append('\'')
                cp == '<'.code -> sb.append('(')
                cp == '>'.code -> sb.append(')')
                cp == '?'.code || cp == '*'.code -> Unit
                // Unassigned / private-use / surrogate halves and format chars (e.g. bidi controls) are dropped.
                Character.getType(cp) == Character.UNASSIGNED.toInt() ||
                    Character.getType(cp) == Character.SURROGATE.toInt() ||
                    Character.getType(cp) == Character.PRIVATE_USE.toInt() -> Unit
                Character.getType(cp) == Character.FORMAT.toInt() && cp != 0x200D -> Unit // keep ZWJ for emoji
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        s = sb.toString()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(\s-\s)(\s*-\s)+"""), " - ")
            .trim()
        // Leading dots would create hidden files; trailing dots/spaces are invalid on Windows/FAT.
        s = s.trimStart('.', ' ', '-').trimEnd('.', ' ')
        s = truncateToBytes(s, maxBytes).trimEnd('.', ' ', '-')
        if (s.isEmpty()) return null
        val stem = s.substringBefore('.').trim().uppercase(Locale.ROOT)
        if (stem in WINDOWS_RESERVED) s = "_$s"
        return s
    }

    /** Truncates at a grapheme cluster boundary so emoji and combining marks are never split. */
    fun truncateToBytes(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(text)
        var end = 0
        var bytes = 0
        var next = it.next()
        while (next != BreakIterator.DONE) {
            val cluster = text.substring(end, next).toByteArray(Charsets.UTF_8).size
            if (bytes + cluster > maxBytes) break
            bytes += cluster
            end = next
            next = it.next()
        }
        return text.substring(0, end)
    }

    /** Key used to detect collisions on case-insensitive file systems (FAT/exFAT/NTFS). */
    fun collisionKey(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}
