package dev.trashpanda.ytmp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TeeDataSource
import dev.trashpanda.ytmp.host.LocalHost
import dev.trashpanda.ytmp.host.SongCache
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

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
 *
 * Songs play from the host's direct stream URL. When that doesn't work here (YouTube's links
 * are tied to the host's internet connection), the phone looks songs up itself with its own
 * YouTube Music module for the rest of the room, and streams through the host only when that
 * fails too (see docs/implementation/playback-sync.md).
 *
 * Before all that, a song downloaded or cached on this phone plays from here; what plays from
 * elsewhere goes into the song cache as it passes ([CacheSink], docs/implementation/song-cache.md).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exo: ExoPlayer
    private lateinit var session: MediaSession

    private val sync = SyncCorrection()
    private var settledAt = 0L

    /** Where the audio really is (ExoPlayer's own position moves in steps). */
    private lateinit var audioClock: AudioClock

    /** The room's clock offset as used here: follows the target's gently (see [OFFSET_SLEW_MS][SyncCorrection.OFFSET_SLEW_MS]). */
    private var clockOffset: Double? = null

    private var loadedItemId: String? = null
    /** The song of [loadedItemId], for the song cache. */
    private var loadedSong: TargetItem? = null
    /** Songs the player may keep as they play (by song ID): [CacheSink] asks. Any thread. */
    private val copying = java.util.concurrent.ConcurrentHashMap<String, Song>()
    /** This item's file on the phone didn't play: from elsewhere then. */
    private var savedFailedItemId: String? = null
    private val cache get() = (application as YtmpApp).host.cache
    /** The song this phone told the room it has loaded ([reportReady]). */
    private var readySent: String? = null
    private var loadedUrl: String? = null

    /** Where songs in this room come from, after what worked so far. */
    private var preferred = Source.DIRECT

    /** Where the loaded song comes from. */
    private var source = Source.DIRECT

    /** This item goes through the host: its own link didn't work. */
    private var hostItemId: String? = null

    /** The room [preferred] was learned in. */
    private var sourceRoom: RoomLink? = null

    /** Stream links this phone looked up itself, by song ID. Main thread only. */
    private val ownLinks = mutableMapOf<String, String>()
    private val lookingUp = mutableSetOf<String>()

    /** How often the current song was tried again after it didn't play from anywhere, by item. */
    private val retries = mutableMapOf<String, Int>()

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

    /** The sleep timer's fade-out (0–1), on top of the room's volume. */
    private var sleepFade = 1f

    /** The phone's media volume in the room, which your other devices can set ([RemoteVolume]). */
    private val remoteVolume = RemoteVolume()
    private lateinit var audioManager: AudioManager
    private var volumeReport: Job? = null

    /** The volume buttons (or anything else) changed the media volume: tell the room. */
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reportVolume()
    }

    /** When the player started waiting for data (elapsedRealtime), to log long buffering. */
    private var bufferingSince: Long? = null

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
        // The speed is set on the AudioTrack, so a correction is heard at once (not only after
        // the audio already buffered), and [AudioClock] reads where the audio really is. The
        // equalizer for what the phone plays on now goes over the sound on its way there.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                AudioClock(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(true)
                        .setAudioProcessors(arrayOf<AudioProcessor>(EqualizerProcessor { Equalizers.settings }))
                        .build()
                ).also { audioClock = it }
        }
        exo = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // YouTube's links in pieces: asked for in one go, YouTube sends them too slowly. What
            // it reads goes into the song cache too.
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachingSources()))
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
        audioManager = getSystemService(AudioManager::class.java)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        scope.launch {
            follower.room.collect { room ->
                when (val action = room?.let { remoteVolume.update(it, mediaVolume()) }) {
                    is RemoteVolume.Action.Apply -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        Log.i(TAG, "volume ${(action.volume * 100).roundToInt()}%, set from another device")
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (action.volume * max).roundToInt(), 0)
                        // The phone has fewer steps: the room hears the one it got.
                        reportVolume()
                    }
                    RemoteVolume.Action.Report -> reportVolume()
                    null -> Unit
                }
            }
        }
        scope.launch {
            PlaybackHub.target.collect { target ->
                if (target?.enabled != true) stoppedHere = false
                follower.follow(target?.room?.takeIf { target.enabled })
            }
        }
        scope.launch {
            combine(PlaybackHub.target, follower.room) { target, room -> merge(target, room) }.collect { apply(it) }
        }
        // The sleep timer: runs here, so it works with the page frozen (screen off).
        scope.launch {
            PlaybackHub.sleep.collectLatest { setting ->
                if (setting == null) return@collectLatest setFade(1f)
                Log.i(TAG, "sleep timer: ${setting.endsAt?.let { "in ${(it - System.currentTimeMillis()) / 1000} s" } ?: "at the end of ${current?.item?.songId}"}")
                while (isActive) {
                    val left = sleepLeft(setting)
                    setFade((left.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f))
                    if (left <= 0) return@collectLatest goToSleep()
                    // Once a second, more often while it fades.
                    delay(if (left > SLEEP_FADE_MS + 1000) minOf(1000, left - SLEEP_FADE_MS) else 200)
                }
            }
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

    private fun cachingSources(): DataSource.Factory {
        val chunked = ChunkedDataSource.Factory(DefaultDataSource.Factory(this))
        return DataSource.Factory {
            TeeDataSource(chunked.createDataSource(), CacheSink { songId -> copying[songId]?.let(cache::copy) })
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) =
        super.onUpdateNotification(session, startInForegroundRequired || keepForeground)

    /**
     * A media button (headphones, Bluetooth, lock screen) while nothing plays here: Android starts
     * this service in the foreground for it, and it must show a notification within seconds or
     * the app is stopped as not responding, taking a room this phone hosts with it. Media3 only
     * shows one while a song is loaded here, so a short one is shown and taken away again.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && !keepForeground) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(BUTTON_CHANNEL, "Media buttons", NotificationManager.IMPORTANCE_MIN))
            val notification = Notification.Builder(this, BUTTON_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("YTMP")
                .build()
            try {
                ServiceCompat.startForeground(this, BUTTON_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.w(TAG, "media button: couldn't come to the foreground", e)
            }
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The web app (and with it the room connection) is gone, so playback can't follow the room anymore.
        exo.stop()
        stopSelf()
    }

    override fun onDestroy() {
        if (PlaybackHub.followerCommands == follower::command) PlaybackHub.followerCommands = null
        contentResolver.unregisterContentObserver(volumeObserver)
        holdLocks(false)
        follower.close()
        scope.cancel()
        session.release()
        exo.release()
        super.onDestroy()
    }

    /** The media volume (what the volume buttons change), 0–1. */
    private fun mediaVolume(): Double =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    /** Tells the room the media volume, once it rests a moment (the buttons move it in steps). */
    private fun reportVolume() {
        volumeReport?.cancel()
        volumeReport = scope.launch {
            delay(VOLUME_REPORT_DELAY_MS)
            val room = follower.room.value ?: return@launch
            val volume = mediaVolume()
            if (room.state.participants.firstOrNull { it.id == room.participantId }?.let { it.accountId != null && it.volume != volume } != true) return@launch
            follower.command(ProtocolJson.encodeToString(Command.serializer(), Command.SetDeviceVolume(volume)))
        }
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
                starting = room.state.playback.starting,
                speed = room.state.playback.speed.toFloat(),
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
        if (target?.room != null && target.room.hostUrl != sourceRoom?.hostUrl) {
            sourceRoom = target.room
            preferred = Source.DIRECT
            hostItemId = null
            clockOffset = null
        }
        val item = target?.item
        val from = if (target != null && item != null) sourceFor(target, item) else Source.DIRECT
        val url = target?.let { if (item != null) urlFor(it, item, from) else null }
        if (target == null || !target.enabled || item == null || url == null) {
            exo.playWhenReady = false
            if (target == null || !target.enabled || item == null) unload()
            return
        }

        if (item.itemId != loadedItemId || url != loadedUrl) {
            if (item.itemId != loadedItemId) {
                leaveSong()
                startSong(target, item)
            }
            loadedItemId = item.itemId
            loadedUrl = url
            source = from
            // Kept as it plays (its song ID is the cache key the sink goes by), unless it's on the phone already.
            val keep = item.song?.takeIf { from != Source.SAVED && cache.takes(it) }
            if (keep != null) copying[keep.id] = keep
            Log.i(TAG, "load ${item.songId} \"${item.title}\" ${from.label} (${describe(url)})")
            if (from == Source.OWN) lookUpNext()
            bufferingSince = null
            exo.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(item.itemId)
                    .setUri(url)
                    .setCustomCacheKey(keep?.id)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.artist)
                            .setArtworkUri(item.artUrl?.let(Uri::parse))
                            .setDurationMs(item.durationMs)
                            .build(),
                    )
                    .build(),
                // A song the room starts once everyone has it: exactly from its start.
                expectedPosition(target) + if (target.starting) 0 else sync.seekLeadMs,
            )
            exo.playbackParameters = PlaybackParameters(target.speed)
            exo.prepare()
            sync.onLoaded()
            settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
        }
        exo.volume = target.volume * sleepFade
        exo.setHandleAudioBecomingNoisy(target.headphones != "keep")
        // Skipping silence moves this device ahead of the room; alone, the room follows (see [correct]).
        exo.skipSilenceEnabled = target.skipSilence && target.alone && target.item?.song?.podcast != null
        if (!target.playing || !target.alone) pausingRoom = false
        followRoomJump(target)
        correct(target)
        if (exo.playbackState == Player.STATE_READY) reportReady()
    }

    /** The room waits for its players to load a new song ([PlaybackTarget.starting]): this phone has it. */
    private fun reportReady() {
        val target = current ?: return
        val itemId = loadedItemId ?: return
        if (!target.enabled || !target.starting || target.item?.itemId != itemId || readySent == itemId) return
        readySent = itemId
        Log.d(TAG, "ready to start $itemId")
        PlaybackHub.command("""{"kind":"PlayerReady","itemId":${JSONObject.quote(itemId)}}""")
    }

    private fun sourceFor(target: PlaybackTarget, item: TargetItem): Source = when {
        item.itemId != savedFailedItemId && cache.localUri(item.songId) != null -> Source.SAVED
        item.itemId == hostItemId -> Source.HOST
        preferred == Source.OWN -> if (canLookUp(target, item.songId)) Source.OWN else Source.DIRECT
        else -> preferred
    }

    private fun urlFor(target: PlaybackTarget, item: TargetItem, from: Source): String? = when (from) {
        Source.SAVED -> cache.localUri(item.songId)
        // A song the room's host has cached: a path on that host.
        Source.DIRECT -> target.streamUrl?.let { if (it.startsWith("/")) (target.room?.hostUrl ?: LocalHost.LOCAL_URL).trimEnd('/') + it else it }
        Source.HOST -> target.proxyUrl
        Source.OWN -> ownLink(item.songId) ?: run { lookUp(item.songId); null }
    }

    /** A YouTube song in a room on another host: this phone's own links work better there. */
    private fun canLookUp(target: PlaybackTarget, songId: String) =
        songId.startsWith("ytm:") && target.room != null && !isOnThisPhone(target.room)

    private fun isOnThisPhone(room: RoomLink) = Uri.parse(room.hostUrl).host in listOf("127.0.0.1", "localhost")

    /** A link looked up earlier, while it has a while left. */
    private fun ownLink(songId: String): String? {
        val url = ownLinks[songId] ?: return null
        val expires = Uri.parse(url).getQueryParameter("expire")?.toLongOrNull() ?: 0
        if (expires - System.currentTimeMillis() / 1000 > OWN_LINK_MARGIN_S) return url
        ownLinks.remove(songId)
        return null
    }

    /** Looks [songId] up with the phone's own YouTube Music module, then plays it if it's still the song. */
    private fun lookUp(songId: String) {
        if (!lookingUp.add(songId)) return
        scope.launch {
            // Starting Python takes a moment the first time: not on the main thread.
            val result = runCatching { withContext(Dispatchers.IO) { (application as YtmpApp).host.ytm }.stream(songId).url }
            lookingUp.remove(songId)
            result.onSuccess { ownLinks[songId] = it }.onFailure { e ->
                val target = current ?: return@onFailure
                val item = target.item?.takeIf { it.songId == songId } ?: return@onFailure
                Log.w(TAG, "looking up $songId on this phone failed: ${e.message}")
                report(target, "app: looking up $songId on the phone failed (${e.message}), playing through the host")
                hostItemId = item.itemId
            }
            current?.let(::apply)
        }
    }

    /** Looks the next song up ahead of time, so it starts without waiting. */
    private fun lookUpNext() {
        val state = lastFollowed?.state ?: return
        val next = (state.queue.firstOrNull() ?: state.autoplay.firstOrNull())?.song?.id ?: return
        if (next.startsWith("ytm:") && ownLink(next) == null) lookUp(next)
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
        settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
    }

    /** Keeps the local position close to the host's: a little faster or slower for small drift, a seek for large. */
    private fun correct(target: PlaybackTarget) {
        if (!target.enabled || target.item?.itemId != loadedItemId) return
        followClockOffset(target)
        val expected = expectedPosition(target)

        if (!target.playing || pausingRoom) {
            exo.playWhenReady = false
            if (abs(exo.currentPosition - expected) > PAUSED_TOLERANCE_MS) exo.seekTo(expected)
            return
        }
        exo.playWhenReady = true
        if (exo.playbackState == Player.STATE_ENDED) {
            // At the end, but the room started the song over (repeat one): from where the room is.
            if (expected < (target.item?.durationMs ?: 0) - SyncCorrection.SEEK_THRESHOLD_MS) {
                Log.d(TAG, "the room plays this song again -> seek to $expected")
                exo.seekTo(expected + sync.seekLeadMs)
                settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
            }
            return
        }
        // Only measure while actually playing, and not right after a seek: the position needs a moment to settle.
        if (!exo.isPlaying) {
            settledAt = maxOf(settledAt, SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS)
        }
        if (SystemClock.elapsedRealtime() < settledAt) return
        val speed = exo.playbackParameters.speed
        val drift = (audioClock.positionMs(speed) ?: return) - expected
        // Corrections are on top of the room's speed (an episode played faster or slower).
        val ownSpeed = SyncCorrection.ownSpeed(speed, target.speed)

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

        val fix = sync.onDrift(drift, expected, ownSpeed, target.alone)
        if (fix.seekTo != null) {
            Log.d(TAG, "drift $drift ms -> seek (lead ${sync.seekLeadMs} ms)")
            exo.playbackParameters = PlaybackParameters(fix.speed * target.speed)
            exo.seekTo(fix.seekTo)
            settledAt = SystemClock.elapsedRealtime() + SyncCorrection.SETTLE_MS
        } else if (fix.speed != ownSpeed) {
            Log.d(TAG, "drift $drift ms -> speed ${fix.speed} (room ${target.speed})")
            exo.playbackParameters = PlaybackParameters(fix.speed * target.speed)
        }
    }

    /**
     * Moves [clockOffset] toward the room's latest estimate. Estimates from pings differ by
     * some ms each time; taken at once, each would look like drift to fix.
     */
    private fun followClockOffset(target: PlaybackTarget) {
        val wanted = target.clockOffset
        val used = clockOffset
        clockOffset = if (used == null || abs(wanted - used) > SyncCorrection.OFFSET_JUMP_MS) wanted
        else used + (wanted - used).coerceIn(-SyncCorrection.OFFSET_SLEW_MS, SyncCorrection.OFFSET_SLEW_MS)
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
        SyncCorrection.targetPosition(clockOffset?.let { target.copy(clockOffset = it) } ?: target, System.currentTimeMillis())
            .coerceAtMost(maxOf(0, target.item?.durationMs ?: 0))

    /**
     * A new song here. In a room elsewhere it counts as a play for this phone's song cache (rooms
     * on this phone count their own).
     */
    private fun startSong(target: PlaybackTarget, item: TargetItem) {
        loadedSong = item
        cache.playingHere = item.songId
        val song = item.song ?: return
        if (target.room != null && !isOnThisPhone(target.room)) cache.started(song)
    }

    /** The player moves on: what it kept of the song is done (or dropped), and was it heard long enough to count. */
    private fun leaveSong() {
        val item = loadedSong ?: return
        loadedSong = null
        copying.remove(item.songId)
        cache.leave(item.songId)
        if (cache.playingHere == item.songId) cache.playingHere = null
        val room = sourceRoom
        val song = item.song ?: return
        if (room != null && !isOnThisPhone(room)) {
            val heard = exo.currentPosition
            cache.finished(song, heard >= SongCache.COUNTS_AFTER_MS || heard >= item.durationMs - END_MARGIN_MS)
        }
    }

    private fun unload() {
        if (loadedItemId == null) return
        leaveSong()
        loadedItemId = null
        loadedUrl = null
        exo.stop()
        exo.clearMediaItems()
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val target = current
            Log.w(
                TAG,
                "player error ${error.errorCodeName} on ${target?.item?.songId} ${source.label} " +
                    "at ${exo.currentPosition / 1000} s (${describe(loadedUrl)}): ${error.message}; cause: ${generateSequence(error.cause) { it.cause }.joinToString(" <- ")}",
            )
            target ?: return
            val item = target.item
            val from = source
            val next = when {
                // The file on the phone didn't play (gone, broken): as if it weren't there.
                item != null && from == Source.SAVED -> Source.DIRECT.also { savedFailedItemId = item.itemId }
                item == null || from == Source.HOST -> null
                // The direct URL is probably tied to the host's IP: look songs up here for the rest
                // of the room, or stream through the host.
                from == Source.DIRECT && canLookUp(target, item.songId) -> Source.OWN.also { preferred = it }
                from == Source.DIRECT && item.songId.startsWith("ytm:") -> Source.HOST.also { preferred = it }
                // This phone's own link (or a host's file) didn't play: this song through the host.
                else -> Source.HOST.also {
                    ownLinks.remove(item.songId)
                    hostItemId = item.itemId
                }
            }
            if (item != null && next != null && (next != Source.HOST || target.proxyUrl != null)) {
                Log.i(TAG, "${item.songId} doesn't play ${from.label}, now ${next.label}")
                report(target, "app: ${from.label} failed for ${item.songId} (${error.errorCodeName}), now ${next.label}")
                loadedItemId = null
                apply(target)
            } else if (item != null && (retries[item.itemId] ?: 0) < RETRY_DELAYS_MS.size) {
                // YouTube sometimes refuses a song for a while, from every connection (403 after
                // its first MB): rather than staying quiet for the whole song, try again later.
                val attempt = retries[item.itemId] ?: 0
                retries.keys.retainAll(setOf(item.itemId))
                retries[item.itemId] = attempt + 1
                val wait = RETRY_DELAYS_MS[attempt]
                Log.i(TAG, "${item.songId} doesn't play from anywhere, trying again in ${wait / 1000} s")
                report(target, "app: can't play ${item.songId} through the host either (${error.errorCodeName}), trying again in ${wait / 1000} s")
                PlaybackHub.status("retrying")
                reportReady()
                scope.launch {
                    delay(wait)
                    val now = current ?: return@launch
                    if (now.item?.itemId != item.itemId || !now.enabled) return@launch
                    // From the start again, with fresh links: this phone's, and the host's
                    // (its proxy forgets a link YouTube refused).
                    ownLinks.remove(item.songId)
                    if (hostItemId == item.itemId) hostItemId = null
                    loadedItemId = null
                    apply(now)
                }
            } else {
                report(target, "app: can't play ${target.item?.songId} through the host either (${error.errorCodeName}: ${error.message})")
                PlaybackHub.status("error")
                // It won't load: the room doesn't wait for it.
                reportReady()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) PlaybackHub.status("playing")
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
            if (playbackState == Player.STATE_READY) reportReady()
            val now = SystemClock.elapsedRealtime()
            if (playbackState == Player.STATE_BUFFERING) {
                if (bufferingSince == null) bufferingSince = now
                return
            }
            val since = bufferingSince ?: return
            bufferingSince = null
            val waited = now - since
            if (playbackState != Player.STATE_READY || waited < LONG_BUFFERING_MS) return
            val target = current
            val line = "buffered ${waited / 100 / 10.0} s at ${exo.currentPosition / 1000} s in ${target?.item?.songId} " +
                "(${source.label}, buffered ahead ${exo.totalBufferedDuration / 1000} s)"
            Log.w(TAG, line)
            if (target != null) report(target, "app: $line")
        }
    }

    /** For the log: where a stream URL points, and when YouTube's link expires; never the whole URL. */
    private fun describe(url: String?): String {
        val uri = url?.let(Uri::parse) ?: return "no link"
        // A file on the phone: the song cache's, or a download in the media store.
        if (uri.scheme == "file") return "the song cache"
        if (uri.scheme == "content") return "a download"
        val expires = uri.getQueryParameter("expire")?.toLongOrNull()?.let { (it - System.currentTimeMillis() / 1000) / 60 }
        return uri.host + (expires?.let { ", expires in $it min" } ?: "")
    }

    /**
     * Also tells the room's host, so its log shows what listeners hear. Not for rooms on this
     * phone: those are in this log already.
     */
    private fun report(target: PlaybackTarget, message: String) {
        val room = target.room ?: return
        if (Uri.parse(room.hostUrl).host in listOf("127.0.0.1", "localhost")) return
        scope.launch(Dispatchers.IO) { HostReport.send(room.hostUrl, room.code, message) }
    }

    /** How long (ms) until the sleep timer goes off; 0 when it's time (the song it waits for the end of has gone). */
    private fun sleepLeft(setting: SleepSetting): Long {
        setting.endsAt?.let { return maxOf(0, it - System.currentTimeMillis()) }
        val target = current ?: return 0
        val item = target.item?.takeIf { it.itemId == setting.itemId } ?: return 0
        // A song without a length can't end by itself.
        if (item.durationMs <= 0) return Long.MAX_VALUE
        return maxOf(0, ((item.durationMs - expectedPosition(target)) / target.speed).toLong())
    }

    private fun setFade(fade: Float) {
        if (fade == sleepFade) return
        sleepFade = fade
        current?.takeIf { it.enabled }?.let { exo.volume = it.volume * fade }
    }

    /**
     * The sleep timer went off: a solo room pauses (silent until it has; if it doesn't, this
     * device stops), in a shared room this device stops playing. Then the timer is off.
     */
    private suspend fun goToSleep() {
        val target = current
        if (target?.solo == true) {
            Log.i(TAG, "sleep timer: pausing the room")
            setFade(0f)
            PlaybackHub.command("""{"kind":"Pause"}""")
            withTimeoutOrNull(PAUSE_WAIT_MS) { while (current?.let { it.playing || it.starting } == true) delay(100) }
            if (current?.playing == true) stopHere()
        } else if (target?.enabled == true) {
            Log.i(TAG, "sleep timer: stopping here")
            stopHere()
        }
        PlaybackHub.sleep.value = null
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

    /** Where the audio comes from: the host's link, a link this phone looked up, or the host streaming it. */
    private enum class Source(val label: String) {
        SAVED("from this phone"),
        DIRECT("direct"),
        OWN("looked up on this phone"),
        HOST("through the host"),
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
            PlaybackHub.command("""{"kind":"Skip","play":${PlaybackHub.target.value?.nextPlays == true}}""")
        }

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() = PlaybackHub.command("""{"kind":"Previous"}""")

        override fun seekToPreviousMediaItem() = seekToPrevious()

        override fun seekTo(positionMs: Long) = PlaybackHub.command("""{"kind":"Seek","positionMs":$positionMs}""")
    }

    companion object {
        private const val TAG = "YtmpSync"
        private const val BUTTON_CHANNEL = "media_buttons"
        private const val BUTTON_NOTIFICATION = 7
        private const val PAUSED_TOLERANCE_MS = 250L
        private const val PAUSE_WAIT_MS = 3000L
        /** Buffering this long (ms) is logged, and reported to the host. */
        private const val LONG_BUFFERING_MS = 3000L

        /** A song that didn't play from anywhere is tried again after these waits (ms). */
        private val RETRY_DELAYS_MS = listOf(10_000L, 30_000L)

        /** The room's answer to a seek from here lands about where it was asked to. */
        private const val ROOM_SEEK_MATCH_MS = 1000L

        /** Use a link this phone looked up only while it has at least this long (s) left. */
        private const val OWN_LINK_MARGIN_S = 600L

        /** The sleep timer fades the sound out over this long (ms) before it goes off. */
        private const val SLEEP_FADE_MS = 10_000L
        private const val VOLUME_REPORT_DELAY_MS = 300L

        /** This close to its end (ms), a song counts as played. */
        private const val END_MARGIN_MS = 5_000L
    }
}
