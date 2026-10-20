package dev.trashpanda.ytmp.host

/** HTTP byte ranges, as far as audio streaming needs them. */
object ByteRange {
    const val OPEN_END = Long.MAX_VALUE / 2

    /** "bytes=100-" -> (100, [OPEN_END]); null or unsupported -> the whole file. */
    fun parse(header: String?): Pair<Long, Long> {
        val match = Regex("""bytes=(\d+)-(\d*)""").matchEntire(header?.trim().orEmpty()) ?: return 0L to OPEN_END
        val (start, end) = match.destructured
        return start.toLong() to (end.toLongOrNull() ?: OPEN_END)
    }

    /** "bytes 0-1023/5000" -> 5000. */
    fun totalSize(contentRange: String?): Long? =
        contentRange?.let { Regex("""/(\d+)$""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
}
