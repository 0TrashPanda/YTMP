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
- The page keeps running in the background (renderer priority *important*), so it
  follows the room with the screen off. Tested on the emulator: other people's skips
  arrive with the screen off, and media keys work with the app in the background.
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
  0.4 s, a search takes ~1.5 s, resolving a stream ~1.5 s. No JavaScript runtime was needed.
  Chaquopy 17 supports AGP up to 9.2, so the project uses AGP 9.2.1.
- **Who can do what:** only the phone itself (loopback) can create, list and close rooms, and
  join **solo** (private) rooms. Friends on the same Wi-Fi can join **public** rooms at
  `http://<phone-ip>:8765/room/CODE` (QR code in the share sheet).
- **`HostService`**: a foreground service (type *connected device*) while the phone has rooms,
  with a "Hosting …" notification and *Stop hosting*, plus Wi-Fi and wake locks so friends'
  devices keep getting answers with the screen off.
- **Discovery** (`Nearby`): public rooms are announced with mDNS (`_ytmp._tcp`, TXT
  `code`/`name`/`v`), and the start page lists rooms found on the network. Not yet verified
  between two phones: the dev PC's firewall blocks mDNS on Wi-Fi.
- Rooms live in memory: they are gone when the app process stops (persistence is a later step).
- Android's asset packer skips folders starting with `_` by default, which dropped the web
  app's `_app/`; `ignoreAssetsPattern` is overridden for that.

## Structure

- **Kotlin app** that contains the host core (when hosting), the on-device YTM module
  (Chaquopy), and a **WebView** that shows the shared Svelte frontend.
- The WebView talks to the native side through a **JS bridge**.
- When hosting, the app also runs the **HTTP/WebSocket server** (Ktor) that serves the web UI
  and the room to other devices on the network.
- Local data (rooms, downloads, settings) is stored in a local SQLite database (see
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
- ExoPlayer handles **gapless** playback, **crossfade** (two players) and loudness
  normalisation.
- The **sync logic** (clock offset, drift correction, see
  [playback-sync](playback-sync.md)) is implemented twice: in TypeScript for browsers, and in
  Kotlin for the native player.

In the browser, the frontend plays audio itself with HTML audio / the Web Audio API.

## App updates

The app checks **GitHub Releases** for new versions, downloads the APK, and asks
Android to install it (needs the *install unknown apps* permission).
