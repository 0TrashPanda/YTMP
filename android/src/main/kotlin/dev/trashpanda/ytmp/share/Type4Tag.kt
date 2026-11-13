package dev.trashpanda.ytmp.share

/**
 * The phone as an NFC tag with one link (docs/implementation/apps/tap-to-share.md): another phone
 * held against it reads an NFC Forum Type 4 tag, like a sticker with a link. This answers the
 * reader's commands (ISO 7816-4 APDUs): select the NDEF application, select and read its
 * capability container, select and read the NDEF file with the link. Writing is refused.
 *
 * [link] gives what's shared right now (null: nothing, and the reader finds no tag here). It's
 * read once when the reader selects the application, so the file can't change halfway a read.
 * [app]: a package the tag names after the link (an Android Application Record), so an Android
 * phone with that app opens it there, whoever else claims the link; null for a plain link.
 */
class Type4Tag(private val app: () -> String? = { null }, private val link: () -> String?) {
    private var ndefFile: ByteArray? = null
    private var selected: ByteArray? = null

    /** The reader went away: the next one starts from the beginning. */
    fun reset() {
        ndefFile = null
        selected = null
    }

    /** The answer to [apdu]: data (when it reads) and a two-byte status word. */
    fun respond(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return WRONG_LENGTH
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        if (cla != 0x00) return CLA_NOT_SUPPORTED
        return when (ins) {
            0xA4 -> when (p1) {
                0x04 -> selectApplication(apdu)
                0x00 -> selectFile(apdu)
                else -> NOT_FOUND
            }
            0xB0 -> readBinary(apdu)
            0xD6 -> SECURITY_NOT_SATISFIED
            else -> INS_NOT_SUPPORTED
        }
    }

    private fun selectApplication(apdu: ByteArray): ByteArray {
        val data = commandData(apdu) ?: return WRONG_LENGTH
        if (!data.contentEquals(NDEF_AID)) return NOT_FOUND
        val url = link() ?: return NOT_FOUND.also { reset() }
        ndefFile = ndefFile(url, app())
        selected = null
        return OK
    }

    private fun selectFile(apdu: ByteArray): ByteArray {
        val file = ndefFile ?: return NOT_FOUND
        val data = commandData(apdu) ?: return WRONG_LENGTH
        selected = when {
            data.contentEquals(CC_ID) -> capabilityContainer(file.size)
            data.contentEquals(NDEF_ID) -> file
            else -> return NOT_FOUND
        }
        return OK
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        val file = selected ?: return NOT_FOUND
        val offset = ((apdu[2].toInt() and 0x7F) shl 8) or (apdu[3].toInt() and 0xFF)
        // Le 0 (or none) means up to 256 bytes.
        val wanted = if (apdu.size > 4) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
        if (offset > file.size) return WRONG_OFFSET
        val end = minOf(file.size, offset + wanted)
        return file.copyOfRange(offset, end) + OK
    }

    /** The data of a command with a short Lc, or null when it doesn't fit. */
    private fun commandData(apdu: ByteArray): ByteArray? {
        if (apdu.size < 5) return null
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return null
        return apdu.copyOfRange(5, 5 + lc)
    }

    companion object {
        /** The NFC Forum's NDEF tag application (Type 4 Tag, version 2.0). */
        val NDEF_AID = bytes(0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01)
        val CC_ID = bytes(0xE1, 0x03)
        val NDEF_ID = bytes(0xE1, 0x04)

        /** Status words. */
        val OK = bytes(0x90, 0x00)
        val NOT_FOUND = bytes(0x6A, 0x82)
        val WRONG_LENGTH = bytes(0x67, 0x00)
        val WRONG_OFFSET = bytes(0x6B, 0x00)
        val SECURITY_NOT_SATISFIED = bytes(0x69, 0x82)
        val INS_NOT_SUPPORTED = bytes(0x6D, 0x00)
        val CLA_NOT_SUPPORTED = bytes(0x6E, 0x00)

        /** The most a reader may read or write in one command. */
        private const val MAX_READ = 0xFF
        private const val MAX_WRITE = 0xFF

        /** The capability container: mapping version 2.0, and the read-only NDEF file of [ndefSize] bytes. */
        fun capabilityContainer(ndefSize: Int): ByteArray = bytes(
            0x00, 0x0F, // its length
            0x20, // mapping version 2.0
            MAX_READ shr 8, MAX_READ and 0xFF,
            MAX_WRITE shr 8, MAX_WRITE and 0xFF,
            0x04, 0x06, // NDEF file control TLV
            0xE1, 0x04, // its file id
            ndefSize shr 8, ndefSize and 0xFF, // its size
            0x00, // read: always
            0xFF, // write: never
        )

        /** The NDEF file: the message's length (2 bytes), then the message. */
        fun ndefFile(url: String, app: String? = null): ByteArray {
            val message = Ndef.uriMessage(url, app)
            return bytes(message.size shr 8, message.size and 0xFF) + message
        }

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}

/** NDEF messages (NFC Forum): here one with a URI record, and maybe an Android Application Record. */
object Ndef {
    /** The URI record's abbreviations: a code byte stands for the start of the link. */
    private val PREFIXES = listOf(
        "http://www." to 0x01,
        "https://www." to 0x02,
        "http://" to 0x03,
        "https://" to 0x04,
    )

    /**
     * A well-known URI record ("U") holding [url]; with [app], then an Android Application Record
     * naming that package. Android opens such a tag in that app (or, without it, its store page);
     * an iPhone only reads the link.
     */
    fun uriMessage(url: String, app: String? = null): ByteArray {
        val (prefix, code) = PREFIXES.firstOrNull { url.startsWith(it.first) } ?: ("" to 0x00)
        val payload = byteArrayOf(code.toByte()) + url.removePrefix(prefix).toByteArray(Charsets.UTF_8)
        val uri = record(WELL_KNOWN, "U", payload, first = true, last = app == null)
        return if (app == null) uri else uri + record(EXTERNAL, "android.com:pkg", app.toByteArray(Charsets.US_ASCII), first = false, last = true)
    }

    private const val WELL_KNOWN = 0x01
    private const val EXTERNAL = 0x04

    /** One record: message begin/end flags, a short record when the payload fits in a byte. */
    private fun record(tnf: Int, type: String, payload: ByteArray, first: Boolean, last: Boolean): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val short = payload.size < 256
        val header = ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or (if (short) 0x10 else 0x00) or tnf).toByte()
        val length = if (short) byteArrayOf(payload.size.toByte())
        else byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
        return byteArrayOf(header, typeBytes.size.toByte()) + length + typeBytes + payload
    }
}
