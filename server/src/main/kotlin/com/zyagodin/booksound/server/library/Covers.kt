package com.zyagodin.booksound.server.library

import com.zyagodin.booksound.core.io.toHex
import com.zyagodin.booksound.core.model.EmbeddedPicture
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Cover images: normalized like the phone does before embedding, cached per book, resized on request. */
class Covers(private val dir: File) {

    init {
        ImageIO.setUseCache(false)
        ImageIO.scanForPlugins() // WebP reader from TwelveMonkeys
    }

    fun file(bookId: String): File? = listOf("jpg", "png").map { File(dir, "$bookId.$it") }.firstOrNull { it.isFile }

    fun save(bookId: String, picture: EmbeddedPicture) {
        remove(bookId)
        val ext = if (picture.mimeType == "image/png") "png" else "jpg"
        File(dir, "$bookId.$ext").writeBytes(picture.bytes)
    }

    fun remove(bookId: String) {
        dir.listFiles { f -> f.name.startsWith("$bookId.") || f.name.startsWith("$bookId-") }?.forEach { it.delete() }
    }

    /** The cover with its longest edge at most [size] pixels, as JPEG; cached next to the original. */
    fun sized(bookId: String, size: Int): File? {
        val original = file(bookId) ?: return null
        val cached = File(dir, "$bookId-$size.jpg")
        if (cached.isFile && cached.lastModified() >= original.lastModified()) return cached
        val image = read(original.readBytes()) ?: return null
        val tmp = File(dir, "$bookId-$size.jpg.tmp")
        tmp.writeBytes(jpeg(scale(image, size), 0.85f))
        tmp.renameTo(cached)
        return cached
    }

    companion object {
        private const val MAX_EDGE = 1400
        private const val MAX_KEEP_BYTES = 1_500_000

        /**
         * Returns a JPEG/PNG suitable for embedding, or null when [bytes] is not a decodable image. Small JPEG/PNG
         * files are kept byte-for-byte; others are scaled to a longest edge of 1400 px and re-encoded as JPEG.
         * The same rule as the phone's `CoverImages.normalize`.
         */
        fun normalize(bytes: ByteArray): EmbeddedPicture? {
            val image = read(bytes) ?: return null
            val mime = EmbeddedPicture.sniffMimeType(bytes)
            if ((mime == "image/jpeg" || mime == "image/png") && bytes.size <= MAX_KEEP_BYTES && maxOf(image.width, image.height) <= MAX_EDGE) {
                return EmbeddedPicture(bytes, mime)
            }
            return EmbeddedPicture(jpeg(scale(image, MAX_EDGE), 0.9f), "image/jpeg")
        }

        /** A small JPEG for embedding into a watch copy of a book. */
        fun shrink(picture: EmbeddedPicture, size: Int): EmbeddedPicture? {
            val image = read(picture.bytes) ?: return null
            return EmbeddedPicture(jpeg(scale(image, size), 0.85f), "image/jpeg")
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun read(bytes: ByteArray): BufferedImage? = try {
            ImageIO.read(ByteArrayInputStream(bytes))?.takeIf { it.width > 0 && it.height > 0 }
        } catch (_: Exception) {
            null
        }

        private fun scale(image: BufferedImage, maxEdge: Int): BufferedImage {
            val longest = maxOf(image.width, image.height)
            val ratio = if (longest > maxEdge) maxEdge.toDouble() / longest else 1.0
            val w = (image.width * ratio).toInt().coerceAtLeast(1)
            val h = (image.height * ratio).toInt().coerceAtLeast(1)
            // Always redraw into RGB: JPEG can't hold alpha, and some decoders return odd color models.
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                color = java.awt.Color.WHITE
                fillRect(0, 0, w, h)
                drawImage(image, 0, 0, w, h, null)
                dispose()
            }
            return out
        }

        private fun jpeg(image: BufferedImage, quality: Float): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(image, null, null), params)
                writer.dispose()
            }
            return out.toByteArray()
        }
    }
}
