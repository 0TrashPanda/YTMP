# Desktop App

Feature description: [features/platforms.md](../../features/platforms.md#desktop-app-).

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

- **A Kotlin host on the computer, but no rooms or accounts**: the phone's host code for the
  song cache, local music and speakers, see [this computer's host](#this-computers-host-).
  It costs a Java runtime of its own (~95 MB on disk, ~190 MB of memory).
- ✅ **The window loads the web app from the server**, like the Android app
  ([android-app.md](android-app.md#client-mode-milestone-2-)): the app and the server's UI
  can't get out of sync. The bundled setup page (`desktop/setup/index.html`, the Android
  app's look) asks for the address the first time (`http://` added when there's none, like the
  phone); the app keeps it in `settings.json` in its config folder and opens it at the next
  start when it answers, else the setup page says why. *Settings → App → Change server* goes
  back to it. Links that open a new window go to the browser. One app at a time (a second
  start brings the first window up).
- ✅ **Its own title bar** (`frame.rs`, `desktop/setup/titlebar.html`): the window has no
  system frame (`decorations(false)`) and holds two web views (Tauri's `unstable` feature):
  the title bar (32 px: logo, the update arrow, minimize / maximize / close; drag it to move,
  double-click to maximize) and the page under it, laid out again on every resize. In the
  page's own code it would have to move every fixed element down, and pages from an older
  server wouldn't have it. Every web view gets the same WebView2 arguments (`BROWSER_ARGS`):
  WebView2 refuses a second set in the same profile.
- ✅ **Closing stops playing here** (`before_quit`, `frontend/src/lib/appQuit.ts`): a closed
  app sends the room no goodbye. The host would only notice the lost connection after its
  30 s timeout, and then keeps a device that played for another minute (`Room.holdPlayer`,
  meant for a reload or a network drop), so the room played on to nobody for up to ~90 s,
  also while an update installed. Now closing the window (or *Restart now*) first calls the
  page's handler (`YtmpDesktop.onQuit`, 2 s at most): if it plays here it sends
  `SetListening(off)`, and the room pauses at once when nothing else plays it. It doesn't
  switch *Play here* off (`ytmp.listening` stays), and the window may start sound without a
  tap (`--autoplay-policy=no-user-gesture-required`, `YtmpDesktop.autoplay`), so the next
  start plays here again (before, it waited for a click, as in a browser). For an update it
  also keeps the room and the song (`ytmp.resumeAfterAppUpdate`, 10 minutes): the new version
  opens that room (also with *Open the party room you were in* off) and, if the room is still
  paused on that song, plays on.
- ✅ **The module is the browser's [own module](../outputs/playback-sync.md#known-risk-ip-bound-stream-urls)**,
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
  `window.YtmpDesktop = { version, platform, moduleRequest(path), changeServer(), updates, checkUpdates(), onQuit(handler), autoplay }`
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
  the default order of *Where songs play from* puts it first (`SONG_SOURCES`: own
  module, the room's link, through the server; the same in browsers, where it's skipped
  without a module), so songs come from this computer unless you change the order.

## This computer's host ✅

The same host code as the phone's (`host/`), run by the app as a third program next to its
window and the Python module (`desktop/host/`, Gradle project `:desktop-host`, main
`dev.trashpanda.ytmp.desktop.DesktopHostKt`). So the desktop gets the song cache, local music,
and later the speakers and copying from a phone, from the code the phone and the server
already use, instead of a second copy in Python. Python stays YouTube only; Rust stays the
window, media keys, updater and starting the other two.

It is **not a server**: no accounts, no admin, no rooms (`roomCreateDenied`; your rooms stay on
your server), `HostKind.COMPUTER`.

- **Only for the app**: it listens on `127.0.0.1` only, on a free port, and answers only with
  the key the app made for this start (`Authorization: Bearer …`; `/api/health` without, for
  the app to see it's up). Requests from elsewhere get 401.
- **The window reaches it through the app**: `ytmphost://localhost/api/…` (Windows:
  `http://ytmphost.localhost/api/…`, `HOST_BASE`, given to the page as `YtmpDesktop.host`). The
  app's protocol handler (`host_request` in `main.rs`) passes method, path, `Range`,
  `Content-Type` and body on with the key, answers CORS (`OPTIONS` itself) so the server's page
  may `fetch` it, and caps a song's open range to 4 MB per answer (`piece`): Tauri keeps each
  answer in memory, the player asks on.
- **Started and watched like the module** (`module.rs`: `Sidecars`, `Kind::Host`): restarted
  when it stops, in the same kind of job object on Windows (`PR_SET_PDEATHSIG` on Linux), so
  it ends with the app. Its output goes to `host.log` in the app's log folder. Env:
  `YTMP_HOST_PORT`, `YTMP_HOST_KEY`, `YTMP_HOST_DIR` (the data folder's `host/`),
  `YTMP_HOST_WINDOW_BASE`, `YTMP_MODULE_URL`/`YTMP_MODULE_KEY` (YouTube through the module,
  `RemoteSourceModule`, moved from `server/` to `host/` for this), `YTMP_DEVICE`.
- **The Java runtime is the app's own**: `desktop/scripts/bundle_host.py` builds the host
  (`installDist`), lets `jdeps` find the Java modules it uses (plus a few it loads by name), and
  makes a runtime of only those with `jlink` (with a class data archive, for a faster start)
  in `src-tauri/resources/java`, the libraries in `resources/host/lib`. Without them (a dev
  build) the app uses `YTMP_DESKTOP_JAVA` or the `java` on `PATH`, and
  `desktop/host/build/install/desktop-host/lib`. Windows gives Tauri's resource folder with a
  `\\?\` prefix, which Java's `lib\*` classpath doesn't understand: the app strips it.
- **Size and memory** (Windows, 2026-11-13): the runtime 73 MB and the libraries 21 MB on disk;
  the host used ~190 MB of memory after playing songs, next to the module's ~100 MB (JVM flags:
  serial GC, `-Xmx256m`, C1 only). Worth tuning further.

## Music and songs on this computer ✅

The music files on the computer and a song cache, both off by default (features:
[sources](../../features/music/sources.md#local-music-)). Tried in a debug build on Windows 10 against a
local server, with the host: the Local tab with three MP3s (tags, covers, albums, folders), one
playing in a solo room from the computer with the server asking for nothing; the same song in a
party room with a second listener (the browser), where the server asked once and the host sent
5 MB; a YouTube song fetched into the cache while the one before it played, then played from
the disk without another lookup.

- **The music** (`ComputerLibrary`, a `LocalLibrary` of kind `computer`, id `dev-<device>`):
  the folders picked with the system's folder picker (the app's `pick_folder`), kept in
  `desktop.json` with the cache's size. Tags (jaudiotagger: MP3, M4A, FLAC, OGG, WAV, WMA; the
  file name when there are none) are read once per file, by its modified time and size, and
  kept in `music-index.json`; the folders are looked through again when the Local tab asks
  (at most every 10 minutes) or with *Look again*. Covers: the picture in the file, else a
  `cover`/`folder`/`front.jpg` in its folder. `LocalMusic` makes the songs, albums, artists,
  genres and folders, as on the phone and the Pi.
- **The cache** is the host's `SongCache`, as on the phone and the server, in the data folder's
  `host/song-cache` (*Settings → Song cache*: `GET/PUT /api/cache`, `POST /api/cache/clear`).
  The window tells it about a song first (`POST /api/desktop/started`: it plays now and counts
  as a play; `…/known`: it's next), so asking for its audio (`/api/audio/…`) fetches it once
  into the cache as it plays (`Fill`), and the next one ahead of time.
- **IDs**: this computer's id and secret are made once and kept in the app's `settings.json`
  (`device_id`, `device_secret`). Songs `local:dev-<id>:s:<key>` (key: from the file's path),
  albums `…:a:…`, artists `…:r:…`. The web app sends the Local tab's library and album and
  artist pages of its own device to the host (`computer.svelte.ts`, `isMine`).
- **Playing**: the web player (`player.svelte.ts`) plays from the host when the song is this
  computer's, or when the cache is on and it's a YouTube Music song (not a podcast, at most an
  hour): `computerAudio`. If that fails it plays the usual way. The badge says *This computer*.
- **Sent only when needed** (`DeviceSongs` in `host/`, on the server): the room's stream link
  for these songs is the server's `/api/audio/<song>`. Nobody asks for it while the computer
  plays alone. When another player or a speaker does, the server marks the song wanted and
  waits up to 60 s. The page waits at `GET /api/devices/<id>/wanted` (25 s at a time,
  `X-Device-Secret`; the first secret a device comes with is its own until the server
  restarts), and on an answer has the host (`POST /api/desktop/upload`) `PUT` that file to
  `/api/devices/<id>/songs/<song>`. The server keeps it in `device-songs/` next to the
  database: at most 2 GB, a day unused, emptied when it starts, 500 MB a file at most.
- **Not yet**: radio and autoplay from these songs (the server can't read them); their covers
  for others in the room (the cover link is the computer's own, so others see the placeholder).

## Speakers near this computer ✅

What the phone does for a room on a server ([chromecast](../outputs/chromecast.md#speakers-near-the-phone-in-a-room-on-another-host-)),
with the same code: the host has the speaker drivers (`CastOutputs`, `SonosOutputs`,
`MpdOutputs`, `ZoneOutputs`, `YoutubeTvOutputs`) and `NearbySpeakers`, which follows the room
over its own connection (`FollowerSource`/`RoomFollower`, moved from the Android app to
`host/`) and plays it on them.

- **Finding them**: always while the app runs, from 3 s after the host starts: mDNS with jmDNS
  for Chromecasts, MPDs and zones files (`NetworkDiscovery.kt`, moved from `server/` to
  `host/`), SSDP for Sonos and DIAL for YouTube TVs (the drivers' own `discover`). Tried on
  Windows 10 on 2026-11-13: the living room's Chromecast was found in 10 s.
- **The audio on the network** (`lanServer`): speakers fetch `/api/audio/<song>` from this
  computer, so a second, tiny server listens on all addresses, without the key, with only
  that route, and only for songs in the room the speakers play (`NearbySpeakers.plays`: now,
  before, the queue, autoplay); anything else is a 404. It starts when a speaker first needs
  its address (that's when Windows asks about the firewall) on a port kept in `desktop.json`
  (`lanPort`), so the firewall's answer stays good.
- **The window** (`computerSpeakers.ts`): `GET /api/desktop/nearby` (devices, what plays,
  notices after `?after=`), `POST …/nearby/play { deviceId, hostUrl, code, guestToken }`,
  `…/stop`, `…/volume`. *Play on* (`OutputsSheet`) looks every 2 s while it's open and shows
  them under *Near this computer*, like the phone app's bridge `nearby`; the room page shows
  the notices (looked at every 5 s). The party screen on a TV isn't offered on the computer.
- **Not tried yet**: playing on a speaker from the app (it needs the owner's OK to play sound
  at home), and the AppImage on Linux.

## Songs from your phone ✅

Copying a phone's songs into the computer's song cache over Wi-Fi, only between one account's
devices, and without the phone announcing itself all the time.

- **Asking through the server** (`AccountApi`, in memory): the window's *Copy from my phone*
  `POST /api/account/phone-copy/ask` (login session) opens a 30-minute ask. The open phone app
  waits on `GET /api/auth/phone-copy?after=&wait=25` (its owner's token, `PhoneCopyLink.check`,
  looped while the app is on screen), which the server answers the moment the ask comes (a
  `StateFlow` per account): no polling delay. Asked, the phone `POST`s `/api/auth/phone-copy/here`
  `MyPhone { id, name, url }` and shows itself on its network for 10 minutes. The window reads
  `GET /api/account/phone-copy` every second; when one phone answered within 3 s it starts the
  copy with it, when more did it lists them and you pick.
  There's no push to a phone whose app isn't running (Android only allows that through
  Google's Firebase Cloud Messaging, which YTMP doesn't use), and no background check.
- **By hand** (*Send songs to my computer*, the phone's `POST /api/cache/songs/show`, its own
  page only): shows the phone for 10 minutes and tells the server when it can.
- **Showing itself** (`Nearby.announceDevice`): `_ytmp-device._tcp` with `id`, `name` and
  `owner` (the first 16 hex of SHA-256 of `ytmp-phone:<account>`, `PhoneCopyLink.ownerMark`).
  The desktop host (`PhoneCopy`, jmDNS) lists only phones with the window's account's mark
  (`GET /api/desktop/phones?account=`) or with a key here.
- **The phone's songs** (`CacheCopy.kt`, any host): `GET /api/cache/songs` (the cached songs with
  size and type, then the downloads and own music files that are a YouTube Music song:
  `SongCache.copyable`) and `GET /api/cache/songs/<id>` (`respondCopy`: the cached file, else
  the download's). Only for the device's own page, its owner on another device
  (`isDeviceOwner`), or a computer with a key.
- **Keys** (`CopyKeys`, the phone's `PhoneCopyLink`, hashed in its preferences): the first copy
  uses a host token for the phone's address from the server (`/api/account/host-token`) and
  gets a key with it (`POST /api/cache/songs/key`, only with a login); the desktop host keeps it
  in `phone-keys.json` and uses it from then on (`X-Copy-Key`). A phone with a key that shows
  itself is copied from at once, without the window or the server.
- **Which songs** (`PhoneCopy.Which`, kept in `phone-copy-songs.txt`, `POST /api/desktop/phones/which`
  or `which` with the copy): a set of three groups that don't overlap, `cache` (cached, not
  local), `both` (cached and local) and `local` (local, not cached); by default `cache` and
  `both`. The phone's list says the group: a cached song has its size and `local` (whether it's
  also a download or music file), a local one no size. An older phone leaves `local` out: its
  cached songs count as `cache`.
- **The copy** (`PhoneCopy`): skips songs that are here; for each other song it reads the size
  from the answer and leaves it out when it doesn't fit in what's left of the cache (it would
  push others out), else `SongCache.take` (kept like a fetched song; MP3, FLAC and Ogg are
  recognised too, for a phone's own files; not audio: dropped). The window shows the progress.
- **Not tried yet** with a real phone: the phone part needs a new release on it.

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
  [playback-sync](../outputs/playback-sync.md#search-and-pages-from-your-own-module).
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
- **PO tokens** ([bot check](../sources/ytm-module.md)): like the phone, BotGuard runs in a hidden window
  of the app on `https://www.youtube.com/robots.txt` (YouTube's origin, a tiny page), with
  `android/…/assets/botguard.html`'s script as an initialization script and its answers sent
  back over IPC; `ytmp_ytm/pot.py` asks the shell for it over the loopback (with a key). Runs
  in WebKitGTK (Linux check below).
- **yt-dlp updates itself** (built in the module and used by the phone, see
  [ytm-module.md](../sources/ytm-module.md#yt-dlp-updating-itself-)): the module checks PyPI once a day,
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
- ✅ **Updates** (`updates.rs`): Tauri's updater plugin, the way Discord does it. At every start
  a splash window (`desktop/setup/splash.html`, 240×280, no frame) shows first; it looks
  through GitHub's list of releases (like the Android app: 0.x releases are pre-releases,
  which `/releases/latest` skips) for the newest with a `latest.json` (6 s at most per
  request, so the app still opens quickly offline). A newer version downloads there with a
  progress bar and installs at once, before the app's window opens; otherwise the splash
  says *Starting YTMP…* until the page has loaded (20 s at most), then the window shows.
  GitHub's API allows only 60 requests an hour per network without signing in; when it says
  no, the app reads the releases feed (`releases.atom`) instead and takes the newest release
  whose `latest.json` is there. While the app runs it looks again every 6 hours; a new
  version downloads quietly and a green arrow shows in the title bar (`frame.rs`). The arrow
  opens a popup under it (`update.html`: the release notes without their *Install* part,
  *Restart now* / *Later*, closes when you click elsewhere). *Settings → App → Check for
  updates* (`checkUpdates()`, from 0.47) looks right away: true when there's one (it
  downloads, then the arrow shows), false when this is the newest; one look runs at a time.
  Installing: the page stops playing the room first (`before_quit`, below), the splash says
  *Installing…*, the module and the host stop (the installer replaces their files, which
  Windows won't do while they run), and the installer runs: on Windows quietly (`/S`, no
  NSIS window) and it starts the new version itself. The version being installed is kept in
  `settings.json` (`installing`), so the new version's splash says *Updated to …* and
  doesn't look again right away. Up to 0.48 the app asked in a system dialog (*Update* /
  *Later* / *Skip this version*), and installed with NSIS's own progress window.
  Updates are signed with an updater key of their own (separate from the APK's): the public
  key in `desktop/updater.pub`, built in through `YTMP_UPDATER_PUBKEY`; the private key and
  its password in the secrets `TAURI_SIGNING_PRIVATE_KEY` / `…_PASSWORD`. Builds without the
  key (development) don't look for updates. **To do by the owner**: make the key
  (`cd desktop && pnpm tauri signer generate -w ~/.tauri/ytmp-updater.key`), commit the public
  key as `desktop/updater.pub` and add the two secrets.
- ✅ **Release**: `release.yml`'s `desktop` job (at the same time as the APK's, which makes
  the release), per OS (`ubuntu-22.04`, `windows-latest`): the module (`bundle_module.py`),
  the host, `tauri build` with the tag's version, kept as artifacts. Then `publish` uploads
  `ytmp-<version>-linux-x86_64.AppImage` and `ytmp-<version>-windows-x64-setup.exe` (each that
  built) to the release and writes `latest.json` from their signatures. The Rust crates
  (`Swatinem/rust-cache`) and Gradle are cached; a tag's run can only read caches saved on main,
  so the workflow also runs there as a test build (weekly, and when `Cargo.lock` or the Gradle
  versions change).
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
| This computer's host (Java) | always | ~190 MB (Windows, after playing songs) |
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
9. ✅ This computer's host (Kotlin, the phone's host code) instead of the module doing them.
10. ✅ Speakers (Chromecast, Sonos, MPD, zones, YouTube TVs) from the host, with always-on
    discovery in the app; ✅ copying songs from a phone's cache over Wi-Fi.

## Open questions

- Closing the window: quit, or keep playing with a tray icon?
- Whether the AUR package is wanted once the AppImage works.
