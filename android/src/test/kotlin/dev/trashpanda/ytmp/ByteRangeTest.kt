package dev.trashpanda.ytmp

import dev.trashpanda.ytmp.host.ByteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ByteRangeTest {
    @Test
    fun `parses ranges`() {
        assertEquals(0L to ByteRange.OPEN_END, ByteRange.parse(null))
        assertEquals(100L to ByteRange.OPEN_END, ByteRange.parse("bytes=100-"))
        assertEquals(0L to 1023L, ByteRange.parse("bytes=0-1023"))
        assertEquals(0L to ByteRange.OPEN_END, ByteRange.parse("bytes=-500")) // suffix ranges: whole file
    }

    @Test
    fun `reads the total size`() {
        assertEquals(5000L, ByteRange.totalSize("bytes 0-1023/5000"))
        assertNull(ByteRange.totalSize(null))
    }
}
