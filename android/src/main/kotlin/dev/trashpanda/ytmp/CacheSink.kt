package dev.trashpanda.ytmp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import dev.trashpanda.ytmp.host.SongCache

/**
 * What the phone's player reads anyway, into the song cache as it passes (Media3's
 * TeeDataSource writes here): docs/implementation/song-cache.md#filling-it. Only for songs the
 * player marked with a cache key (their song ID); [copyFor] says where it goes, or nowhere.
 */
@OptIn(UnstableApi::class)
class CacheSink(private val copyFor: (songId: String) -> SongCache.Copy?) : DataSink {
    private var copy: SongCache.Copy? = null

    override fun open(dataSpec: DataSpec) {
        copy = dataSpec.key?.let(copyFor)?.also { it.open(dataSpec.position, dataSpec.length.takeIf { length -> length != C.LENGTH_UNSET.toLong() }) }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        copy?.write(buffer, offset, length)
    }

    override fun close() {
        copy?.close()
        copy = null
    }
}
