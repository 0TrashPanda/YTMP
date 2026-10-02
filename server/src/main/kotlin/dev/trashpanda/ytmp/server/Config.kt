package dev.trashpanda.ytmp.server

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import dev.trashpanda.ytmp.core.RoomCodeStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** Contents of `ytmp.toml`. Every value has a default, so the file is optional. */
@Serializable
data class Config(
    val server: ServerConfig = ServerConfig(),
    val ytm: YtmConfig = YtmConfig(),
    val rooms: RoomsConfig = RoomsConfig(),
    val cast: CastConfig = CastConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
) {
    companion object {
        private val toml = Toml(TomlInputConfig(ignoreUnknownNames = true))

        /** Reads [file] (if it exists), then applies `YTMP_*` environment variables on top. */
        fun load(file: File?, env: Map<String, String> = System.getenv()): Config {
            val base = if (file != null && file.isFile) toml.decodeFromString(serializer(), file.readText()) else Config()
            return base.copy(
                server = base.server.copy(
                    port = env["YTMP_PORT"]?.toInt() ?: base.server.port,
                    frontend = env["YTMP_FRONTEND"] ?: base.server.frontend,
                ),
                ytm = base.ytm.copy(
                    url = env["YTMP_YTM_URL"] ?: base.ytm.url,
                    key = env["YTMP_MODULE_KEY"] ?: base.ytm.key,
                ),
                database = base.database.copy(
                    type = env["YTMP_DB_TYPE"] ?: base.database.type,
                    path = env["YTMP_DB_PATH"] ?: base.database.path,
                    url = env["YTMP_DB_URL"] ?: base.database.url,
                ),
            )
        }
    }
}

@Serializable
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Folder with the built frontend (`frontend/build`). */
    val frontend: String = "frontend/build",
)

@Serializable
data class YtmConfig(
    /** Base URL of the YTM module (ytm-module/). */
    val url: String = "http://127.0.0.1:8401",
    /** Shared key, must match YTMP_MODULE_KEY of the module. */
    val key: String = "",
)

@Serializable
data class CastConfig(
    /** Find Chromecasts on the network with mDNS. Inside Docker this needs `network_mode: host`. */
    val discovery: Boolean = true,
    /** Cast devices to always offer, as "host" or "Name=host", e.g. "Living room=10.0.0.109". */
    val devices: List<String> = emptyList(),
    /**
     * Where Cast devices fetch the audio, e.g. "http://10.0.0.5:8080". Default: this server's
     * address on the device's network, with the server port. Set it when that address isn't
     * reachable (Docker without host networking, a reverse proxy, …).
     */
    @SerialName("audio_base_url")
    val audioBaseUrl: String = "",
)

/** See docs/implementation/storage.md. */
@Serializable
data class DatabaseConfig(
    /** sqlite | postgres */
    val type: String = "sqlite",
    /** SQLite file. */
    val path: String = "ytmp.db",
    /** PostgreSQL, e.g. "postgresql://ytmp:secret@localhost/ytmp". */
    val url: String = "",
)

@Serializable
data class RoomsConfig(
    @SerialName("code_style")
    val codeStyle: String = "letters",
    @SerialName("code_length")
    val codeLength: Int = 4,
) {
    fun style(): RoomCodeStyle = RoomCodeStyle.valueOf(codeStyle.uppercase())
}
