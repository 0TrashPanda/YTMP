package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.text.Normalizer

/**
 * "Is this the same song?" across sources (a YTM song and a local file), by title and main
 * artist. See docs/implementation/storage.md#song-matching.
 */
object SongMatch {
    /** Title and main artist, lowercase, without accents, brackets ("(Remastered)", "[Official Video]") and "feat." parts. */
    fun key(song: Song): String = "${normalize(song.title)}|${normalize(song.artists.firstOrNull()?.name.orEmpty())}"

    /** The lengths of two versions of one song differ by at most this much (unknown lengths always match). */
    const val DURATION_SLACK_MS = 5_000L

    fun sameLength(a: Long, b: Long) = a <= 0 || b <= 0 || kotlin.math.abs(a - b) <= DURATION_SLACK_MS

    private val brackets = Regex("""\([^)]*\)|\[[^]]*]""")
    private val featuring = Regex("""\s(feat\.?|ft\.?|featuring)\s.*$""")
    private val marks = Regex("""\p{M}+""")
    private val other = Regex("""[^\p{L}\p{N}]+""")

    fun normalize(text: String): String {
        val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(marks, "")
        return plain.replace(brackets, " ").replace(featuring, "").replace(other, " ").trim()
    }
}

/**
 * A radio with songs from the song graph mixed in: one of [graph]'s (best first) after every
 * [every] of [base], the rest of each after the other runs out. Songs are in it once (also the same
 * song from two sources). Without a [base] (no YTM radio for the song, or it failed) it's
 * [graph] alone.
 */
fun mixRadio(base: List<Song>, graph: List<Song>, limit: Int = RADIO_MIX_LIMIT, every: Int = 2): List<Song> {
    val seen = HashSet<String>()
    val out = ArrayList<Song>()
    fun add(song: Song) {
        if (out.size < limit && seen.add(song.id) && seen.add(SongMatch.key(song))) out += song
    }
    val fromBase = base.iterator()
    val fromGraph = graph.iterator()
    while (fromBase.hasNext() || fromGraph.hasNext()) {
        repeat(every.coerceAtLeast(1)) { if (fromBase.hasNext()) add(fromBase.next()) }
        if (fromGraph.hasNext()) add(fromGraph.next())
    }
    return out
}

/**
 * [base] (YTM's radio) with the song graph's songs ([graph]) mixed in ([mixRadio]), both asked
 * at once. [graph] failing just means no songs from it; [base] failing only matters when the
 * graph has none either.
 */
suspend fun mixedRadio(base: suspend () -> List<Song>, graph: suspend () -> List<Song>): List<Song> = coroutineScope {
    val learned = async {
        try {
            graph()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mixLog.warn("No songs from the song graph: {}", e.message)
            emptyList()
        }
    }
    val songs = try {
        Result.success(base())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
    val fromGraph = learned.await()
    songs.exceptionOrNull()?.let { if (fromGraph.isEmpty()) throw it }
    mixRadio(songs.getOrDefault(emptyList()), fromGraph)
}

private val mixLog = LoggerFactory.getLogger("dev.trashpanda.ytmp.core.RadioMix")

/** A radio is at most this long after mixing. */
const val RADIO_MIX_LIMIT = 60
