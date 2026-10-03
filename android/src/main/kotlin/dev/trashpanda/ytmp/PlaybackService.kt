package dev.trashpanda.ytmp

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Plays the room's audio with ExoPlayer, in sync with the host.
 *
 * The media session gives lock screen, notification and headphone/Bluetooth controls. Those
 * controls don't act on the local player: they become room commands (see [RoomPlayer]), and
 * the resulting room state comes back through [PlaybackHub.target].
 *
 * What should play comes from the page ([PlaybackHub.target]), but while the player's own
 * room connection ([RoomFollower]) is up, the room's song, stream and clock come from that
 * instead: Android freezes the page after a few minutes with the screen off.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exo: ExoPlayer
    private lateinit var session: MediaSession

    private val sync = SyncCorrection()
    private var settledAt = 0L

    private var loadedItemId: String? = null
    private var loadedUrl: String? = null
    private var useProxy = false

    private val follower = RoomFollower()

    /** What the player follows right now: the page's target, with the room parts from [follower]. */
    private var current: PlaybackTarget? = null

    /** The last room state from [follower], kept while it reconnects. */
    private var lastFollowed: FollowedRoom? = null

    /** Stopped here because another app took the audio; until the page has switched "Play here" off. */
    private var stoppedHere = false

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

        PlaybackHub.followerCommands = follower::command
        scope.launch {
            PlaybackHub.target.collect { target ->
                if (target?.enabled != true) stoppedHere = false
                follower.follow(target?.room?.takeIf { target.enabled })
            }
        }
        scope.launch {
            combine(PlaybackHub.target, follower.room) { target, room -> merge(target, room) }.collect { apply(it) }
        }
        // Drift correction: the host's clock keeps moving, so check regularly.
        scope.launch {
            while (isActive) {
                delay(SyncCorrection.SAMPLE_INTERVAL_MS)
                current?.let { correct(it) }
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
        if (PlaybackHub.followerCommands == follower::command) PlaybackHub.followerCommands = null
        follower.close()
        scope.cancel()
        session.release()
        exo.release()
        super.onDestroy()
    }

    /** The page's [target], with the room's song, stream and clock from [live] when it's the same room. */
    private fun merge(target: PlaybackTarget?, live: FollowedRoom?): PlaybackTarget? {
        if (target == null) return null
        if (live != null) lastFollowed = live
        // While the follower reconnects, its last state is still better than a frozen page's,
        // unless the page has heard of something newer (every change moves the host time).
        val room = live ?: lastFollowed?.takeIf { it.link == target.room && it.state.playback.hostTimeMs >= target.hostTimeMs }
        val followed = if (room != null && room.link == target.room) {
            val now = room.state.nowPlaying
            target.copy(
                item = now?.let { TargetItem.of(it.item.itemId, it.item.song) },
                streamUrl = now?.streamUrl,
                proxyUrl = now?.let { "${room.link.hostUrl.trimEnd('/')}/api/audio/${Uri.encode(it.item.song.id)}" },
                playing = room.state.playback.playing,
                positionMs = room.state.playback.positionMs,
                hostTimeMs = room.state.playback.hostTimeMs,
                clockOffset = room.clockOffset,
                alone = room.alone,
            )
        } else {
            target
        }
        return if (stoppedHere) followed.copy(enabled = false) else followed
    }

    private var lastTarget: PlaybackTarget? = null

    private fun apply(target: PlaybackTarget?) {
        current = target
        lastTarget?.let { old ->
            if (target != null && (old.clockOffset != target.clockOffset || old.hostTimeMs != target.hostTimeMs)) {
                val now = System.currentTimeMillis()
                Log.d(TAG, "target moved ${SyncCorrection.targetPosition(target, now) - SyncCorrection.targetPosition(old, now)} ms " +
                    "(clock offset ${old.clockOffset.toLong()} -> ${target.clockOffset.toLong()})")
            }
        }
        lastTarget = target
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
                expectedPosition(target) + sync.seekLeadMs,
            )
            exo.prepare()
            sync.onLoaded()
            settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
        }
        exo.volume = target.volume
        correct(target)
    }

    /** Keeps the local position close to the host's: nudge the speed for small drift, seek for large. */
    private fun correct(target: PlaybackTarget) {
        if (!target.enabled || target.item?.itemId != loadedItemId) return
        val expected = expectedPosition(target)
        val reading = exo.currentPosition - expected

        if (!target.playing) {
            exo.playWhenReady = false
            if (abs(reading) > PAUSED_TOLERANCE_MS) exo.seekTo(expected)
            return
        }
        exo.playWhenReady = true
        // Only measure while actually playing, and not right after a seek: the position needs a moment to settle.
        if (!exo.isPlaying) {
            settledAt = maxOf(settledAt, SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS)
        }
        if (SystemClock.elapsedRealtime() < settledAt) {
            sync.resetSamples()
            return
        }
        val drift = sync.addSample(reading) ?: return

        val seekTo = sync.onSettledDrift(drift, expected, target.alone) ?: return
        Log.d(TAG, "drift $drift ms -> seek (lead ${sync.seekLeadMs} ms)")
        exo.seekTo(seekTo)
        settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
    }

    private fun expectedPosition(target: PlaybackTarget): Long =
        SyncCorrection.targetPosition(target, System.currentTimeMillis())
            .coerceAtMost(maxOf(0, target.item?.durationMs ?: 0))

    private fun unload() {
        if (loadedItemId == null) return
        loadedItemId = null
        loadedUrl = null
        exo.stop()
        exo.clearMediaItems()
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val target = current ?: return
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
                // Stop right away: the page (which switches "Play here" off) may be frozen for a while.
                stoppedHere = true
                current = current?.copy(enabled = false)
                follower.command("""{"kind":"SetListening","on":false}""")
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

        override fun play() {
            Log.d(TAG, "media session: play")
            PlaybackHub.command("""{"kind":"Play"}""")
        }

        override fun pause() {
            Log.d(TAG, "media session: pause")
            PlaybackHub.command("""{"kind":"Pause"}""")
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

        override fun seekToNext() {
            Log.d(TAG, "media session: next")
            PlaybackHub.command("""{"kind":"Skip"}""")
        }

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() = PlaybackHub.command("""{"kind":"Previous"}""")

        override fun seekToPreviousMediaItem() = seekToPrevious()

        override fun seekTo(positionMs: Long) = PlaybackHub.command("""{"kind":"Seek","positionMs":$positionMs}""")
    }

    companion object {
        private const val TAG = "YtmpSync"
        private const val PAUSED_TOLERANCE_MS = 250L
    }
}
