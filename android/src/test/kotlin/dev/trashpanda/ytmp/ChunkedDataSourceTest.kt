package dev.trashpanda.ytmp

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChunkedDataSourceTest {
    /** A file served like YouTube does: each request gets at most the range it asked for. */
    private class Server(val file: ByteArray, val sendsSize: Boolean = true) {
        val requests = mutableListOf<LongRange>()
        private var piece: ByteArray = ByteArray(0)
        private var at = 0

        val pieces = ChunkedDataSource.Pieces(
            openRange = { start, length ->
                val end = minOf(file.size.toLong(), start + length)
                requests += start until end
                piece = file.copyOfRange(start.toInt(), end.toInt())
                at = 0
                piece.size.toLong()
            },
            readPiece = { buffer, offset, length ->
                if (at == piece.size) C.RESULT_END_OF_INPUT
                else minOf(length, piece.size - at).also { piece.copyInto(buffer, offset, at, at + it); at += it }
            },
            closePiece = {},
            totalSize = { if (sendsSize) file.size.toLong() else null },
            chunkSize = 100,
        )
    }

    private fun readAll(pieces: ChunkedDataSource.Pieces, bufferSize: Int = 37): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        while (true) {
            val count = pieces.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buffer, 0, count)
        }
    }

    private val file = ByteArray(350) { it.toByte() }

    @Test
    fun `the rest of the file comes in pieces with an end each`() {
        val server = Server(file)
        assertEquals(350, server.pieces.open(0, C.LENGTH_UNSET.toLong()))
        assertContentEquals(file, readAll(server.pieces))
        assertEquals(listOf(0L until 100, 100L until 200, 200L until 300, 300L until 350), server.requests)
    }

    @Test
    fun `a seek reads from there`() {
        val server = Server(file)
        assertEquals(230, server.pieces.open(120, C.LENGTH_UNSET.toLong()))
        assertContentEquals(file.copyOfRange(120, 350), readAll(server.pieces))
        assertEquals(listOf(120L until 220, 220L until 320, 320L until 350), server.requests)
    }

    @Test
    fun `a range with an end stops there`() {
        val server = Server(file)
        assertEquals(150, server.pieces.open(10, 150))
        assertContentEquals(file.copyOfRange(10, 160), readAll(server.pieces))
        assertEquals(listOf(10L until 110, 110L until 160), server.requests)
    }

    @Test
    fun `without a size, a short piece is the end`() {
        val server = Server(file, sendsSize = false)
        assertEquals(C.LENGTH_UNSET.toLong(), server.pieces.open(0, C.LENGTH_UNSET.toLong()))
        assertContentEquals(file, readAll(server.pieces, bufferSize = 64))
        assertEquals(4, server.requests.size)
    }
}
