package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

/** Usage: `server [path/to/ytmp.toml]`, or set YTMP_CONFIG. */
fun main(args: Array<String>) {
    val configFile = (args.firstOrNull() ?: System.getenv("YTMP_CONFIG"))?.let(::File)
    val config = Config.load(configFile)

    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // Resolving a stream can take a few seconds; audio streams stay open for minutes.
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }
    val ytm = RemoteSourceModule(http, config.ytm.url.trimEnd('/'), config.ytm.key)

    embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        val rooms = RoomManager(ytm, this, config.rooms.style(), config.rooms.codeLength)
        rooms.startCleanup()
        ytmpModule(rooms, ytm, ytm, File(config.server.frontend))
    }.start(wait = true)
}
