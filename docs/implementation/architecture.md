# Architecture

> Status: built this way (milestones 1–4 and much of 5). The *history & graph service*
> below has the listening history and the song graph (`server/.../History.kt`, `SongGraph.kt`);
> recap isn't built yet.

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

## Where things are in the code

Where a new feature usually goes, so it lands next to what it's like.

**A room** (`core/`): `Room.kt` is the one entry point: the lock, the commands (`execute`), what
plays next and the playback. The parts that stand on their own are next to it, each used with
the room locked: `Member`, `RoomRoles` (permissions, Discord-style ranking, editing roles),
`FairOrder`, `QueueLimits`, `SkipVote`, `PlaybackClock` and `StartTogether`, `RoomOutputs`
(speakers and TVs), `Autoplay`. A new command: a `Command` in `protocol/`, a branch in
`Room.execute` (with its permission check), and the events it emits. `RoomManager` keeps the
rooms; their radio comes from a `RoomRadio` (YTM's, the owner's, the song graph mixed in by
`RadioMix.kt`), and the host hears about them through `RoomHooks`.

**Room events** reach clients as `Event`s, applied by `RoomState.apply` (`protocol/.../RoomEvents.kt`,
for the app) and `applyEvent` (`frontend/src/lib/roomEvents.ts`). A new event goes in both;
neither compiles until it's handled. Tests check that clients get the host's room
(`RoomTest`), and that the web app gets the same from the same events (`roomEvents.test.ts`,
its data written by `YTMP_WRITE_FIXTURES=1 ./gradlew :core:test --tests '*RoomTest*'`).

**The host's HTTP side** (`host/`): `ytmpModule(rooms, sources, options)` in `Application.kt`.
What a host offers is a `HostSources` (search, pages, lyrics, audio, downloads, local music,
your YouTube Music, the song graph), how it behaves a `HostOptions`. Routes are in files by area:
`RoomRoutes` (`/api/host`, rooms, `/ws`), `CatalogRoutes` (search, pages, Local, audio),
`PersonalRoutes` (`/api/me`), `ScreenRoutes` (party screens); what they share (who a request is,
its YouTube Music) is `HostContext`. The server adds its own routes (accounts, history, room
sync, the admin page in `AdminApi.kt`) through `extraApi`. A server setting the admin page can
change goes in `ServerSettings.kt` (`ServerSettingValues` in `protocol/`) and is put to work
in `settings.apply` in the server's `Main.kt`.

**Songs' audio** (`host/`): sources are wrapped, in front of YouTube Music: `LocalMusic`
(`local:` songs) and the song cache `SongCache` (cached and downloaded songs), each with a
`streams` (what rooms play), an `audio` (the `/api/audio` route) and for local music a
`radio` and `catalog`. A host sets them up in that order (server `Main.kt`, phone `LocalHost.kt`).

**Speakers and TVs** (`host/`): an `OutputDriver` per kind. Devices that each play a room by
themselves extend `DeviceDriver` and only give a `DeviceSession` (keep one device in step with
the room); `ZoneOutputs` has its own loop on `RoomFollowingDriver`. A host lists its drivers once
in `OutputDrivers` (server `Main.kt`, phone `LocalHost.kt`).

**The web app** (`frontend/src/`): the room page (`routes/room/[code]/+page.svelte`) shows one
page of the stack (`lib/pageStack.svelte.ts`: a new kind of page is a `View`) and at most one
sheet, menu or dialog (`sheet`). Song lists use `SongList.svelte`; what tap, swipe and hold do
is `RowGestures` (`lib/gestures.svelte.ts`), and the actions themselves with their messages are
`lib/songActions.ts`. The song menu is `SongMenu.svelte`: each action it can show for a song
goes under its id, and `arrange` in `lib/songMenu.svelte.ts` lays them out as set in
*Settings → Song menu* (a new action: an id in `MENU_ACTIONS` and `DEFAULT_ITEMS`). A setting
that follows your account goes in `lib/preferences.svelte.ts` under a key of its own. Calls to the host are in `lib/api.ts`, to your account's server in
`lib/account.ts` and `lib/listening.svelte.ts`.
A new version on the host: SvelteKit sees that `_app/version.json` changed (on focus, every
10 minutes, and when the room's WebSocket reconnects), and `lib/appUpdate.svelte.ts` reloads
the page at a calm moment: not typing, and not with *Play here* on, unless the browser's
autoplay policy (`lib/autoplay.ts`) lets the reloaded page play on; then the player reloads it
when the next song starts and the room page switches *Play here* back on (`appUpdate.resumes`). The host serves `index.html` and `version.json` with `no-cache`
and the hashed files under `_app/immutable/` for a year (`webApp` in `host/.../Application.kt`).

**The YouTube Music module** (`ytm-module/ytmp_ytm/`): `core.py` talks to YouTube Music and
yt-dlp, `parse.py` turns its answers into YTMP's songs and pages, `http.py` is the HTTP API.

## Web app as an installable app

`frontend/static/manifest.webmanifest` and the icons (`icon-192.png`, `icon-512.png`,
`icon-maskable-512.png`) make it installable; `src/service-worker.ts` (registered by hand in
`src/routes/+layout.svelte`, only in a browser on https) keeps the app's own files
(`immutable` and `assets` of `$app/manifest`, in a cache named after the build) and answers
the page from the host first, from the cache when it can't be reached. It never touches
`/api`, `/ws` or audio. The host already sends everything but `_app/immutable/` as `no-cache`,
so a new service worker is picked up at once and an update reloads open pages as before.
