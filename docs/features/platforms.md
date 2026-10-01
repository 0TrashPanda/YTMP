# Platforms

| Platform | Host | Client | Notes |
|----------|:----:|:------:|-------|
| **Linux server** | ✅ | — | Serves the web interface. Rooms are created and joined through the browser. |
| **Android phone** | ✅ | ✅ | Can host (including solo) and join. Can work without a server. |
| **Web browser** | via server | ✅ | A browser is always a client, but through a server it can *create* a room or solo room and become its admin. |

Other platforms (Windows, macOS, desktop Linux) use YTMP **through the web
browser**. **iOS is not supported**: iPhones can open the web UI, but playback in the
background is unreliable in Safari and won't be worked around.

The Android app needs **Android 13 or newer**. A native Linux app is a [future idea](future-ideas.md#native-linux-app).

## Key properties

- **Hosting/joining from almost anywhere**: any device with a browser can create or
  join a room on a YTMP server.
- **Serverless use**: an Android phone can host on its own, including fetching music
  through an [on-device source module](sources.md), so YTMP works without a
  server.
- **Local network**: when a phone hosts, other devices on the same network can join it.
- **Accounts on phone hosts**: a phone host can use a YTMP server for authentication.
  Without one, only guests can join.

> Note: on a server, the *server process* is the actual host (it keeps the queue and
> controls playback), while the person who clicked "Host a room" in their browser is the **admin**
> of that room. On Android, the host device and the admin are the same.

## Android app

- **Background playback**: music keeps playing with the screen off or the app in the
  background.
- **Media controls**: lock screen and notification controls, and headphone / Bluetooth
  buttons (play/pause, next, previous).
- **Offline**: without internet, downloaded songs and local files can still be played,
  in a solo room or in a room on the local network.
- **App updates**: the app is distributed through GitHub Releases, so it **checks for new
  versions itself** and offers to download and install them.
