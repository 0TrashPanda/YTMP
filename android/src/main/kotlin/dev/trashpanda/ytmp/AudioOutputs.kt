package dev.trashpanda.ytmp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the phone plays music on: its speaker, wired headphones, a Bluetooth or USB device. */
@Serializable
data class AudioOutput(val id: String, val name: String) {
    companion object {
        val SPEAKER = AudioOutput("speaker", "Phone speaker")

        /** The output for an Android audio device of [type] (AudioDeviceInfo.TYPE_*) called [name]. */
        @SuppressLint("InlinedApi") // Only compared: an older Android never says them.
        fun of(type: Int, name: String): AudioOutput = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG ->
                AudioOutput("wired", "Wired headphones")
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID ->
                AudioOutput("bluetooth:$name", name.ifBlank { "Bluetooth" })
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                AudioOutput("usb:$name", name.ifBlank { "USB audio" })
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> AudioOutput("hdmi", "HDMI")
            else -> AudioOutput("other:$type:$name", name.ifBlank { "Other output" })
        }

        /**
         * Of the outputs connected now (type and name), the one music plays on, for an Android
         * that can't say (before 13): Bluetooth before USB before wired before the speaker, as
         * Android picks.
         */
        @SuppressLint("InlinedApi")
        fun likelyFor(connected: List<Pair<Int, String>>): AudioOutput {
            val order = listOf(
                setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID),
                setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE),
                setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG),
            )
            for (types in order) connected.firstOrNull { it.first in types }?.let { return of(it.first, it.second) }
            return SPEAKER
        }
    }
}

/** For the page (bridge `equalizer`): the setting for what the phone plays on now. */
@Serializable
data class EqualizerState(val output: AudioOutput, val settings: EqSettings)

/**
 * The equalizer's settings, one per output (docs/features/playback-and-outputs.md#equalizer):
 * the phone's speaker, wired headphones and each Bluetooth device keep their own. Follows what
 * the phone plays on, so [settings] is always the one for that ([EqualizerProcessor] reads it).
 */
object Equalizers {
    private const val TAG = "YtmpEqualizer"
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var context: Context
    private val prefs by lazy { context.getSharedPreferences("equalizer", Context.MODE_PRIVATE) }
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val followLater = Runnable { follow() }
    /** The setting for the output at the start has been read. */
    private var started = false

    private val mutableState = MutableStateFlow(EqualizerState(AudioOutput.SPEAKER, EqSettings.OFF))
    val state: StateFlow<EqualizerState> = mutableState.asStateFlow()

    /** The setting for what plays now; any thread. */
    val settings: EqSettings get() = mutableState.value.settings

    fun start(context: Context) {
        this.context = context.applicationContext
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
        }, main)
        follow()
    }

    /** Sets the equalizer for what the phone plays on now. */
    fun set(settings: EqSettings) {
        val output = mutableState.value.output
        val valid = settings.valid()
        prefs.edit().putString(output.id, json.encodeToString(EqSettings.serializer(), valid)).apply()
        mutableState.value = EqualizerState(output, valid)
    }

    /** Headphones came or went: Android may switch over a moment later, so look again then too. */
    private fun changed() {
        follow()
        main.removeCallbacks(followLater)
        main.postDelayed(followLater, 1000)
    }

    private fun follow() {
        val output = runCatching { current() }.getOrElse {
            Log.w(TAG, "can't tell what the phone plays on", it)
            AudioOutput.SPEAKER
        }
        if (started && output == mutableState.value.output) return
        started = true
        val settings = prefs.getString(output.id, null)
            ?.let { runCatching { json.decodeFromString(EqSettings.serializer(), it).valid() }.getOrNull() }
            ?: EqSettings.OFF
        Log.i(TAG, "plays on ${output.name}: equalizer ${if (settings.on) "on (${settings.preset ?: "own"})" else "off"}")
        mutableState.value = EqualizerState(output, settings)
    }

    private fun current(): AudioOutput {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            runCatching { audio.getAudioDevicesForAttributes(media).firstOrNull() }.getOrNull()
                ?.let { return AudioOutput.of(it.type, it.productName.toString()) }
        }
        return AudioOutput.likelyFor(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type to it.productName.toString() })
    }
}
