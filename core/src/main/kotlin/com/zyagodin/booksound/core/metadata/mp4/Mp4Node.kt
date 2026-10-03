package com.zyagodin.booksound.core.metadata.mp4

import com.zyagodin.booksound.core.io.BeWriter
import com.zyagodin.booksound.core.io.fourCC
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u64
import com.zyagodin.booksound.core.metadata.CorruptedFileException

/**
 * In-memory MP4 box. Containers keep parsed [children] (plus an optional [prefix], e.g. the
 * version/flags of a full box); leaves keep their raw [payload].
 */
class Mp4Node private constructor(
    val type: String,
    var prefix: ByteArray,
    var payload: ByteArray?,
    val children: MutableList<Mp4Node>?,
) {
    val isContainer: Boolean get() = children != null

    fun child(type: String): Mp4Node? = children?.firstOrNull { it.type == type }

    fun childrenOf(type: String): List<Mp4Node> = children?.filter { it.type == type }.orEmpty()

    /** Resolves a slash separated path such as "mdia/minf/stbl". */
    fun find(path: String): Mp4Node? {
        var node: Mp4Node? = this
        for (part in path.split('/')) node = node?.child(part) ?: return null
        return node
    }

    fun requireChildren(): MutableList<Mp4Node> = children ?: error("Box $type is not a container")

    fun bodySize(): Long = if (children != null) prefix.size + children.sumOf { it.size() } else payload!!.size.toLong()

    fun size(): Long {
        val body = bodySize()
        return body + if (body + 8 > UInt.MAX_VALUE.toLong()) 16 else 8
    }

    fun writeTo(out: BeWriter) {
        val body = bodySize()
        if (body + 8 > UInt.MAX_VALUE.toLong()) {
            out.u32(1).fourCC(type).u64(body + 16)
        } else {
            out.u32(body + 8).fourCC(type)
        }
        if (children != null) {
            out.bytes(prefix)
            children.forEach { it.writeTo(out) }
        } else {
            out.bytes(payload!!)
        }
    }

    fun toByteArray(): ByteArray = BeWriter(size().toInt()).also { writeTo(it) }.toByteArray()

    override fun toString(): String = if (children != null) "$type$children" else "$type(${payload!!.size})"

    companion object {
        fun leaf(type: String, payload: ByteArray) = Mp4Node(type, ByteArray(0), payload, null)

        fun container(type: String, children: List<Mp4Node> = emptyList(), prefix: ByteArray = ByteArray(0)) =
            Mp4Node(type, prefix, null, children.toMutableList())

        private val CONTAINERS = setOf(
            "moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf", "tref", "mvex", "gmhd", "ilst",
        )

        /** Parses a box tree from [data] (the complete bytes of one box including its header). */
        fun parse(data: ByteArray): Mp4Node {
            val nodes = parseChildren(data, 0, data.size, parentType = "")
            if (nodes.size != 1) throw CorruptedFileException("Expected a single box, found ${nodes.size}")
            return nodes.single()
        }

        internal fun parseChildren(data: ByteArray, start: Int, end: Int, parentType: String): MutableList<Mp4Node> {
            val result = mutableListOf<Mp4Node>()
            var pos = start
            while (pos < end) {
                if (end - pos < 8) {
                    // QuickTime udta/ilst sometimes end with a 32-bit zero terminator; ignore such padding.
                    if (data.copyOfRange(pos, end).all { it.toInt() == 0 }) break
                    throw CorruptedFileException("Truncated box inside '$parentType'")
                }
                var size = data.u32(pos)
                val type = data.fourCC(pos + 4)
                var header = 8
                if (size == 1L) {
                    if (end - pos < 16) throw CorruptedFileException("Truncated large box '$type'")
                    size = data.u64(pos + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (end - pos).toLong()
                }
                if (size < header || pos + size > end) {
                    throw CorruptedFileException("Box '$type' in '$parentType' has invalid size $size")
                }
                val bodyStart = pos + header
                val bodyEnd = (pos + size).toInt()
                result += parseBody(data, type, bodyStart, bodyEnd, parentType)
                pos = bodyEnd
            }
            return result
        }

        private fun parseBody(data: ByteArray, type: String, bodyStart: Int, bodyEnd: Int, parentType: String): Mp4Node {
            return when {
                type == "meta" -> {
                    // ISO meta is a full box (4 bytes version/flags); QuickTime meta is a plain container.
                    val isQuickTimeStyle = bodyEnd - bodyStart >= 8 && data.fourCC(bodyStart + 4) == "hdlr"
                    val prefixLen = if (isQuickTimeStyle) 0 else 4
                    container(
                        type,
                        parseChildren(data, bodyStart + prefixLen, bodyEnd, type),
                        data.copyOfRange(bodyStart, bodyStart + prefixLen),
                    )
                }
                type in CONTAINERS || parentType == "ilst" ->
                    container(type, parseChildren(data, bodyStart, bodyEnd, type))
                else -> leaf(type, data.copyOfRange(bodyStart, bodyEnd))
            }
        }
    }
}

/** A top level box located in the file without loading its body. */
data class TopLevelBox(val type: String, val offset: Long, val headerSize: Int, val size: Long) {
    val payloadOffset: Long get() = offset + headerSize
    val payloadSize: Long get() = size - headerSize
    val end: Long get() = offset + size
}
