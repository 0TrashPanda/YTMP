package dev.trashpanda.ytmp.server

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import dev.trashpanda.ytmp.core.RoomCodeStyle
import dev.trashpanda.ytmp.protocol.SignupMode
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
    val sonos: SonosConfig = SonosConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val accounts: AccountsConfig = AccountsConfig(),
    val audio: AudioConfig = AudioConfig(),
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
                accounts = base.accounts.copy(
                    url = env["YTMP_URL"] ?: base.accounts.url,
                ),
                audio = base.audio.copy(
                    proxy = env["YTMP_AUDIO_PROXY"] ?: base.audio.proxy,
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

/** See docs/implementation/playback-sync.md and [AudioProxyMode]. */
@Serializable
data class AudioConfig(
    /**
     * Streaming audio through the server, for clients whose direct YouTube link doesn't work
     * (it only works on the server's own internet connection): always | lan | never.
     */
    val proxy: String = "always",
) {
    fun proxyMode(): AudioProxyMode = AudioProxyMode.parse(proxy)
}

@Serializable
data class SonosConfig(
    /** Find Sonos speakers with SSDP. Needs UDP multicast replies to reach the server (firewall, Docker host networking). */
    val discovery: Boolean = true,
    /** Sonos speakers to always offer, by IP address, e.g. "192.168.68.100". They're named after their room. */
    val devices: List<String> = emptyList(),
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

/** See docs/features/accounts.md and docs/implementation/auth.md. */
@Serializable
data class AccountsConfig(
    /**
     * This server's public address, e.g. "https://ytmp.example.com". Account tokens for pages
     * served from there are accepted, and other hosts use it to log in. Without it, only
     * this machine's own IP addresses (and localhost) work.
     */
    val url: String = "",
    /**
     * The server's name in account IDs (`user@name`). Default: the host name of [url], or of
     * this machine, remembered on first start. Changing it later changes everyone's account ID.
     */
    val name: String = "",
    /** Who may create accounts: open | invite | admin. The first account is always allowed and becomes the server admin. */
    val signup: String = "invite",
    /** Other YTMP servers (URLs) whose accounts may join rooms here. */
    val trusted: List<String> = emptyList(),
    /** Whether new accounts keep a listening history until they turn it off. */
    @SerialName("tracking_default")
    val trackingDefault: Boolean = false,
) {
    fun signupMode(): SignupMode = SignupMode.valueOf(signup.uppercase())
}

@Serializable
data class RoomsConfig(
    @SerialName("code_style")
    val codeStyle: String = "letters",
    @SerialName("code_length")
    val codeLength: Int = 4,
) {
    fun style(): RoomCodeStyle = RoomCodeStyle.valueOf(codeStyle.uppercase())
}
