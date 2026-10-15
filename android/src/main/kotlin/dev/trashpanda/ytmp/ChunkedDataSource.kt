package dev.trashpanda.ytmp

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.trashpanda.ytmp.host.ByteRange

/**
 * Reads YouTube's stream links in [CHUNK_SIZE] pieces, each asked for with its own byte range,
 * passed on to the player as one stream.
 *
 * ExoPlayer asks for the whole rest of the file, and YouTube sends such a request only about as
 * fast as the song plays: the buffer kept running dry, which you hear as short pauses. The
 * audio proxies already ask in pieces, like yt-dlp does (see OnDeviceAudioProxy and the
 * module's /audio); this does the same for the phone's own player. Other links (a host's
 * /api/audio) pass through unchanged.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(private val upstream: DataSource) : DataSource {
    /** The whole request while it's read in pieces; null when passed through. */
    private var spec: DataSpec? = null
    private val pieces = Pieces(
        openRange = { start, length -> upstream.open(spec!!.subrange(start - spec!!.position, length)) },
        readPiece = upstream::read,
        closePiece = upstream::close,
        totalSize = { contentRange(upstream.responseHeaders) },
    )

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!isYoutube(dataSpec.uri)) {
            spec = null
            return upstream.open(dataSpec)
        }
        spec = dataSpec
        return pieces.open(dataSpec.position, dataSpec.length)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (spec == null) upstream.read(buffer, offset, length) else pieces.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (spec != null) pieces.close() else upstream.close()
        spec = null
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    /**
     * Reads `[start, start + length)` (length [C.LENGTH_UNSET]: to the end) as consecutive
     * pieces of at most [chunkSize] bytes from [openRange]. Kept apart from Media3's types to test it.
     */
    internal class Pieces(
        /** Opens the bytes from `start` (`length` of them); returns how many it has, or [C.LENGTH_UNSET]. */
        private val openRange: (start: Long, length: Long) -> Long,
        private val readPiece: (buffer: ByteArray, offset: Int, length: Int) -> Int,
        private val closePiece: () -> Unit,
        /** The file's size, as the last opened piece says; null when unknown. */
        private val totalSize: () -> Long?,
        private val chunkSize: Long = CHUNK_SIZE,
    ) {
        private var position = 0L
        /** Where the request ends (exclusive), or [UNSET] while not known. */
        private var end = UNSET
        private var pieceLeft = 0L
        private var pieceOpen = false

        fun open(start: Long, length: Long): Long {
            position = start
            end = if (length == UNSET) UNSET else start + length
            openPiece()
            if (end == UNSET) end = totalSize() ?: UNSET
            return if (end == UNSET) UNSET else end - start
        }

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (pieceLeft == 0L) {
                if (end != UNSET && position >= end) return C.RESULT_END_OF_INPUT
                close()
                openPiece()
                if (pieceLeft == 0L) return C.RESULT_END_OF_INPUT
            }
            val count = readPiece(buffer, offset, minOf(length.toLong(), pieceLeft).toInt())
            // A piece that ends early is the end of the file.
            if (count == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            position += count
            pieceLeft -= count
            return count
        }

        fun close() {
            if (pieceOpen) closePiece()
            pieceOpen = false
            pieceLeft = 0
        }

        private fun openPiece() {
            val length = if (end == UNSET) chunkSize else minOf(chunkSize, end - position)
            pieceOpen = true
            val opened = openRange(position, length)
            pieceLeft = if (opened == UNSET) length else opened
            // Less than asked for while the size isn't known: this was the last piece.
            if (end == UNSET && opened != UNSET && opened < length) end = position + opened
        }
    }

    companion object {
        /** Like the audio proxies: 1 MB, about a minute of music. */
        const val CHUNK_SIZE = 1024L * 1024
        private const val UNSET = C.LENGTH_UNSET.toLong()

        fun isYoutube(uri: Uri): Boolean = uri.host?.endsWith(".googlevideo.com") == true

        private fun contentRange(headers: Map<String, List<String>>): Long? =
            headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()?.let(ByteRange::totalSize)
    }
}
