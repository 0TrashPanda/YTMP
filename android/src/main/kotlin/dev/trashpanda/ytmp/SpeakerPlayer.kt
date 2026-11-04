package dev.trashpanda.ytmp

import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.roundToInt

/**
 * The media session's player while the room plays on speakers or TVs and not on this phone
 * (docs/features/playback-and-outputs.md#media-controls-while-casting): the notification and lock
 * screen show the room's song with its controls, like YouTube Music while casting, and the
 * volume (the system's volume panel, the volume buttons) is the speakers' volume, all of them
 * together ([SpeakerGroup]). Nothing plays here: every control becomes a room command.
 */
@OptIn(UnstableApi::class)
class SpeakerPlayer(private val controls: Controls) : SimpleBasePlayer(Looper.getMainLooper()) {
    interface Controls {
        /**
         * Runs a room command (JSON). With the app on screen the page asks first in a party;
         * [onlyAlone]: else not while others listen along (a pause would stop them too).
         */
        fun command(json: String, onlyAlone: Boolean)

        /** Sets each speaker's volume (0–1), by speaker ID. */
        fun setVolumes(volumes: Map<String, Double>)
    }

    private var target: PlaybackTarget? = null
    private var speakers: List<Speaker> = emptyList()
    private var canSetVolume = false
    private val group = SpeakerGroup()

    /** The level the buttons asked for, until the speakers say they have it (or a moment passed). */
    private var asked: Double? = null
    private var askedAt = 0L

    /** What to show: the room's [target] (not playing here), on [speakers]. */
    fun show(target: PlaybackTarget, speakers: List<Speaker>, canSetVolume: Boolean) {
        this.target = target
        this.speakers = speakers
        this.canSetVolume = canSetVolume
        invalidateState()
    }

    private fun level(): Double? {
        val reported = group.level(speakers)
        val wanted = asked
        if (wanted != null && (reported == wanted || SystemClock.elapsedRealtime() - askedAt > ASKED_FOR_MS)) asked = null
        return asked ?: reported
    }

    override fun getState(): State {
        val target = target
        val item = target?.item ?: return State.Builder().build()
        val level = level()
        val commands = Player.Commands.Builder()
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE)
        if (target.canSkip) commands.addAll(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        if (target.canSeek) commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (level != null) commands.add(Player.COMMAND_GET_DEVICE_VOLUME)
        if (level != null && canSetVolume) commands.addAll(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .setArtworkUri(item.artUrl?.let(Uri::parse))
            .setDurationMs(item.durationMs)
            .build()
        val position = SyncCorrection.targetPosition(target, System.currentTimeMillis()).coerceIn(0, maxOf(0, item.durationMs))
        return State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(item.itemId)
                        .setMediaItem(MediaItem.Builder().setMediaId(item.itemId).setMediaMetadata(metadata).build())
                        .setMediaMetadata(metadata)
                        .setDurationUs(if (item.durationMs > 0) Util.msToUs(item.durationMs) else androidx.media3.common.C.TIME_UNSET)
                        .setIsSeekable(target.canSeek)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .setPlayWhenReady(target.playing || target.starting, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (target.starting) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlaybackParameters(PlaybackParameters(target.speed))
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(MAX_VOLUME).build())
            .setDeviceVolume(((level ?: 0.0) * MAX_VOLUME).roundToInt())
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controls.command("""{"kind":"Play"}""", onlyAlone = false)
        else controls.command("""{"kind":"Pause"}""", onlyAlone = true)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                controls.command("""{"kind":"Skip","play":${target?.nextPlays == true}}""", onlyAlone = false)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controls.command("""{"kind":"Previous"}""", onlyAlone = false)
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> controls.command("""{"kind":"Seek","positionMs":$positionMs}""", onlyAlone = false)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> = setLevel(deviceVolume.toDouble() / MAX_VOLUME)

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = setLevel((level() ?: 0.0) + STEP)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = setLevel((level() ?: 0.0) - STEP)

    private fun setLevel(level: Double): ListenableFuture<*> {
        // In whole steps of the volume panel, so the room hears what it shows.
        val wanted = ((level.coerceIn(0.0, 1.0) * MAX_VOLUME).roundToInt().toDouble() / MAX_VOLUME)
        asked = wanted
        askedAt = SystemClock.elapsedRealtime()
        Log.d("YtmpPlayback", "speakers' volume ${(wanted * 100).roundToInt()}%")
        controls.setVolumes(group.setTo(speakers, wanted))
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MAX_VOLUME = 100
        /** A volume button press: 5%, as a Chromecast's own steps. */
        const val STEP = 0.05
        const val ASKED_FOR_MS = 3000L
    }
}
