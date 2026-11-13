package dev.trashpanda.ytmp

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/** What the media session shows, for the home screen widget: the song (a queue item), and whether it plays. */
data class WidgetNow(val item: TargetItem?, val playing: Boolean)

/**
 * The home screen widget, like YouTube Music's (docs/features/platforms.md#android-app):
 * the song's art in a circle, play/pause and the thumbs up. What it shows comes from [Widget].
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widget.render(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = Widget.render(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_PAUSE -> Widget.playPause(context)
            ACTION_LIKE -> {
                val done = goAsync()
                Widget.toggleLike(context) { done.finish() }
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "dev.trashpanda.ytmp.widget.PLAY_PAUSE"
        const val ACTION_LIKE = "dev.trashpanda.ytmp.widget.LIKE"
    }
}

/**
 * What the widget shows, and its buttons. The song and play state come from the media session
 * ([PlaybackHub.session]), or the page's room when the player isn't running; the last song stays
 * (paused) when the app is gone, like in YouTube Music.
 */
object Widget {
    private const val TAG = "YtmpWidget"
    private const val PREFS = "widget"
    private const val ART_FILE = "widget_art.png"
    /** The art's size in pixels: sharp on a big widget, and well below what a widget may send. */
    private const val ART_PX = 480
    /** The size (dp) before Android says how big the widget is. */
    private const val DEFAULT_DP = 140f

    private data class Shown(
        val item: TargetItem? = null,
        val playing: Boolean = false,
        /** The app has the room: the buttons work, else they open the app. */
        val live: Boolean = false,
        /** Your thumbs up on the song; null: no YouTube Music sign-in (the button hides). */
        val liked: Boolean? = null,
        val art: Bitmap? = null,
        /** The art URL [art] is of. */
        val artOf: String? = null,
    )

    private val shown = MutableStateFlow(Shown())
    @SuppressLint("StaticFieldLeak") // The application's context.
    private lateinit var app: Context
    private lateinit var scope: CoroutineScope

    /** Follows the player from the app's start (on the main thread). */
    fun start(context: Context, scope: CoroutineScope) {
        app = context.applicationContext
        this.scope = scope
        shown.value = restore()
        scope.launch {
            combine(PlaybackHub.target, PlaybackHub.session) { target, session ->
                (session ?: target?.let { WidgetNow(it.item, it.playing) }) to (target != null)
            }.collect { (now, live) ->
                shown.update { it.copy(item = now?.item ?: it.item, playing = now?.playing == true, live = live) }
            }
        }
        // The art, once per song.
        scope.launch {
            shown.map { it.item?.artUrl }.distinctUntilChanged().collect { url ->
                if (url == null || url == shown.value.artOf) return@collect
                val saved = prefs().getString("artUrl", null)
                val art = withContext(Dispatchers.IO) {
                    if (url == saved) readArt() ?: loadArt(url)?.also { saveArt(url, it) }
                    else loadArt(url)?.also { saveArt(url, it) }
                }
                if (art != null && shown.value.item?.artUrl == url) shown.update { it.copy(art = art, artOf = url) }
            }
        }
        // The thumbs up: from the page's sign-in, looked up once per song.
        scope.launch {
            var asked: Pair<WidgetLikes.Access, String>? = null
            combine(shown.map { it.item?.songId }.distinctUntilChanged(), WidgetLikes.access, WidgetLikes.ratings, ::Triple).collect { (songId, access, ratings) ->
                // Only YouTube Music songs have a like there (not local music).
                val signedIn = access != null && songId != null && songId.startsWith("ytm:")
                shown.update { it.copy(liked = if (signedIn) ratings[songId] ?: false else null) }
                if (signedIn && songId !in ratings && asked != access to songId) {
                    asked = access to songId
                    launch { WidgetLikes.load(songId) }
                }
            }
        }
        scope.launch {
            var last: Shown? = null
            shown.collect { now ->
                if (now.item != last?.item) save(now.item)
                if (now != last) render(app)
                last = now
            }
        }
    }

    /** Play/pause, as on the lock screen; without the room in the app it opens the app. */
    fun playPause(context: Context) {
        PlaybackHub.sessionPlayPause?.let { return it() }
        val target = PlaybackHub.target.value
        if (target?.room != null && target.item != null) PlaybackHub.command(if (target.playing) """{"kind":"Pause"}""" else """{"kind":"Play"}""")
        else openApp(context)
    }

    /** Thumbs up on the song, or takes it back. */
    fun toggleLike(context: Context, done: () -> Unit) {
        val songId = shown.value.item?.songId
        val liked = shown.value.liked
        if (songId == null || liked == null || !::scope.isInitialized) {
            openApp(context)
            return done()
        }
        scope.launch {
            try {
                WidgetLikes.set(songId, !liked)
            } finally {
                done()
            }
        }
    }

    private fun openApp(context: Context) {
        // From a click on the widget; Android may not let a broadcast open it (then a second tap does, see [render]).
        runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "couldn't open the app", it) }
    }

    /** Draws every widget again. */
    fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
        if (ids.isEmpty()) return
        val now = shown.value
        for (id in ids) {
            runCatching { manager.updateAppWidget(id, views(context, now, manager.getAppWidgetOptions(id))) }
                .onFailure { Log.w(TAG, "couldn't update the widget", it) }
        }
    }

    private fun views(context: Context, now: Shown, options: Bundle?) = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val item = now.item
        // A new song doesn't show the last one's art while its own loads.
        if (item != null && now.art != null && now.artOf == item.artUrl) setImageViewBitmap(R.id.widget_art, now.art) else setImageViewResource(R.id.widget_art, R.drawable.widget_art_empty)
        setContentDescription(R.id.widget_art, item?.let { "${it.title}, ${it.artist}. ${context.getString(R.string.widget_open)}" } ?: context.getString(R.string.widget_open))
        setOnClickPendingIntent(R.id.widget_art, open)

        setImageViewResource(R.id.widget_play, if (now.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        setContentDescription(R.id.widget_play, context.getString(if (now.playing) R.string.widget_pause else R.string.widget_play))
        setOnClickPendingIntent(R.id.widget_play, if (now.live) broadcast(context, NowPlayingWidget.ACTION_PLAY_PAUSE) else open)

        val liked = now.liked.takeIf { item != null }
        setViewVisibility(R.id.widget_like, if (liked == null) View.GONE else View.VISIBLE)
        setImageViewResource(R.id.widget_like, if (liked == true) R.drawable.ic_widget_liked else R.drawable.ic_widget_like)
        setContentDescription(R.id.widget_like, context.getString(if (liked == true) R.string.widget_unlike else R.string.widget_like))
        setOnClickPendingIntent(R.id.widget_like, broadcast(context, NowPlayingWidget.ACTION_LIKE))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) fit(options)
    }

    /** Scales the circle and the buttons to the widget's size (Android 12 and later). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun RemoteViews.fit(options: Bundle?) {
        // Upright, a widget is its smallest width wide and its largest height high.
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 }?.toFloat() ?: DEFAULT_DP
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 }?.toFloat() ?: DEFAULT_DP
        val side = min(width, height).coerceAtLeast(56f)
        val dp = TypedValue.COMPLEX_UNIT_DIP
        setViewLayoutWidth(R.id.widget_square, side, dp)
        setViewLayoutHeight(R.id.widget_square, side, dp)
        // As in YouTube Music: play/pause a third of the circle, the thumbs up a bit smaller.
        val play = side * 0.33f
        setViewLayoutWidth(R.id.widget_play, play, dp)
        setViewLayoutHeight(R.id.widget_play, play, dp)
        val like = side * 0.27f
        setViewLayoutWidth(R.id.widget_like, like, dp)
        setViewLayoutHeight(R.id.widget_like, like, dp)
        val density = app.resources.displayMetrics.density
        val playPad = (play * 0.24f * density).toInt()
        setViewPadding(R.id.widget_play, playPad, playPad, playPad, playPad)
        val likePad = (like * 0.24f * density).toInt()
        setViewPadding(R.id.widget_like, likePad, likePad, likePad, likePad)
    }

    private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, NowPlayingWidget::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last song, so the widget still shows it after the app (or the phone) restarted. */
    private fun save(item: TargetItem?) {
        item ?: return
        prefs().edit()
            .putString("itemId", item.itemId)
            .putString("songId", item.songId)
            .putString("title", item.title)
            .putString("artist", item.artist)
            .putString("art", item.artUrl)
            .apply()
    }

    private fun restore(): Shown {
        val prefs = prefs()
        val songId = prefs.getString("songId", null) ?: return Shown()
        val item = TargetItem(
            itemId = prefs.getString("itemId", null).orEmpty(),
            songId = songId,
            title = prefs.getString("title", null).orEmpty(),
            artist = prefs.getString("artist", null).orEmpty(),
            artUrl = prefs.getString("art", null),
            durationMs = 0,
        )
        val saved = item.artUrl?.takeIf { it == prefs.getString("artUrl", null) }
        return Shown(item = item, art = saved?.let { readArt() }, artOf = saved)
    }

    private fun readArt(): Bitmap? = runCatching { BitmapFactory.decodeFile(File(app.filesDir, ART_FILE).path) }.getOrNull()

    private fun saveArt(url: String, art: Bitmap) {
        runCatching {
            File(app.filesDir, ART_FILE).outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
            prefs().edit().putString("artUrl", url).apply()
        }.onFailure { Log.w(TAG, "couldn't keep the art", it) }
    }

    /** The art, cut to a circle; null when it doesn't load. */
    private fun loadArt(url: String): Bitmap? {
        // A song from the host (local music) has its art there.
        val full = if (url.startsWith("/")) PlaybackHub.target.value?.room?.hostUrl?.trimEnd('/')?.plus(url) ?: return null else url
        return try {
            val bytes = (URL(full).openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 10_000
                try {
                    inputStream.use { it.readBytes() }
                } finally {
                    disconnect()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_PX) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            circle(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't load the art: ${e.message}")
            null
        }
    }

    private fun circle(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888)
        // Fills the circle (cut from the middle, as YouTube's wide thumbnails are).
        val scale = ART_PX.toFloat() / min(source.width, source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((ART_PX - source.width * scale) / 2, (ART_PX - source.height * scale) / 2)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        Canvas(out).drawCircle(ART_PX / 2f, ART_PX / 2f, max(1f, ART_PX / 2f), paint)
        return out
    }
}

/**
 * Your thumbs up in YouTube Music, for the widget. The page says where your likes are
 * ([setAccess]: its host, or your server, with your token there) and which ones it knows; a
 * like from the widget goes there the same way the page's own does (`PUT /api/me/likes/{songId}`).
 */
object WidgetLikes {
    private const val TAG = "YtmpWidget"

    data class Access(val base: String, val token: String?)

    /** Null: not signed in to YouTube Music (or the page hasn't said yet). */
    val access = MutableStateFlow<Access?>(null)

    /** The likes known, by song ID. */
    val ratings = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Tells the page about a like from the widget (set by [MainActivity]). */
    @Volatile
    var pageSink: ((songId: String, liked: Boolean) -> Unit)? = null

    /** From the page: JSON `{ base, token }`, or null when signed out. */
    fun setAccess(json: String?) {
        val next = runCatching {
            json?.let(::JSONObject)?.let { Access(it.getString("base").trimEnd('/'), if (it.isNull("token")) null else it.getString("token").ifEmpty { null }) }
        }.getOrNull()?.takeIf { it.base.startsWith("http://") || it.base.startsWith("https://") }
        if (next == access.value) return
        // Another account: other likes.
        ratings.value = emptyMap()
        access.value = next
    }

    /** The page knows (or changed) a like. */
    fun heard(songId: String, liked: Boolean) = remember(songId, liked)

    private fun remember(songId: String, liked: Boolean) = ratings.update { (it - songId + (songId to liked)).entries.toList().takeLast(50).associate { e -> e.key to e.value } }

    suspend fun load(songId: String) {
        val access = access.value ?: return
        val answer = withContext(Dispatchers.IO) { runCatching { request(access, songId, "GET", null) }.onFailure { Log.w(TAG, "couldn't look up the like: ${it.message}") }.getOrNull() }
        if (answer != null && this.access.value == access) remember(songId, JSONObject(answer).optBoolean("liked"))
    }

    /** Likes [songId] or takes it back: shown at once, back again when it didn't work. */
    suspend fun set(songId: String, liked: Boolean) {
        val access = access.value ?: return
        val before = ratings.value[songId]
        remember(songId, liked)
        val body = JSONObject().put("liked", liked).put("disliked", false).toString()
        val ok = withContext(Dispatchers.IO) { runCatching { request(access, songId, "PUT", body) }.onFailure { Log.w(TAG, "couldn't change the like: ${it.message}") }.isSuccess }
        if (ok) {
            Log.i(TAG, "${if (liked) "liked" else "unliked"} $songId")
            pageSink?.invoke(songId, liked)
        } else {
            ratings.update { if (before == null) it - songId else it + (songId to before) }
        }
    }

    private fun request(access: Access, songId: String, method: String, body: String?): String {
        val connection = URL("${access.base}/api/me/likes/${Uri.encode(songId)}").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            access.token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }
}
