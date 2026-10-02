package dev.trashpanda.ytmp.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * One message of the Cast v2 protocol. On the wire it is a protobuf `CastMessage`, prefixed with
 * its length (4 bytes, big endian). Only text (JSON) payloads are used here.
 *
 * ```
 * message CastMessage {
 *   ProtocolVersion protocol_version = 1;  // always 0 (CASTV2_1_0)
 *   string source_id = 2;
 *   string destination_id = 3;
 *   string namespace = 4;
 *   PayloadType payload_type = 5;          // 0 = STRING
 *   string payload_utf8 = 6;
 * }
 * ```
 */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payload: String,
) {
    fun encode(): ByteArray {
        val body = ByteArrayOutputStream()
        body.varintField(1, 0)
        body.stringField(2, sourceId)
        body.stringField(3, destinationId)
        body.stringField(4, namespace)
        body.varintField(5, 0)
        body.stringField(6, payload)
        val bytes = body.toByteArray()
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).writeInt(bytes.size)
            out.write(bytes)
        }.toByteArray()
    }

    companion object {
        private const val MAX_SIZE = 64 * 1024

        /** Reads one framed message. Messages with a binary payload come back with an empty payload. */
        fun read(input: DataInputStream): CastMessage {
            val size = input.readInt()
            require(size in 0..MAX_SIZE) { "Bad Cast message size $size" }
            val bytes = ByteArray(size).also(input::readFully)
            return decode(bytes)
        }

        fun decode(bytes: ByteArray): CastMessage {
            var source = ""
            var destination = ""
            var namespace = ""
            var payload = ""
            var i = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = bytes[i++].toInt() and 0xff
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (i < bytes.size) {
                val key = varint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> varint()
                    2 -> {
                        val length = varint().toInt()
                        val text = String(bytes, i, length, Charsets.UTF_8)
                        i += length
                        when (field) {
                            2 -> source = text
                            3 -> destination = text
                            4 -> namespace = text
                            6 -> payload = text
                        }
                    }
                    else -> error("Unsupported protobuf wire type in Cast message")
                }
            }
            return CastMessage(source, destination, namespace, payload)
        }

        private fun ByteArrayOutputStream.varint(value: Long) {
            var v = value
            while (v and 0x7f.inv().toLong() != 0L) {
                write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            write(v.toInt())
        }

        private fun ByteArrayOutputStream.varintField(field: Int, value: Long) {
            varint((field shl 3).toLong())
            varint(value)
        }

        private fun ByteArrayOutputStream.stringField(field: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            varint(((field shl 3) or 2).toLong())
            varint(bytes.size.toLong())
            write(bytes)
        }
    }
}
