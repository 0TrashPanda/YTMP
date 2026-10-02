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

For hosting on the phone (milestone 3), the APK will bundle the web app and the phone's own
server will serve it.

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
