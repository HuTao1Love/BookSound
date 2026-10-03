package com.zyagodin.booksound.core.metadata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decoding of legacy 8-bit tag text. Many audiobooks (especially Russian ones) carry
 * Windows-1251 bytes in fields declared as ISO-8859-1, or UTF-8 bytes without declaring it.
 */
object TextDecoding {
    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    fun decodeLegacy(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0) return ""
        var highBytes = 0
        var cyrillicRange = 0
        var letters = 0
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) {
                highBytes++
                // 0xC0..0xFF are А..я in windows-1251; 0xA8/0xB8 are Ё/ё.
                if (b >= 0xC0 || b == 0xA8 || b == 0xB8) cyrillicRange++
                letters++
            } else if (b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code) {
                letters++
            }
        }
        if (highBytes == 0) return String(bytes, offset, length, Charsets.ISO_8859_1)
        strictUtf8(bytes, offset, length)?.let { return it }
        // Cyrillic text is made almost entirely of high bytes; Western text uses them occasionally.
        val looksCyrillic = letters > 0 && cyrillicRange >= 2 &&
            cyrillicRange.toDouble() / letters >= 0.4 && cyrillicRange.toDouble() / highBytes >= 0.8
        return String(bytes, offset, length, if (looksCyrillic) WINDOWS_1251 else Charsets.ISO_8859_1)
    }

    /** Decodes UTF-8, falling back to legacy heuristics when the bytes are not valid UTF-8. */
    fun decodeUtf8Lenient(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        strictUtf8(bytes, offset, length) ?: decodeLegacy(bytes, offset, length)

    private fun strictUtf8(bytes: ByteArray, offset: Int, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Removes NULs, BOMs and surrounding whitespace; returns null for empty values. */
    fun clean(text: String?): String? =
        text?.replace("\u0000", "")?.replace("﻿", "")?.trim()?.takeIf { it.isNotEmpty() }
}
