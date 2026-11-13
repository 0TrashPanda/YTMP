# Desktop App (being built)

Feature description: [features/platforms.md](../features/platforms.md#desktop-app-planned).

A **client** for Windows and Linux: the web app in a native window, with the YouTube Music
module running on the same computer. The room, accounts and room sync stay on the YTMP
server; every YouTube request comes from the computer. The app is in `desktop/`: ✅ marks
what's built. First released in 0.44.0; tried on Windows 10 with that release (see the
Windows check below).

## Shape

```
┌──────────────── desktop app ────────────────┐
│ Tauri shell (Rust)                          │
│  ├─ window: the web app from the server ────┼──► YTMP server (rooms, accounts, sync)
│  ├─ media keys (MPRIS / Windows controls)   │
│  ├─ updater                                 │
│  └─ starts & stops ─► YTM module (Python)  ─┼──► YouTube (lookups, search, pages, audio)
│                        on 127.0.0.1, own key│
│                        + Deno for yt-dlp    │
└─────────────────────────────────────────────┘
```

- **No Kotlin host on the computer**: it's a client, so no Java runtime is shipped. That's
  most of the memory and size a full host would cost.
- ✅ **The window loads the web app from the server**, like the Android app
  ([android-app.md](android-app.md#client-mode-milestone-2-)): the app and the server's UI
  can't get out of sync. The bundled setup page (`desktop/setup/index.html`, the Android
  app's look) asks for the address the first time (`http://` added when there's none, like the
  phone); the app keeps it in `settings.json` in its config folder and opens it at the next
  start when it answers, else the setup page says why. *Settings → App → Change server* goes
  back to it. Links that open a new window go to the browser. One app at a time (a second
  start brings the first window up).
- ✅ **The module is the browser's [own module](playback-sync.md#known-risk-ip-bound-stream-urls)**,
  set up by the app (`desktop/src-tauri/src/module.rs`): the shell starts it
  (`python -m ytmp_ytm.http`) on `127.0.0.1` with a free port and a new random key on every
  start (`YTMP_YTM_PORT`, `YTMP_MODULE_KEY`), waits for `/health`, starts it again when it
  stops (1 s, then longer up to 30 s), and stops it with the app, also when the app is killed
  or crashes: on Linux with `PR_SET_PDEATHSIG`, on Windows by putting it in a job object with
  *kill on job close* (the app holds the job's only handle, so Windows ends the module, and
  the Deno it starts, when the app ends however it ends). Its output goes to `module.log` in
  the app's log folder.
- ✅ **The page reaches the module through the shell**, not with `fetch`: WebKitGTK blocks an
  https page from fetching `http://127.0.0.1` (mixed content; "Load failed", the request
  never leaves; seen in the Linux check below), and Chrome's loopback exception doesn't
  exist there. An **initialization script** the shell runs on every page sets
  `window.YtmpDesktop = { version, platform, moduleRequest(path), changeServer(), updates }`
  (`updates`: the build looks for new versions, so *Settings → App* says so; missing before
  0.45); `moduleRequest` is a Tauri command
  that forwards the request to the module with its key and answers `{ status, body }`. The
  page never sees the module's port or key. The server's origin gets a Tauri capability for
  the command at runtime: the origin the entered address ends up on after redirects, and
  that address is the one kept. (A server behind Caddy sends `http://` to `https://`; up to
  0.45.0 only the typed `http://` origin was allowed, so the page couldn't reach the module:
  "this app's YouTube Music module doesn't answer".) Same on Windows, so
  there's one way. `ownModule.svelte.ts` sends every module request through one function that
  uses `moduleRequest` when it's there. In the app, `activeModule()` is the app's own module
  (a browser's saved one doesn't count there), shown in *Settings → Where songs play from* as
  *This app's YouTube Music*, with a switch (`ytmp.desktopModule`) and its state. In the app
  the default order of *Where songs play from* puts it first (`DESKTOP_SONG_SOURCES`: own
  module, the room's link, through the server), so songs come from this computer unless
  you change the order; browsers keep the room's link first.

## Music and songs on this computer ✅

The music files on the computer and a song cache, both off by default (features:
[sources](../features/sources.md#local-music-)). Tried in a debug build on Windows 10 against a
local server: the Local tab with three MP3s (tags, covers, albums, folders), one playing in a
solo room from the computer with the server asking for nothing; the same song in a party room
with a second listener (the browser), where the server asked the app once and the app sent
5 MB; a YouTube song kept by the cache while the one before it played, then played from the disk
without another lookup.

- **The module does the work** (`ytmp_ytm/desktop.py`, only with `YTMP_YTM_DESKTOP_DIR` = the
  app's data folder `on-this-computer/`): settings in `desktop.json` (`cacheMb`, `music`,
  `folders`), the music index in `music-index.json` (tags read once per file with mutagen, by its
  modified time and size), the cache in `song-cache/` with its `index.json`. Routes under
  `/desktop/…` (status, settings, library, albums, artists, files, upload), with the key like the
  rest.
- **The window plays from the computer** through a protocol of the app's own, `ytmpfiles`
  (`http://ytmpfiles.localhost/` on Windows, `ytmpfiles://localhost/` on Linux; `FILES_BASE`):
  `audio/<song>` and `art/<song>`. The app passes each request (with its `Range`) to the
  module's `/desktop/files/…` with the key and answers with CORS open, so the equalizer can use
  it too. The module answers at most 2 MB per request (`PIECE`) and the player asks on.
- **IDs**: this computer's id and secret are made once and kept in the app's `settings.json`
  (`device_id`, `device_secret`). Songs `local:dev-<id>:s:<key>` (key: SHA-1 of the path), albums
  `…:a:…`, artists `…:r:…`. The web app sends album and artist pages of its own device to the
  module (`computer.svelte.ts`, `isMine`); the Local tab's library (`kind: computer`) comes from
  the module too, never through a host.
- **Playing**: the web player (`player.svelte.ts`) plays from `ytmpfiles` when the song is this
  computer's, or when the cache is on and it's a YouTube Music song (not a podcast, at most an
  hour): `computerAudio`. If that fails it plays the usual way. The badge says *This computer*.
- **The cache** (`Desktop.file` → `_from_fill`): a song not kept yet is fetched once, in 1 MB
  ranges like yt-dlp, into a `.part` file that the player reads as it comes in (a seek far
  ahead is fetched from YouTube directly meanwhile). Complete and under an hour: kept. Over the
  size: the lowest keep score goes first (last played, plus a week per extra play in 90 days),
  like the phone's. The player asks for the next song's start while one plays, so it's fetched
  ahead.
- **Sent only when needed** (`DeviceSongs` in `host/`, on the server): the room's stream link
  for these songs is the server's `/api/audio/<song>`. Nobody asks for it while the computer
  plays alone (its player plays from `ytmpfiles`). When another player or a speaker does, the
  server marks the song wanted and waits up to 60 s. The app's web page waits at
  `GET /api/devices/<id>/wanted` (25 s at a time, `X-Device-Secret`; the first secret a device
  comes with is its own until the server restarts), and on an answer has the module `PUT` that
  file to `/api/devices/<id>/songs/<song>`. The server keeps it in `device-songs/` next to the
  database: at most 2 GB, a day unused, emptied when it starts, 500 MB a file at most.
- **Not yet**: radio and autoplay from these songs (the server can't read them); their covers
  for others in the room (the cover link is the computer's own, so others see the placeholder).

## Why Tauri

| | Tauri | Electron |
|---|---|---|
| Web engine | The system's: WebView2 (Chromium) on Windows, WebKitGTK on Linux | Its own Chromium |
| App size | A few MB (+ module) | ~100 MB more |
| Memory | Lower, most of all on Linux | A whole Chromium per app |
| Risk | Linux: WebKitGTK is another engine than the web app is tested on; audio formats go through GStreamer | None for playback |

**Decision: Tauri 2**, because the app is mostly the web app plus the module, and memory
matters. The Linux risk is handled by bundling GStreamer in the AppImage
(`bundleMediaFramework`) and by checking the risky parts first (step 1 below).

## Every YouTube request on the computer

The web app asks the own module for:

- ✅ stream links (`ownStreamUrl`), and search and suggestions, Home, artist, album, playlist,
  podcast and song pages (pasted links), lyrics: one place in `api.ts` (`fromOwnModule`,
  `lookUp`), with the server as fallback when the module fails; browsers with an own module
  get it too. How, and what the host still does per route:
  [playback-sync](playback-sync.md#search-and-pages-from-your-own-module).
- ✅ Every module request goes through `moduleRequest()` (`ownModule.svelte.ts`): `fetch` with
  the key, or, when the shell offers `window.YtmpDesktop.moduleRequest(path)` (a Promise of
  `{ status, body }`), that instead: WebKitGTK won't let the https page fetch
  `http://127.0.0.1`, so the shell forwards them over Tauri IPC.
- planned, with the app's own YouTube Music sign-in: the `/me/…` requests (library, likes,
  playlists, history, the account's own Home and search), with the sign-in in `X-Ytm-Cookie`.
  Until then they, and the Home and searches of a sign-in on the server, stay with the server.

How:

- **Stays on the server**, because the room needs it there: radio and autoplay (the host
  picks the songs), the room's own link for a song (other listeners use it), the song cache,
  playlist imports and anything that changes the room.
- **The sign-in**: the app keeps its own YouTube Music sign-in (a Google sign-in window, like
  the phone, or *Use the one from your account on <server>*, which the server only gives to
  you on its own pages). Kept in the system's keyring (Windows Credential Manager, the Secret
  Service on Linux), and given to the page through the shell, never to the server.

## The YouTube parts on a computer

- ✅ **Python**: `desktop/scripts/bundle_module.py` puts a standalone CPython
  ([python-build-standalone](https://github.com/astral-sh/python-build-standalone), 3.13) with
  `ytm-module/` installed into `desktop/src-tauri/resources/`, one per OS; downloads pinned and
  checked by SHA-256. Without Tk, headers, pip and Linux's duplicate `libpython` (the
  `python3.13` binary has it built in): 85 MB. Same code as the server's and the phone's
  module. Not PyInstaller: its frozen archive would win over the updated yt-dlp in front on
  `sys.path`, and Windows' virus scanners often flag its starter.
- ✅ **yt-dlp's JavaScript runtime**: a bundled **Deno** binary (92 MB, `YTMP_YTM_JS_RUNTIME=deno`,
  its folder in front on `PATH`), with the limits the server
  already has (`ytmp_ytm/deno.py`: at most 2 at once, capped heap, stopped after 30 s). It
  takes ~250 MB for a few seconds per lookup; the next song is looked up while one plays, so
  that's mostly once per song.
- **PO tokens** ([bot check](ytm-module.md)): like the phone, BotGuard runs in a hidden window
  of the app on `https://www.youtube.com/robots.txt` (YouTube's origin, a tiny page), with
  `android/…/assets/botguard.html`'s script as an initialization script and its answers sent
  back over IPC; `ytmp_ytm/pot.py` asks the shell for it over the loopback (with a key). Runs
  in WebKitGTK (Linux check below).
- **yt-dlp updates itself** (built in the module and used by the phone, see
  [ytm-module.md](ytm-module.md#yt-dlp-updating-itself-)): the module checks PyPI once a day,
  puts a newer yt-dlp in the app's data folder and loads it before the bundled one at the next
  start; when it fails to load or its first lookups fail, it goes back to the bundled one. ✅
  The app starts the module with `YTMP_YTM_UPDATE_DIR` = `ytm-updates` in its data folder;
  the bundled Python keeps yt-dlp as plain files, so the updated one in front wins.

## Media keys

The web app already sets the Media Session API (title, art, play/pause/next/previous).

- ✅ **Windows**: WebView2 passes the Media Session to Windows' media controls itself, but
  Windows then names the player *msedgewebview2.exe* and leaves the album out (seen in the
  Windows check). So WebView2's own is off (`--disable-features=…,HardwareMediaKeyHandling`,
  next to Tauri's default flags it replaces), the initialization script passes the page's
  Media Session (metadata, `playbackState`, `setPositionState`, only when they change) to the
  app's `media_update` command, and the app shows it in Windows' media controls as itself
  (`desktop/src-tauri/src/media.rs`, the `souvlaki` crate on the window's HWND). Play, Pause,
  Next, Previous and seeking there call the page's Media Session handlers
  (`window.__ytmpMediaAction`). The web app doesn't know; it uses the Media Session API as
  in a browser.
- **Linux**: WebKitGTK does MPRIS itself (checked with 2.54): title, artist, album and
  length show in the desktop's media widget, and Pause, Play and Next over MPRIS reach the
  page's Media Session handlers. Still to check with the AppImage's own (older) WebKitGTK; if
  that lacks it, the shell does MPRIS itself (the `souvlaki` crate), fed by the page over IPC.

## Packaging, updates and releases

- **Windows**: an NSIS installer (`.exe`), per user, no admin rights. It installs WebView2 when
  it's missing (rare on Windows 10/11). Unsigned at first, so SmartScreen warns once
  (*More info → Run anyway*); a code-signing certificate can come later.
- ✅ **Linux**: an **AppImage** with GStreamer and WebKitGTK inside (so playback doesn't depend
  on the distribution's plugins): 230 MB, most of it Deno (92 MB) and Python (85 MB). Built and
  run on Arch. Some distributions need `libfuse2` to run AppImages. Gotchas when building it:
  the AppImage's `AppRun` sets `PYTHONHOME`/`PYTHONPATH` for its own folder, which the
  bundled Python would follow (the app removes them for the module); Tauri doesn't remove old
  files from `target/release/resources/`, so after changing the bundle delete that folder (and
  `target/release/bundle/`); on Arch linuxdeploy's `strip` fails on the system's libraries:
  `NO_STRIP=true pnpm tauri build --bundles appimage`.
  Built on the oldest Ubuntu GitHub has, so it runs on older distributions too. An AUR
  package that installs the same AppImage can come later.
- ✅ **Updates**: Tauri's updater plugin. 8 s after starting, the app looks through GitHub's
  list of releases (like the Android app: 0.x releases are pre-releases, which
  `/releases/latest` skips) for the newest with a `latest.json`, checks it, and offers the
  version with that release's notes as they are now (*Update* / *Later* / *Skip this
  version*). *Update* downloads, stops the module (the installer replaces its files, which
  Windows won't do while they run), installs (Windows: the installer, passive) and restarts.
  Updates are signed with an updater key of their own (separate from the APK's): the public
  key in `desktop/updater.pub`, built in through `YTMP_UPDATER_PUBKEY`; the private key and
  its password in the secrets `TAURI_SIGNING_PRIVATE_KEY` / `…_PASSWORD`. Builds without the
  key (development) don't look for updates. **To do by the owner**: make the key
  (`cd desktop && pnpm tauri signer generate -w ~/.tauri/ytmp-updater.key`), commit the public
  key as `desktop/updater.pub` and add the two secrets.
- ✅ **Release**: `release.yml`'s `desktop` job (after the APK's, which makes the release),
  per OS (`ubuntu-22.04`, `windows-latest`): the module (`bundle_module.py`), `tauri build`
  with the tag's version, and uploads `ytmp-<version>-linux-x86_64.AppImage` and
  `ytmp-<version>-windows-x64-setup.exe` to the release (artifacts on a run by hand). Then
  `desktop-updates` writes `latest.json` from both signatures and uploads it.
- **Code**: `desktop/` (Tauri project: `src-tauri/`, `setup/`, `scripts/`); the module and the
  web app stay where they are. Development: `python desktop/scripts/bundle_module.py` once
  (or without it the app uses `ytm-module/.venv` and no Deno), then `cd desktop && pnpm
  install && pnpm tauri dev`. On Windows without Visual Studio, Rust's GNU toolchain works
  for trying things (`rustup` with `--default-host x86_64-pc-windows-gnu`) with a full
  MinGW-w64 on `PATH` (WinLibs, the *msvcrt* one; Rust's own bundled MinGW lacks `as` and
  `windres`); its linker warns "multiple non-default manifests". Releases are built with
  MSVC on GitHub. `resources/` must exist for `cargo build` (copy it from an installed app,
  or run `bundle_module.py`).

## Memory, roughly

Measured with the AppImage on Linux, on the join page (2026-11-11):

| Part | When | About |
|---|---|---|
| Web view: the page (`WebKitWebProcess`) | always | ~300 MB |
| Web view: network | always | ~100 MB |
| The app itself | always | ~25 MB |
| YTM module (Python) | always | ~85 MB |
| Deno | during a song lookup, a few seconds | ~250 MB |
| BotGuard window (planned) | while lookups happen, closes after 10 min without | ~20 MB |

The page's share is about what the same page takes in a browser tab.

## Linux check ✅ (2026-11-11)

A throwaway Tauri 2 window (Arch, WebKitGTK 2.54, GStreamer 1.28 with good/bad/libav) on a
local YTMP server and module:

| What | Result |
|---|---|
| The web app (join page) | Renders and works as in Chrome |
| Audio formats | `canPlayType` "probably" for Opus/WebM and AAC/M4A; MediaSource there |
| Playing a song | The module's direct YouTube link (M4A) plays, time runs on |
| Module from an `http://` page | `fetch` to `http://127.0.0.1` works |
| Module from an `https://` page | **Blocked** (mixed content), never reaches the module; through a Tauri command it works, so the shell forwards them (see Shape) |
| Media keys | MPRIS player with the Media Session's title/artist/album/length; Pause, Play, Next reach the page |
| BotGuard | In a hidden window on `https://www.youtube.com/robots.txt` (YouTube's origin, a tiny page) with `botguard.html`'s script: snapshot in 0.27 s, Google gave an integrity token (12 h), minter started and made a 128-character token. Not yet tried in a real lookup |
| Tauri IPC from a server page | Needs the origin in a capability and the app's commands in an app manifest (`tauri_build::AppManifest`) |

Not covered: Windows (WebView2), the AppImage's bundled WebKitGTK, and the web app's room
pages beyond the join page.

## Windows check ✅ (2026-11-12)

The 0.44.0 installer on Windows 10 (WebView2 157), on a local YTMP server:

| What | Result |
|---|---|
| Install | Per user, no admin, 162 MB (Python 60, Deno 94); unsigned |
| Setup page, Change server, a wrong address | Works; the server is remembered and opens in ~1 s next time |
| `YtmpDesktop` and module requests over IPC | Work; Home, albums and playlists from the app's module |
| Playing a song | Through the room's link and through the app's module (Python + Deno, 8.5 s for the link) |
| One app at a time; closing the window | Second start brings the first up; closing stops the module |
| Killing the app | **The module kept running** → fixed (job object, see Shape) |
| Default order | **The room's link first**, so the server looked songs up → fixed (own module first in the app) |
| *Settings → App* | **Said updates are offered**, but 0.44.0 has no updater key → fixed (`updates`) |
| Media keys | Pause and Next work, but **named *msedgewebview2.exe*, no album** → fixed (the app's own, see Media keys) |
| Memory | App ~29 MB, module ~96 MB, WebView2 ~450 MB |

The fixes were checked with a debug build (GNU toolchain): one media session, as the app,
with title, artist, album and cover; Pause, Play and Next from Windows; killing the app ends
the module.

## Order of work

1. ✅ Check the risky parts on Linux first, see above.
2. ✅ `desktop/`: the shell, the setup page, the window on the server, the initialization script.
3. ✅ The module in the app: standalone Python, Deno, starting and stopping it, the web app
   using *This app* as its own module, `YTMP_YTM_UPDATE_DIR`.
4. ✅ Search, Home and pages through the own module (frontend), with the server as fallback.
5. ✅ Updater and release workflow (waiting for the owner's updater key); README.
6. Next: PO tokens (BotGuard in a hidden window) and the YouTube Music sign-in in the app.
7. ✅ Trying it on Windows, and the first release with it (0.44.0).
8. ✅ The music on the computer and a song cache (see above).

## Open questions

- Closing the window: quit, or keep playing with a tray icon?
- Whether the AUR package is wanted once the AppImage works.
