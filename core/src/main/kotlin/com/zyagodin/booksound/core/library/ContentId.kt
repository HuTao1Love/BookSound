package com.zyagodin.booksound.core.library

import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.readUpTo
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable id for library files without an embedded BookSound id, derived from size and leading content,
 * so every device that finds the same file gives it the same id.
 */
object ContentId {
    private const val HEAD_BYTES = 4 * 1024 * 1024

    fun of(source: RandomAccessSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source.readUpTo(0, HEAD_BYTES))
        digest.update(source.size.toString().toByteArray())
        return UUID.nameUUIDFromBytes(digest.digest()).toString()
    }
}
