# Android App

## Client mode (milestone 2) ✅

- The WebView loads the **web app straight from the server** (`http(s)://server/`), so the app
  and the server's UI can never get out of sync, and there is no CORS. A small bundled page
  (`assets/setup.html`) asks for the server address the first time.
- The web app detects the app through the `YtmpNative` JS bridge and then uses a
  `NativePlayer`: instead of playing audio itself, it sends the playback target (song,
  stream URL, proxy URL, position, host time, clock offset, volume) to the app on every change.
- `PlaybackService` (Media3) plays it and does the drift correction. Media buttons (lock
  screen, notification, headphones, Bluetooth) are turned into **room commands**
  (`window.__ytmpNative.onCommand`), so they control the room, not just this phone.
- Losing audio focus or unplugging headphones stops playback **on this phone only**.
- **The player follows the room itself** (`RoomFollower`). Android's WebView **freezes
  the page** after about 5 minutes hidden (screen off): timers, fetches and WebSocket
  messages stop, even with renderer priority *important* and the foreground services
  running. The room then kept playing, but the phone stopped at the end of the song. So the
  page also sends the room's address, code and its guest token, and `PlaybackService` opens
  its **own connection** with `attach` (see [protocol-messages](protocol-messages.md)). While
  that is connected, the song, stream, proxy URL, playback clock, clock offset and "alone"
  come from it; the page still decides "Play here", volume and sync offset. Media buttons
  go over it too. If it can't connect, the page's target is used as before.
- Losing audio focus stops playback here at once and sends `SetListening off` over the
  player's own connection, since the page may be frozen.
- **Between songs and while paused** (`PlaybackService`): while "Play here" is on and the room
  has a song, the player stays in the foreground (`onUpdateNotification`), also paused, so it
  stays on the lock screen and keeps network in deep sleep (Android cuts background apps off).
  While the room plays it also holds its own wake and Wi-Fi lock (high-performance, like
  ExoPlayer's): ExoPlayer's locks end with each song, and a Galaxy A20e (Android 11) dropped
  Wi-Fi within a second in deep sleep, so the next song never loaded. Play/Pause pressed while
  the room connection is down is sent when it's back (within 15 s). A room pauses after three
  songs in a row fail to load, instead of skipping through the whole queue.
- **Battery:** drift correction (`PlaybackService`) only runs while this phone plays. Alone,
  it measures every 250 ms right after a load or seek (to catch a slow start), then once a
  second, since only drift over 1.5 s matters then. The room page only ticks its progress
  bar while the room plays and the page is visible.
- Known limitation: swiping the app away from recents stops playback, because the room
  connection lives in the page.
- Debug builds enable WebView debugging (`chrome://inspect`) and log the page's console to
  logcat (tag `YtmpWeb`).

## Host mode (milestone 3)

- The app runs its **own host** (`LocalHost`): the shared `host/` module on Ktor CIO, port
  **8765**, with the web app bundled in the APK (copied to app storage on first start). The
  app's WebView opens it at `http://127.0.0.1:8765/`, which is the app's home screen.
- **YouTube Music on the phone** (`OnDeviceYtm`): the Python code from `ytm-module/` run with
  **Chaquopy** (Python 3.14, ytmusicapi + yt-dlp). Measured on a Pixel 7: Python starts in
  0.4 s, a search takes ~1.5 s, resolving a stream ~1.5 s.
- **yt-dlp's JavaScript** (`JsEngine`): yt-dlp has to run YouTube's player script to solve its
  challenges; without a JavaScript runtime it only gets a reduced set of formats (and warns).
  The phone can't start Deno as a program, so the app hands yt-dlp the **WebView's own
  JavaScript engine** (`androidx.javascriptengine`, V8 in a sandboxed process):
  `ytm-module/ytmp_ytm/jsc.py` is a yt-dlp challenge provider that runs the same scripts as
  for Deno (the `yt-dlp-ejs` package) in it. A fresh isolate per run; when the sandbox's
  process dies it starts a new one. On a WebView without the sandbox, or one that can't take
  big scripts (the player is ~3 MB), yt-dlp works as before, without JavaScript. Set up on the
  first stream lookup (connecting waits for the main thread). Takes ~1.3 s, only for lookups
  that need it.
  Chaquopy 17 supports AGP up to 9.2, so the project uses AGP 9.2.1.
- **Who can do what:** only the phone itself (loopback) can create, list and close rooms, and
  join **solo** (private) rooms. Friends on the same Wi-Fi can join **public** rooms at
  `http://<phone-ip>:8765/room/CODE` (QR code in the share sheet).
- **`HostService`**: a foreground service (type *connected device*) while the phone has public
  rooms or a room is playing, with a "Hosting …" notification and *Stop hosting* (closes the
  public rooms, pauses solo ones). An idle solo room doesn't need it. To save battery the locks
  are only held while a room plays: a **wake lock** (songs move on with the screen off, also
  when only speakers or friends listen) and a **Wi-Fi lock** (high-performance; the next song
  must load). An idle public room holds neither: a friend's request wakes the phone.
- Chromecast, Sonos and MPD discovery (`CastFinder`, `SonosFinder`, `MpdFinder`, all on
  `NsdFinder`) run while the app is on screen, or while a public room plays. A device leaves
  the list when mDNS says it's gone (after 30 s, in case it's back right away), when a new
  search doesn't find it within 20 s, and when the phone is on another Wi-Fi network
  (`WifiWatch`: another address range or router, or off Wi-Fi for over a minute).
- **Cloning onto the phone** (bridge `cloneRoom`, version 6): from another host's page in the
  app, the page hands over a copy of the room; the app makes it on its own host and the page
  opens it there (`http://127.0.0.1:8765/room/CODE#owner=…`). From the phone's own page onto
  your server: `POST /api/rooms/clone-to-server`, forwarded with your host token (`RoomSync`).
  See [room lifecycle](../features/room-lifecycle.md#cloning-a-room).
- **Party screen on your own TV** (bridge `castScreens`, `showCastScreen`, `stopCastScreen`,
  version 7): in a room on another host, the phone's Chromecasts show that room's `/tv` page
  with DashCast (`LocalHost.castScreens`), see
  [chromecast](chromecast.md#from-the-app-in-a-room-on-another-host-).
- **Speakers near the phone** (bridge `nearby`, `playNearby`, `stopNearby`,
  `setNearbyVolume`, version 8): in a room on another host, the phone plays the room on its
  Sonos speakers, Chromecasts and MPDs itself (`LocalHost.nearby`), see
  [chromecast](chromecast.md#speakers-near-the-phone-in-a-room-on-another-host-).
- **Accounts** (`AuthLink`): the phone can be linked to a YTMP server on its home screen; then
  account holders of that server can join its rooms (see [auth](auth.md)). The WebView keeps
  that server's pages in the app, for logging in.
- **Discovery** (`Nearby`): public rooms are announced with mDNS (`_ytmp._tcp`, TXT
  `code`/`name`/`v`), and the start page lists rooms found on the network. Not yet verified
  between two phones: the dev PC's firewall blocks mDNS on Wi-Fi.
- **Rooms survive app restarts** (`PhoneRoomStore`, SQLite `ytmp.db` in app storage): each
  room is saved about a second after it changes, and every 15 s while playing (for the
  position). They come back paused; the stream is resolved again on *play*. Which speakers a
  room played on is not kept.
- Android's asset packer skips folders starting with `_` by default, which dropped the web
  app's `_app/`; `ignoreAssetsPattern` is overridden for that.

## Structure

- **Kotlin app** that contains the host core (when hosting), the on-device YTM module
  (Chaquopy), and a **WebView** that shows the shared Svelte frontend.
- The WebView talks to the native side through a **JS bridge**.
- When hosting, the app also runs the **HTTP/WebSocket server** (Ktor) that serves the web UI
  and the room to other devices on the network.
- Rooms are kept in a local SQLite database, settings in SharedPreferences (see
  [storage](storage.md)).

## Audio playback: native

**Decision:** audio on Android is played **natively** with **Media3 / ExoPlayer**, not
inside the WebView.

- The WebView UI only **controls** playback through the JS bridge (play, pause, seek,
  volume) and receives state updates (position, buffering, errors).
- A Media3 **MediaSessionService** (foreground service) gives:
  - background playback with the screen off
  - lock screen and notification controls
  - headphone / Bluetooth / car buttons
- ExoPlayer can do **gapless** playback, **crossfade** (two players) and loudness
  normalisation. Not built yet: crossfade and loudness normalisation.
- The **sync logic** (clock offset, drift correction, see
  [playback-sync](playback-sync.md)) is implemented twice: in TypeScript for browsers, and in
  Kotlin for the native player.

In the browser, the frontend plays audio itself with HTML audio / the Web Audio API.

## App updates ✅

`Updates.kt`: at app start (once per process, not in debug builds: they're signed with
another key) the app asks the GitHub API for the releases (pre-releases included: every 0.x
is one) and compares the newest one with an APK to its own version code (`1.2.3` →
`10203`, as in `android/build.gradle.kts`). A newer one is offered in a dialog with the
release notes (without their Install part); *Skip this version* is remembered. Update
downloads the APK into the cache (`updates/`) with a progress dialog and opens Android's
installer through the FileProvider (`REQUEST_INSTALL_PACKAGES`; Android asks once to allow
installs from YTMP). The settings call it through the bridge (version 5: `appVersion`,
`checkForUpdates`).

## Logs ✅

- `assets/logback.xml`: logcat, plus files in `files/logs/` (a file per day, split at 2 MB,
  7 days, at most 20 MB), not in backups. The app's own lines go through `Log.kt` (slf4j,
  same API as `android.util.Log`), the shared code logs through slf4j, and the Python
  module's `logging` is sent there too (`core.log_to` → `PythonLog`), yt-dlp included.
- The player logs each song it loads (direct or through the host, link host and expiry, never
  the whole URL), player errors with their cause, and buffering longer than 3 s. Errors and
  long buffering in rooms on another host are also sent to that host (`HostReport`,
  `POST /api/log`), so the server's log shows what listeners hear.
- *Send logs* (bridge `shareLogs`) puts all files in one text file with the app version and
  phone at the top, and opens the share sheet.
