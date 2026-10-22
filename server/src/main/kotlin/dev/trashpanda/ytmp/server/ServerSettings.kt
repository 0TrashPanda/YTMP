package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomCodeStyle
import dev.trashpanda.ytmp.protocol.AdminSettings
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.ServerSettingOverrides
import dev.trashpanda.ytmp.protocol.ServerSettingValues
import org.slf4j.LoggerFactory

/**
 * The server settings the admin page changes (docs/features/server-admin.md). They start as in
 * `ytmp.toml` ([config]); what the admin sets is saved in the database and wins. [apply] puts
 * new values to work while the server runs.
 */
class ServerSettings(private val accounts: Accounts, config: Config) {
    /** What ytmp.toml (and the environment variables) say. */
    val file = ServerSettingValues(
        signup = config.accounts.signupMode(),
        guests = config.accounts.guests,
        trackingDefault = config.accounts.trackingDefault,
        trusted = config.accounts.trusted,
        roomCodeStyle = config.rooms.codeStyle.lowercase(),
        roomCodeLength = config.rooms.codeLength,
        audioProxy = config.audio.proxy.lowercase(),
        cacheBytes = config.cache.bytes(),
        logLevel = config.logs.level.lowercase(),
    )

    @Volatile
    var overrides: ServerSettingOverrides = load()
        private set

    @Volatile
    var values: ServerSettingValues = merge(overrides)
        private set

    /** Called with the new values after each change. */
    var apply: (old: ServerSettingValues, new: ServerSettingValues) -> Unit = { _, _ -> }

    val admin: AdminSettings get() = AdminSettings(values, file, overrides)

    /** Saves what the admin set (null: as in ytmp.toml) and applies it. Throws [IllegalArgumentException] for a value that can't be. */
    @Synchronized
    fun change(new: ServerSettingOverrides) {
        val clean = check(new)
        accounts.setSetting(SETTING, ProtocolJson.encodeToString(ServerSettingOverrides.serializer(), clean))
        val old = values
        overrides = clean
        values = merge(clean)
        log.info("Server settings changed on the admin page: {}", clean)
        apply(old, values)
    }

    private fun load(): ServerSettingOverrides =
        accounts.setting(SETTING)
            ?.let { runCatching { ProtocolJson.decodeFromString(ServerSettingOverrides.serializer(), it) }.getOrNull() }
            ?.let { runCatching { check(it) }.onFailure { e -> log.warn("Ignoring the admin page's server settings: {}", e.message) }.getOrNull() }
            ?: ServerSettingOverrides()

    private fun merge(o: ServerSettingOverrides) = ServerSettingValues(
        signup = o.signup ?: file.signup,
        guests = o.guests ?: file.guests,
        trackingDefault = o.trackingDefault ?: file.trackingDefault,
        trusted = o.trusted ?: file.trusted,
        roomCodeStyle = o.roomCodeStyle ?: file.roomCodeStyle,
        roomCodeLength = o.roomCodeLength ?: file.roomCodeLength,
        audioProxy = o.audioProxy ?: file.audioProxy,
        cacheBytes = o.cacheBytes ?: file.cacheBytes,
        logLevel = o.logLevel ?: file.logLevel,
    )

    /** [o] with its values checked and tidied. */
    private fun check(o: ServerSettingOverrides): ServerSettingOverrides {
        val style = o.roomCodeStyle?.trim()?.lowercase()
        require(style == null || RoomCodeStyle.entries.any { it.name.lowercase() == style }) { "Room codes are letters, digits or alnum" }
        require(o.roomCodeLength?.let { it in CODE_LENGTHS } ?: true) { "Room codes are ${CODE_LENGTHS.first}–${CODE_LENGTHS.last} characters" }
        val proxy = o.audioProxy?.trim()?.lowercase()
        if (proxy != null) AudioProxyMode.parse(proxy)
        require((o.cacheBytes ?: 0) >= 0) { "The song cache can't be smaller than 0" }
        val level = o.logLevel?.trim()?.lowercase()
        require(level == null || level in LOG_LEVELS) { "The log level is info or debug" }
        val trusted = o.trusted?.map { it.trim().trimEnd('/') }?.filter { it.isNotEmpty() }?.distinct()
        trusted?.forEach { require(it.startsWith("http://") || it.startsWith("https://")) { "\"$it\" isn't a web address (http:// or https://)" } }
        return o.copy(roomCodeStyle = style, audioProxy = proxy, logLevel = level, trusted = trusted)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ServerSettings::class.java)
        private const val SETTING = "server_settings"
        val CODE_LENGTHS = 3..8
        private val LOG_LEVELS = setOf("info", "debug")
    }
}

fun ServerSettingValues.codeStyle(): RoomCodeStyle = RoomCodeStyle.valueOf(roomCodeStyle.uppercase())

fun ServerSettingValues.proxyMode(): AudioProxyMode = AudioProxyMode.parse(audioProxy)
