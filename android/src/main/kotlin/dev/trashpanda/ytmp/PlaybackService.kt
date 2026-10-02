package dev.trashpanda.ytmp

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Plays the room's audio with ExoPlayer, in sync with the host.
 *
 * The media session gives lock screen, notification and headphone/Bluetooth controls. Those
 * controls don't act on the local player: they become room commands (see [RoomPlayer]), and
 * the resulting room state comes back through [PlaybackHub.target].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exo: ExoPlayer
    private lateinit var session: MediaSession

    private var loadedItemId: String? = null
    private var loadedUrl: String? = null
    private var useProxy = false

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, RoomPlayer(exo)).setSessionActivity(openApp).build()

        scope.launch { PlaybackHub.target.collect { apply(it) } }
        // Drift correction: the host's clock keeps moving, so check regularly.
        scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                PlaybackHub.target.value?.let { correct(it) }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The web app (and with it the room connection) is gone, so playback can't follow the room anymore.
        exo.stop()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        exo.release()
        super.onDestroy()
    }

    private fun apply(target: PlaybackTarget?) {
        val item = target?.item
        val url = target?.let { if (useProxy) it.proxyUrl else it.streamUrl }
        if (target == null || !target.enabled || item == null || url == null) {
            exo.playWhenReady = false
            if (target == null || !target.enabled || item == null) unload()
            return
        }

        if (item.itemId != loadedItemId || url != loadedUrl) {
            loadedItemId = item.itemId
            loadedUrl = url
            exo.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(item.itemId)
                    .setUri(url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.artist)
                            .setArtworkUri(item.artUrl?.let(Uri::parse))
                            .setDurationMs(item.durationMs)
                            .build(),
                    )
                    .build(),
                expectedPosition(target),
            )
            exo.prepare()
        }
        exo.volume = target.volume
        correct(target)
    }

    /** Keeps the local position close to the host's: nudge the speed for small drift, seek for large. */
    private fun correct(target: PlaybackTarget) {
        if (!target.enabled || target.item?.itemId != loadedItemId) return
        val expected = expectedPosition(target)
        val drift = exo.currentPosition - expected

        if (!target.playing) {
            exo.playWhenReady = false
            if (abs(drift) > PAUSED_TOLERANCE_MS) exo.seekTo(expected)
            return
        }
        if (exo.playbackState == Player.STATE_READY) {
            when {
                abs(drift) > SEEK_THRESHOLD_MS -> {
                    exo.seekTo(expected)
                    exo.setPlaybackSpeed(1f)
                }
                abs(drift) > NUDGE_THRESHOLD_MS -> exo.setPlaybackSpeed(if (drift > 0) 0.97f else 1.03f)
                else -> exo.setPlaybackSpeed(1f)
            }
        }
        exo.playWhenReady = true
    }

    private fun expectedPosition(target: PlaybackTarget): Long {
        val hostNow = System.currentTimeMillis() + target.clockOffset.toLong()
        val position = if (target.playing) target.positionMs + (hostNow - target.hostTimeMs) else target.positionMs
        return position.coerceIn(0, maxOf(0, target.item?.durationMs ?: 0))
    }

    private fun unload() {
        if (loadedItemId == null) return
        loadedItemId = null
        loadedUrl = null
        exo.stop()
        exo.clearMediaItems()
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val target = PlaybackHub.target.value ?: return
            if (!useProxy && target.proxyUrl != null) {
                // The direct URL is probably tied to the host's IP; stream through the host instead.
                useProxy = true
                loadedItemId = null
                apply(target)
            } else {
                PlaybackHub.status("error")
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Another app took the audio, or headphones were unplugged: stop playing here,
            // but don't pause the room for everyone else.
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            ) {
                PlaybackHub.status("stopped")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            PlaybackHub.status(if (playbackState == Player.STATE_BUFFERING) "buffering" else "ready")
        }
    }

    /** Turns media controls (lock screen, notification, headphones) into room commands. */
    private class RoomPlayer(player: Player) : ForwardingPlayer(player) {
        private val roomCommands = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*roomCommands.toIntArray()).build()

        override fun isCommandAvailable(command: Int): Boolean = command in roomCommands || super.isCommandAvailable(command)

        override fun play() = PlaybackHub.command("""{"kind":"Play"}""")

        override fun pause() = PlaybackHub.command("""{"kind":"Pause"}""")

        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

        override fun seekToNext() = PlaybackHub.command("""{"kind":"Skip"}""")

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() = PlaybackHub.command("""{"kind":"Previous"}""")

        override fun seekToPreviousMediaItem() = seekToPrevious()

        override fun seekTo(positionMs: Long) = PlaybackHub.command("""{"kind":"Seek","positionMs":$positionMs}""")
    }

    companion object {
        private const val SYNC_INTERVAL_MS = 500L
        private const val SEEK_THRESHOLD_MS = 1500L
        private const val NUDGE_THRESHOLD_MS = 120L
        private const val PAUSED_TOLERANCE_MS = 250L
    }
}
