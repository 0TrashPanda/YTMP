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
) {
    companion object {
        private val toml = Toml(TomlInputConfig(ignoreUnknownNames = true))

        fun load(file: File?): Config =
            if (file != null && file.exists()) toml.decodeFromString(serializer(), file.readText()) else Config()
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
data class RoomsConfig(
    @SerialName("code_style")
    val codeStyle: String = "letters",
    @SerialName("code_length")
    val codeLength: Int = 4,
) {
    fun style(): RoomCodeStyle = RoomCodeStyle.valueOf(codeStyle.uppercase())
}
