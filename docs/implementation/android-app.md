# Android App

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
