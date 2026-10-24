package dev.trashpanda.ytmp.host

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * The part of a music file where its tags are, whole: the ID3v2 tag of an MP3, the `moov` box of
 * an M4A (at the start or the end), or the start of anything else (Ogg, Opus, FLAC: their tags
 * come first). A big cover can sit before the tag a link is in, so a fixed number of bytes from
 * the start isn't enough.
 */
object TagArea {
    /** Never more than this (a cover of a few MB fits). */
    const val MAX_BYTES = 16 * 1024 * 1024
    /** For files that aren't ID3 or MP4. */
    const val OTHER_BYTES = 1024 * 1024

    fun read(input: InputStream, max: Int = MAX_BYTES): ByteArray {
        val stream = DataInputStream(input.buffered())
        val head = ByteArray(10)
        val got = readUpTo(stream, head, 0, head.size)
        if (got >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            // Synchsafe size: 7 bits a byte; a footer adds 10 more.
            val size = (0 until 4).fold(0) { acc, i -> (acc shl 7) or (head[6 + i].toInt() and 0x7F) } + if (head[5].toInt() and 0x10 != 0) 10 else 0
            return head + readBytes(stream, minOf(size, max - 10))
        }
        if (got >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") return moov(stream, head.copyOf(got), max)
        return head.copyOf(got) + readBytes(stream, minOf(OTHER_BYTES, max) - got)
    }

    /** The `moov` box, found by stepping over the top-level boxes before it (the audio, often). */
    private fun moov(stream: DataInputStream, start: ByteArray, max: Int): ByteArray {
        // [start] holds the first box's header and a little of it.
        var header = start
        while (true) {
            if (header.size < 8) header += readBytes(stream, 8 - header.size)
            if (header.size < 8) return ByteArray(0)
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                val large = readBytes(stream, 8)
                if (large.size < 8) return ByteArray(0)
                size = (u32(large, 0) shl 32) or u32(large, 4)
                headerSize = 16
            }
            val rest = header.size - 8L
            if (type == "moov") {
                val body = if (size == 0L) max.toLong() else size - headerSize
                return header.copyOfRange(8, header.size) + readBytes(stream, (minOf(body, max.toLong()) - rest).toInt().coerceAtLeast(0))
            }
            // The last box (size 0 runs to the end), or a broken size: no moov after it.
            if (size == 0L || size < headerSize) return ByteArray(0)
            if (!skip(stream, size - headerSize - rest)) return ByteArray(0)
            header = ByteArray(0)
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun skip(stream: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = stream.skip(left)
            if (n <= 0) {
                if (stream.read() < 0) return false
                left--
            } else {
                left -= n
            }
        }
        return true
    }

    private fun readBytes(stream: InputStream, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteArray(count)
        return buffer.copyOf(readUpTo(stream, buffer, 0, count))
    }

    private fun readUpTo(stream: InputStream, buffer: ByteArray, offset: Int, count: Int): Int {
        var got = 0
        while (got < count) {
            val n = try {
                stream.read(buffer, offset + got, count - got)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            got += n
        }
        return got
    }
}
