package com.zyagodin.booksound.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.zyagodin.booksound.core.io.toHex
import com.zyagodin.booksound.core.model.EmbeddedPicture
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Validation and normalisation of cover images before they are embedded or cached. */
object CoverImages {
    private const val MAX_EDGE = 1400
    private const val MAX_KEEP_BYTES = 1_500_000

    /**
     * Returns a JPEG/PNG suitable for embedding, or null when [bytes] is not a decodable image.
     * Small JPEG/PNG files are kept byte-for-byte; others are decoded (respecting EXIF rotation),
     * scaled down and re-encoded as JPEG.
     */
    fun normalize(bytes: ByteArray): EmbeddedPicture? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val mime = EmbeddedPicture.sniffMimeType(bytes)
        if ((mime == "image/jpeg" || mime == "image/png") && bytes.size <= MAX_KEEP_BYTES &&
            maxOf(bounds.outWidth, bounds.outHeight) <= MAX_EDGE
        ) {
            return EmbeddedPicture(bytes, mime)
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_EDGE) {
                    val scale = MAX_EDGE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) {
            return null
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return EmbeddedPicture(out.toByteArray(), "image/jpeg")
    }

    /** Width and height of an encoded image, or null when it can't be decoded. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /**
     * Decodes for on-screen cropping (EXIF rotation applied), downsampled so the longest edge
     * stays at most [maxEdge]. Slow for big photos: call off the main thread.
     */
    fun decodeForCrop(bytes: ByteArray, maxEdge: Int = 2048): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }

    /** Cuts [region] (in [bitmap] pixels) out as a square cover JPEG. */
    fun crop(bitmap: Bitmap, region: SquareCrop.Region): EmbeddedPicture {
        val square = Bitmap.createBitmap(bitmap, region.left, region.top, region.size, region.size)
        val target = if (region.size > MAX_EDGE) Bitmap.createScaledBitmap(square, MAX_EDGE, MAX_EDGE, true) else square
        val out = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, 92, out)
        if (target !== square) target.recycle()
        if (square !== bitmap) square.recycle()
        return EmbeddedPicture(out.toByteArray(), "image/jpeg")
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

/**
 * Local cache of book covers (`files/covers`). The m4b file remains the source of truth; a missing
 * cache entry is regenerated from the embedded cover during a library scan.
 */
class CoverStore(context: Context) {
    private val dir = File(context.filesDir, "covers").apply { mkdirs() }
    private val draftDir = File(context.cacheDir, "draft-covers").apply { mkdirs() }

    /** Saves [picture] for [bookId]; the file name includes a hash so image caches refresh. */
    fun save(bookId: String, picture: EmbeddedPicture): File {
        val hash = CoverImages.sha256(picture.bytes).take(12)
        val ext = if (picture.mimeType == "image/png") "png" else "jpg"
        val target = File(dir, "$bookId-$hash.$ext")
        if (!target.exists()) writeAtomically(target, picture.bytes)
        dir.listFiles { f -> f.name.startsWith("$bookId-") && f != target }?.forEach { it.delete() }
        return target
    }

    fun delete(bookId: String) {
        dir.listFiles { f -> f.name.startsWith("$bookId-") }?.forEach { it.delete() }
    }

    /** Temporary file used to preview a cover candidate during import. */
    fun draftFile(sessionId: String, picture: EmbeddedPicture): File {
        val sessionDir = File(draftDir, sessionId).apply { mkdirs() }
        val hash = CoverImages.sha256(picture.bytes).take(16)
        val ext = if (picture.mimeType == "image/png") "png" else "jpg"
        val file = File(sessionDir, "$hash.$ext")
        if (!file.exists()) writeAtomically(file, picture.bytes)
        return file
    }

    fun clearDrafts(sessionId: String) {
        File(draftDir, sessionId).deleteRecursively()
    }

    fun clearAllDrafts() {
        draftDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            target.writeBytes(bytes)
        }
    }
}
