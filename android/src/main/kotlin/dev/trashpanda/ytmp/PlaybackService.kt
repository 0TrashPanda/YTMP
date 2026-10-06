package dev.trashpanda.ytmp

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    /** Headphones were unplugged and the room was asked to pause: stay quiet until it has. */
    private var pausingRoom = false

    /**
     * Listening alone, this device was behind (or ahead) by itself, e.g. buffering at the start
     * of a song: the room was asked to seek to where this device is. Until the room answers.
     */
    private var roomSeekTo: Long? = null

    /** The room didn't follow a [roomSeekTo] during this song, so don't keep asking. */
    private var roomWontFollow: String? = null

    /** The room's last play position and time, to see when someone seeks, pauses or plays. */
    private var lastAnchor: Triple<String?, Long, Long>? = null

    /**
     * The room plays here: stay awake and on Wi-Fi also between songs. ExoPlayer's own locks
     * end with each song, and some phones (a Galaxy A20e) drop Wi-Fi within a second in deep
     * sleep, so the next song could never load.
     */
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    /**
     * "Play here" is on and the room has a song, playing or paused: the player stays in the
     * foreground. Between songs that keeps its network in deep sleep (Android cuts background
     * apps off), and paused it stays on the lock screen to play again, like YTM.
     */
    private var keepForeground = false

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
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmp:playback").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION") // What ExoPlayer itself uses; the replacement only works with the screen on.
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytmp:playback").apply { setReferenceCounted(false) }

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
        // Drift correction: the host's clock keeps moving, so check regularly, but only while
        // this device plays (a paused or empty player has nothing to correct: [apply] handles it).
        scope.launch {
            measuring.collectLatest { on ->
                while (on && isActive) {
                    delay(sampleInterval())
                    current?.let { correct(it) }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) =
        super.onUpdateNotification(session, startInForegroundRequired || keepForeground)

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The web app (and with it the room connection) is gone, so playback can't follow the room anymore.
        exo.stop()
        stopSelf()
    }

    override fun onDestroy() {
        if (PlaybackHub.followerCommands == follower::command) PlaybackHub.followerCommands = null
        holdLocks(false)
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

    /** Something plays here, so [correct] runs every [sampleInterval]. */
    private val measuring = MutableStateFlow(false)

    /**
     * Listening alone, only big drift matters ([SyncCorrection.ALONE_TOLERANCE_MS]), so after the
     * first measurement following a load or seek (which catches a slow start), measure less often:
     * fewer wake-ups with the screen off.
     */
    private fun sampleInterval(): Long {
        val settledFor = SystemClock.elapsedRealtime() - settledAt
        return if (current?.alone == true && settledFor > SyncCorrection.QUICK_MEASURE_MS) SyncCorrection.ALONE_SAMPLE_INTERVAL_MS
        else SyncCorrection.SAMPLE_INTERVAL_MS
    }

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
        measuring.value = target != null && target.enabled && target.playing && target.item != null
        holdLocks(measuring.value)
        setKeepForeground(target != null && target.enabled && target.item != null)
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
        exo.setHandleAudioBecomingNoisy(target.headphones != "keep")
        if (!target.playing || !target.alone) pausingRoom = false
        followRoomJump(target)
        correct(target)
    }

    /**
     * Listening alone: when the room jumps (someone seeks or skips ahead 30 s), go there right
     * away instead of after the usual measuring. With others listening, [correct] handles it.
     */
    private fun followRoomJump(target: PlaybackTarget) {
        val anchor = Triple(target.item?.itemId, target.positionMs, target.hostTimeMs)
        val previous = lastAnchor
        lastAnchor = anchor
        if (previous == null || previous == anchor || previous.first != anchor.first) return
        val asked = roomSeekTo
        if (asked != null && abs(target.positionMs - asked) < ROOM_SEEK_MATCH_MS) {
            // The room followed this device: nothing to do here.
            roomSeekTo = null
            return
        }
        if (!target.alone || !target.playing || SystemClock.elapsedRealtime() < settledAt) return
        val expected = expectedPosition(target)
        if (abs(exo.currentPosition - expected) <= SyncCorrection.ALONE_TOLERANCE_MS) return
        Log.d(TAG, "room jumped -> seek")
        exo.seekTo(expected + sync.seekLeadMs)
        sync.resetSamples()
        settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
    }

    /** Keeps the local position close to the host's: nudge the speed for small drift, seek for large. */
    private fun correct(target: PlaybackTarget) {
        if (!target.enabled || target.item?.itemId != loadedItemId) return
        val expected = expectedPosition(target)
        val reading = exo.currentPosition - expected

        if (!target.playing || pausingRoom) {
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

        if (roomSeekTo != null) roomWontFollow = loadedItemId
        if (target.alone && abs(drift) > SyncCorrection.ALONE_ROOM_TOLERANCE_MS && roomSeekTo == null &&
            abs(drift) <= SyncCorrection.MAX_ROOM_FOLLOW_MS && roomWontFollow != loadedItemId
        ) {
            // Nobody else listens, so rather than jumping (which you hear), the room moves to
            // where this device is. If the room doesn't (no permission to seek), the next
            // measurement seeks here as before.
            val position = maxOf(0, exo.currentPosition - target.syncOffsetMs)
            Log.d(TAG, "alone, drift $drift ms -> room seeks to $position")
            roomSeekTo = position
            PlaybackHub.command("""{"kind":"Seek","positionMs":$position}""")
            settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
            return
        }
        roomSeekTo = null

        val seekTo = sync.onSettledDrift(drift, expected, target.alone) ?: return
        Log.d(TAG, "drift $drift ms -> seek (lead ${sync.seekLeadMs} ms)")
        exo.seekTo(seekTo)
        settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
    }

    private fun holdLocks(hold: Boolean) {
        if (hold == wakeLock.isHeld) return
        if (hold) {
            wakeLock.acquire()
            wifiLock.acquire()
        } else {
            wakeLock.release()
            wifiLock.release()
        }
    }

    private fun setKeepForeground(keep: Boolean) {
        if (keep == keepForeground) return
        keepForeground = keep
        triggerNotificationUpdate()
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
            if (playWhenReady) return
            val target = current
            // Headphones unplugged, set to pause, and nobody else listens: pause the room, like YTM.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY && target?.headphones == "pause" && target.alone) {
                pausingRoom = true
                PlaybackHub.command("""{"kind":"Pause"}""")
                // If the room doesn't pause (no connection, no permission), stop playing here instead.
                scope.launch {
                    delay(PAUSE_WAIT_MS)
                    if (pausingRoom && current?.playing == true) stopHere()
                    pausingRoom = false
                }
                return
            }
            // Another app took the audio, or headphones were unplugged: stop playing here,
            // but don't pause the room for everyone else.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            ) {
                stopHere()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            PlaybackHub.status(if (playbackState == Player.STATE_BUFFERING) "buffering" else "ready")
        }
    }

    /** Stops playing on this device (not in the room). */
    private fun stopHere() {
        // Stop right away: the page (which switches "Play here" off) may be frozen for a while.
        stoppedHere = true
        current = current?.copy(enabled = false)
        measuring.value = false
        holdLocks(false)
        setKeepForeground(false)
        exo.playWhenReady = false
        follower.command("""{"kind":"SetListening","on":false}""")
        PlaybackHub.status("stopped")
    }

    /** Turns media controls (lock screen, notification, headphones) into room commands. */
    private class RoomPlayer(player: Player) : ForwardingPlayer(player) {
        private val roomCommands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        ).build()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(roomCommands).build()

        override fun isCommandAvailable(command: Int): Boolean = roomCommands.contains(command) || super.isCommandAvailable(command)

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
        private const val PAUSE_WAIT_MS = 3000L

        /** The room's answer to a seek from here lands about where it was asked to. */
        private const val ROOM_SEEK_MATCH_MS = 1000L
    }
}
