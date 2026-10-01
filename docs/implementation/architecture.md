# Architecture

> Status: early design. Expect this to change. YTMP's scope has grown over time,
> and the structure may need to be reconsidered.

## Tech stack

| Part | Choice |
|------|--------|
| Backend / host core | **Kotlin** (JVM), shared by the Linux server and the Android app |
| Frontend | **Svelte + Tailwind + TypeScript**, built as a static SPA |
| Android app | Kotlin app with a **WebView** that loads the same frontend |
| Web client | The same frontend, served by the server |
| YouTube Music source | **Python** (`ytmusicapi` + `yt-dlp`), see [ytm-module.md](ytm-module.md) |
| Database | **SQLite** or **PostgreSQL** on the server, SQLite on Android, see [storage.md](storage.md) |

**Goal:** maximise code sharing.

- **One frontend** (Svelte SPA) for the browser and the Android WebView. It is served by
  the server, or by the phone host to LAN clients, and bundled in the APK.
- **One host core** in Kotlin (rooms, queue, ordering, permissions, sync) that runs on
  both the server and Android. A JVM-friendly HTTP/WebSocket stack such as Ktor
  can run in both places.
- **Shared protocol types**: Kotlin classes are the source of truth and TypeScript types
  are generated from them (see [protocol](protocol.md)).

## Distribution

YTMP is made for a **small group of friends**, not for the general public.

- Source code and releases are on **GitHub**. The Android APK is installed from GitHub
  Releases, not from the Play Store.
- There is **no official server**. Anyone who wants a server self-hosts one.

## Deployment shapes

```
1) Server-hosted
   Browser clients ──► Linux server (host + web UI) ──► YTM service (Python)
   (play the audio)                                └──► outputs (Sonos, Chromecast, … if on the same network)

2) Phone-hosted (LAN)
   Browser / Android clients ──(LAN)──► Android host ──► on-device YTM source
                                                     └──► outputs

3) Solo
   Android host ⇄ its own client, local only
```

## Main components

- **Host core**: rooms, queue and ordering, permissions, playback control
- **Client protocol**: real-time sync between host and clients (queue updates, playback state)
- **Web UI**: shared frontend (browser + Android WebView)
- **Source modules**: see [modules.md](modules.md)
- **Output modules**: see [modules.md](modules.md)
- **Account service** (on the auth server): accounts, room transfer, playlists, role templates,
  linked YouTube accounts and media servers (see [auth](auth.md))
- **History & graph service** (on the auth server): listening history, recap, song graph,
  radio generation
