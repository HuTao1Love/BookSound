package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.io.ByteArraySource
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.TextDecoding
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class Mp3AndId3Test {

    private val cp1251 = Charset.forName("windows-1251")

    @Test
    fun `id3v23 tags and cbr duration`() {
        val tag = TestMedia.id3v23(
            listOf(
                "TIT2" to TestMedia.id3TextUtf16("Глава 1"),
                "TPE1" to TestMedia.id3TextLatin1Raw("Борис Акунин".toByteArray(cp1251)),
                "TALB" to TestMedia.id3TextLatin1Raw("Азазель".toByteArray(cp1251)),
                "TRCK" to TestMedia.id3TextLatin1Raw("3/12".toByteArray()),
                "TXXX" to TestMedia.id3TxxxUtf8("SERIES", "Приключения Эраста Фандорина"),
                "APIC" to TestMedia.id3Apic(TestMedia.TINY_JPEG),
            ),
        )
        val frames = 383 // ~10 s of 128 kbps audio
        val file = tag + TestMedia.mp3Frames(frames)
        val parsed = AudioProbe.probe(ByteArraySource(file), "01.mp3")
        assertEquals(AudioContainer.MP3, parsed.container)
        assertEquals("Глава 1", parsed.tags.title)
        assertEquals("Борис Акунин", parsed.tags.artist)
        assertEquals("Азазель", parsed.tags.album)
        assertEquals(3, parsed.tags.trackNumber)
        assertEquals(12, parsed.tags.trackTotal)
        assertEquals("Приключения Эраста Фандорина", parsed.tags.series)
        assertArrayEquals(TestMedia.TINY_JPEG, parsed.cover!!.bytes)
        val expected = frames * 1152L * 1000 / 44100
        assertTrue("duration ${parsed.durationMs} vs $expected", kotlin.math.abs(parsed.durationMs!! - expected) <= 30)
        assertEquals(128000, parsed.stream!!.bitrate)
    }

    @Test
    fun `legacy decoding heuristics`() {
        assertEquals("Привет мир", TextDecoding.decodeLegacy("Привет мир".toByteArray(cp1251)))
        assertEquals("Café crème", TextDecoding.decodeLegacy("Café crème".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("Ёлка", TextDecoding.decodeLegacy("Ёлка".toByteArray(Charsets.UTF_8)))
        assertEquals("plain", TextDecoding.decodeLegacy("plain".toByteArray()))
    }

    @Test(expected = CorruptedFileException::class)
    fun `mp3 without frames is corrupted`() {
        AudioProbe.probe(ByteArraySource(TestMedia.id3v23(listOf("TIT2" to TestMedia.id3TextUtf16("x"))) + ByteArray(5000)), "a.mp3")
    }

    @Test(expected = UnsupportedFormatException::class)
    fun `flac is unsupported`() {
        AudioProbe.probe(ByteArraySource("fLaC".toByteArray() + ByteArray(100)), "a.flac")
    }

    @Test(expected = CorruptedFileException::class)
    fun `garbage with mp3 extension is corrupted`() {
        AudioProbe.probe(ByteArraySource(ByteArray(1000) { 7 }), "a.mp3")
    }
}
