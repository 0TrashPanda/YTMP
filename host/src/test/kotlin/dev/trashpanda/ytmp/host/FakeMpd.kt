package dev.trashpanda.ytmp.host

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A pretend MPD: answers the protocol like a real one (enough of it) and records the commands. */
class FakeMpd(
    private val outputName: String = "Bluetooth Speaker",
    /** null: no mixer, like an output without volume control. */
    @Volatile var volume: Int? = 40,
) : AutoCloseable {
    val commands = CopyOnWriteArrayList<String>()
    @Volatile var state = "stop"
        set(value) {
            at = positionMs
            since = System.currentTimeMillis()
            field = value
        }
    @Volatile var file: String? = null
    /** Moves on while it plays. */
    var positionMs: Long
        get() = at + if (state == "play") System.currentTimeMillis() - since else 0
        set(value) {
            at = value
            since = System.currentTimeMillis()
        }
    @Volatile private var at = 0L
    @Volatile private var since = 0L
    @Volatile var error: String? = null

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val out = it.getOutputStream()
                        out.write("OK MPD 0.23.5\n".toByteArray())
                        while (true) {
                            val line = reader.readLine() ?: break
                            commands += line
                            out.write(answer(line).toByteArray())
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    private fun answer(line: String): String {
        val command = line.substringBefore(' ')
        val argument = line.substringAfter(' ', "").removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        val body = when (command) {
            "outputs" -> "outputid: 0\noutputname: $outputName\nplugin: alsa\noutputenabled: 1\n"
            "status" -> buildString {
                volume?.let { append("volume: $it\n") }
                append("state: $state\n")
                if (file != null && state != "stop") append("elapsed: ${positionMs / 1000.0}\n")
                error?.let { append("error: $it\n") }
            }
            "currentsong" -> file?.let { "file: $it\nId: 1\n" }.orEmpty()
            "clearerror" -> { error = null; "" }
            "clear" -> { file = null; state = "stop"; positionMs = 0; "" }
            "addid" -> { file = argument; "Id: 1\n" }
            "play" -> if (file == null) return "ACK [50@0] {play} No such song\n" else { positionMs = 0; state = "play"; "" }
            "pause" -> { if (state != "stop") state = if (argument == "1") "pause" else "play"; "" }
            "stop" -> { state = "stop"; "" }
            "seekcur" -> if (state == "stop") return "ACK [55@0] {seekcur} Not playing\n" else { positionMs = (argument.toDouble() * 1000).toLong(); "" }
            "setvol" -> if (volume == null) return "ACK [52@0] {setvol} problems setting volume\n" else { volume = argument.toInt(); "" }
            else -> return "ACK [5@0] {$command} unknown command \"$command\"\n"
        }
        return body + "OK\n"
    }

    val port: Int get() = server.localPort

    override fun close() = server.close()
}
