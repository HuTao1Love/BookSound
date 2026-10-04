package com.zyagodin.booksound.core.io

import java.io.ByteArrayOutputStream

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

internal fun ByteArray.u24(at: Int): Int = (u8(at) shl 16) or (u8(at + 1) shl 8) or u8(at + 2)

internal fun ByteArray.u32(at: Int): Long =
    ((u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong())

internal fun ByteArray.s32(at: Int): Int = u32(at).toInt()

internal fun ByteArray.u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)

/** ID3v2 "synchsafe" integer: 7 significant bits per byte. */
internal fun ByteArray.synchsafe32(at: Int): Int =
    ((u8(at) and 0x7F) shl 21) or ((u8(at + 1) and 0x7F) shl 14) or ((u8(at + 2) and 0x7F) shl 7) or (u8(at + 3) and 0x7F)

internal fun ByteArray.fourCC(at: Int): String = String(this, at, 4, Charsets.ISO_8859_1)

/** Big-endian byte writer used to build MP4 boxes. */
class BeWriter(initialCapacity: Int = 256) {
    private val out = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun u24(v: Int) = apply { u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun u32(v: Long) = apply { u8((v ushr 24).toInt()); u8((v ushr 16).toInt()); u8((v ushr 8).toInt()); u8(v.toInt()) }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) = apply { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
    fun bytes(b: ByteArray) = apply { out.write(b, 0, b.size) }
    fun fourCC(type: String) = apply {
        val b = type.toByteArray(Charsets.ISO_8859_1)
        require(b.size == 4) { "Invalid fourCC '$type'" }
        bytes(b)
    }
    fun zeros(count: Int) = apply { repeat(count) { out.write(0) } }

    fun toByteArray(): ByteArray = out.toByteArray()
}
