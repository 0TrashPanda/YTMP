package dev.trashpanda.ytmp.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Writes iTunes-style tags (title, artist, album, cover) into an .m4a file, where music players
 * read them: `moov/udta/meta/ilst` (docs/implementation/downloads.md). The audio is copied as it
 * is. A bigger `moov` moves what comes after it, so absolute offsets into that move along
 * (`stco`/`co64` chunk offsets, a `tfhd` base offset, `tfra` entries); YouTube's files are
 * fragments whose offsets count from their own `moof`, so they have none.
 */
object M4aTags {
    data class Tags(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        /** JPEG or PNG; anything else is left out. */
        val cover: ByteArray? = null,
    )

    /** Copies [input] to [output] with [tags] instead of the tags it had. */
    fun write(input: File, output: File, tags: Tags) {
        RandomAccessFile(input, "r").use { file ->
            val boxes = topLevel(file)
            val moov = boxes.firstOrNull { it.type == "moov" } ?: throw IllegalArgumentException("Not an m4a file (no moov box)")
            val oldMoov = file.read(moov)
            val newMoov = withTags(oldMoov, tags)
            val delta = (newMoov.size - oldMoov.size).toLong()
            // Offsets that point past the old moov move by delta.
            val after = moov.offset + moov.size
            if (delta != 0L) shiftChunkOffsets(newMoov, 0, newMoov.size, after, delta)
            output.outputStream().buffered().use { out ->
                for (box in boxes) when {
                    box === moov -> out.write(newMoov)
                    delta != 0L && box.type == "moof" -> out.write(file.read(box).also { shiftFragment(it, 0, it.size, after, delta) })
                    delta != 0L && box.type == "mfra" -> out.write(file.read(box).also { shiftRandomAccess(it, after, delta) })
                    else -> file.copy(box, out)
                }
            }
        }
    }

    /** The tags in an .m4a file's bytes: item name ("©nam") to its data (text as UTF-8). For tests and checks. */
    fun read(data: ByteArray): Map<String, ByteArray> {
        val moov = children(data, 0, data.size).firstOrNull { it.type == "moov" } ?: return emptyMap()
        val udta = children(data, moov.body, moov.end).firstOrNull { it.type == "udta" } ?: return emptyMap()
        val meta = children(data, udta.body, udta.end).firstOrNull { it.type == "meta" } ?: return emptyMap()
        val ilst = children(data, meta.body + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return emptyMap()
        return children(data, ilst.body, ilst.end).associate { item ->
            val value = children(data, item.body, item.end).first { it.type == "data" }
            item.type to data.copyOfRange(value.body + 8, value.end)
        }
    }

    // --- boxes ---

    /** A box: where it starts, where its contents start, where it ends. */
    private class Box(val type: String, val offset: Long, val headerSize: Int, val size: Long)

    private class Child(val type: String, val start: Int, val body: Int, val end: Int)

    private fun topLevel(file: RandomAccessFile): List<Box> {
        val boxes = mutableListOf<Box>()
        var offset = 0L
        val length = file.length()
        while (offset + 8 <= length) {
            file.seek(offset)
            var size = file.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(file::readFully).toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = file.readLong()
                header = 16
            } else if (size == 0L) {
                size = length - offset
            }
            if (size < header || offset + size > length) throw IllegalArgumentException("Not an m4a file (box $type at $offset)")
            boxes += Box(type, offset, header, size)
            offset += size
        }
        return boxes
    }

    private fun RandomAccessFile.read(box: Box): ByteArray {
        if (box.size > Int.MAX_VALUE) throw IllegalArgumentException("${box.type} box too big")
        seek(box.offset)
        return ByteArray(box.size.toInt()).also(::readFully)
    }

    private fun RandomAccessFile.copy(box: Box, out: OutputStream) {
        seek(box.offset)
        val buffer = ByteArray(64 * 1024)
        var left = box.size
        while (left > 0) {
            val n = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IllegalArgumentException("The file ended early")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun children(data: ByteArray, from: Int, to: Int): List<Child> {
        val list = mutableListOf<Child>()
        var at = from
        while (at + 8 <= to) {
            val buffer = ByteBuffer.wrap(data)
            var size = buffer.getInt(at).toLong() and 0xffffffffL
            val type = String(data, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (at + 16 > to) break
                size = buffer.getLong(at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header || at + size > to) break
            list += Child(type, at, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return list
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int(8 + parts.sumOf { it.size }))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    // --- the tags ---

    /** [moov] with its `udta/meta` replaced by one with [tags] (the rest of `udta` stays). */
    private fun withTags(moov: ByteArray, tags: Tags): ByteArray {
        val top = children(moov, 0, moov.size).single()
        val kids = children(moov, top.body, top.end)
        val body = ByteArrayOutputStream()
        kids.filter { it.type != "udta" }.forEach { body.write(moov, it.start, it.end - it.start) }
        val udta = ByteArrayOutputStream()
        kids.firstOrNull { it.type == "udta" }?.let { old ->
            children(moov, old.body, old.end).filter { it.type != "meta" }.forEach { udta.write(moov, it.start, it.end - it.start) }
        }
        udta.write(meta(tags))
        body.write(box("udta", udta.toByteArray()))
        return box("moov", body.toByteArray())
    }

    private fun meta(tags: Tags): ByteArray {
        val items = ByteArrayOutputStream()
        fun text(name: String, value: String?) {
            if (!value.isNullOrBlank()) items.write(box(name, box("data", int(TYPE_UTF8), int(0), value.toByteArray())))
        }
        text("©nam", tags.title)
        text("©ART", tags.artist)
        text("©alb", tags.album)
        text("aART", tags.albumArtist)
        text("©gen", tags.genre)
        val cover = tags.cover
        val coverType = when {
            cover == null -> null
            cover.size > 3 && cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte() -> TYPE_JPEG
            cover.size > 8 && cover[0] == 0x89.toByte() && String(cover, 1, 3, Charsets.ISO_8859_1) == "PNG" -> TYPE_PNG
            else -> null
        }
        if (cover != null && coverType != null) items.write(box("covr", box("data", int(coverType), int(0), cover)))
        // iTunes' handler, without which some players skip the tags.
        val hdlr = box("hdlr", int(0), int(0), "mdir".toByteArray(), "appl".toByteArray(), int(0), int(0), byteArrayOf(0))
        return box("meta", int(0), hdlr, box("ilst", items.toByteArray()))
    }

    // --- offsets that move ---

    private val CHUNK_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    private fun shiftChunkOffsets(data: ByteArray, from: Int, to: Int, after: Long, delta: Long) {
        for (child in children(data, from, to)) {
            when (child.type) {
                in CHUNK_CONTAINERS -> shiftChunkOffsets(data, child.body, child.end, after, delta)
                "stco", "co64" -> {
                    val wide = child.type == "co64"
                    val buffer = ByteBuffer.wrap(data)
                    val count = buffer.getInt(child.body + 4)
                    for (i in 0 until count) {
                        val at = child.body + 8 + i * (if (wide) 8 else 4)
                        if (wide) {
                            val offset = buffer.getLong(at)
                            if (offset >= after) buffer.putLong(at, offset + delta)
                        } else {
                            val offset = buffer.getInt(at).toLong() and 0xffffffffL
                            if (offset >= after) buffer.putInt(at, (offset + delta).toInt())
                        }
                    }
                }
            }
        }
    }

    /** A `moof`'s `tfhd` boxes that give an absolute base offset. */
    private fun shiftFragment(data: ByteArray, from: Int, to: Int, after: Long, delta: Long) {
        for (child in children(data, from, to)) {
            when (child.type) {
                "moof" -> shiftFragment(data, child.body, child.end, after, delta)
                "traf" -> shiftFragment(data, child.body, child.end, after, delta)
                "tfhd" -> {
                    val buffer = ByteBuffer.wrap(data)
                    val flags = buffer.getInt(child.body) and 0xffffff
                    if (flags and 1 != 0) {
                        val at = child.body + 8
                        val offset = buffer.getLong(at)
                        if (offset >= after) buffer.putLong(at, offset + delta)
                    }
                }
            }
        }
    }

    /** An `mfra`'s `tfra` entries: each points at a `moof`. */
    private fun shiftRandomAccess(data: ByteArray, after: Long, delta: Long) {
        val mfra = children(data, 0, data.size).single()
        for (tfra in children(data, mfra.body, mfra.end).filter { it.type == "tfra" }) {
            val buffer = ByteBuffer.wrap(data)
            val wide = data[tfra.body].toInt() == 1
            val sizes = buffer.getInt(tfra.body + 8)
            // Bytes for traf_number, trun_number and sample_number.
            val numbers = ((sizes shr 4) and 3) + ((sizes shr 2) and 3) + (sizes and 3) + 3
            val count = buffer.getInt(tfra.body + 12)
            var at = tfra.body + 16
            repeat(count) {
                if (wide) {
                    val offset = buffer.getLong(at + 8)
                    if (offset >= after) buffer.putLong(at + 8, offset + delta)
                    at += 16 + numbers
                } else {
                    val offset = buffer.getInt(at + 4).toLong() and 0xffffffffL
                    if (offset >= after) buffer.putInt(at + 4, (offset + delta).toInt())
                    at += 8 + numbers
                }
            }
        }
    }

    private const val TYPE_UTF8 = 1
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14
}
