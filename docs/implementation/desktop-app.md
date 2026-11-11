# Desktop App (planned)

Feature description: [features/platforms.md](../features/platforms.md#desktop-app-planned).

A **client** for Windows and Linux: the web app in a native window, with the YouTube Music
module running on the same computer. The room, accounts and room sync stay on the YTMP
server; every YouTube request comes from the computer. Nothing here is built yet.

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
- **The window loads the web app from the server**, like the Android app
  ([android-app.md](android-app.md#client-mode-milestone-2-)): the app and the server's UI
  can't get out of sync. A small bundled setup page asks for the server address the first
  time (changeable later).
- **The module is the browser's [own module](playback-sync.md#known-risk-ip-bound-stream-urls)**,
  set up by the app: the shell starts `ytmp-ytm` on `127.0.0.1` with a free port and a new
  random key on every start (`YTMP_YTM_PORT`, `YTMP_MODULE_KEY`), and stops it with the app.
- **The page reaches the module through the shell**, not with `fetch`: WebKitGTK blocks an
  https page from fetching `http://127.0.0.1` (mixed content; "Load failed", the request
  never leaves; seen in the Linux check below), and Chrome's loopback exception doesn't
  exist there. An **initialization script** the shell runs on every page sets
  `window.YtmpDesktop = { version, moduleRequest(path) }`; `moduleRequest` is a Tauri command
  that forwards the request to the module with its key and answers `{ status, body }`. The
  page never sees the module's port or key. The server's origin gets a Tauri capability for
  the command at runtime (it's the address entered at the first start). Same on Windows, so
  there's one way. `ownModule.svelte.ts` sends every module request through one function that
  uses `moduleRequest` when it's there, and shows the module in *Settings → Where songs play
  from* as *This app* (can be switched off, not removed).

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
  `http://127.0.0.1`, so the shell forwards them over Tauri IPC (the shell's side is planned).
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

- **Python**: a PyInstaller one-folder build of `ytm-module/` per OS (Windows, Linux),
  shipped in the app's resources. Same code as the server's and the phone's module.
- **yt-dlp's JavaScript runtime**: a bundled **Deno** binary, with the limits the server
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
  start; when it fails to load or its first lookups fail, it goes back to the bundled one. The
  desktop app only has to start the module with `YTMP_YTM_UPDATE_DIR` set to a folder in its
  data folder. A PyInstaller build must keep yt-dlp importable from outside the bundle: leave
  `yt_dlp` and `yt_dlp_ejs` out of the frozen archive as plain files (or check that a folder
  in front on `sys.path` still wins).

## Media keys

The web app already sets the Media Session API (title, art, play/pause/next/previous).

- **Windows**: WebView2 passes it to Windows' media controls.
- **Linux**: WebKitGTK does MPRIS itself (checked with 2.54): title, artist, album and
  length show in the desktop's media widget, and Pause, Play and Next over MPRIS reach the
  page's Media Session handlers. Still to check with the AppImage's own (older) WebKitGTK; if
  that lacks it, the shell does MPRIS itself (the `souvlaki` crate), fed by the page over IPC.

## Packaging, updates and releases

- **Windows**: an NSIS installer (`.exe`), per user, no admin rights. It installs WebView2 when
  it's missing (rare on Windows 10/11). Unsigned at first, so SmartScreen warns once
  (*More info → Run anyway*); a code-signing certificate can come later.
- **Linux**: an **AppImage** with GStreamer inside (so playback doesn't depend on the
  distribution's plugins), ~80–120 MB. Some distributions need `libfuse2` to run AppImages.
  Built on the oldest Ubuntu GitHub has, so it runs on older distributions too. An AUR
  package that installs the same AppImage can come later.
- **Updates**: Tauri's updater plugin, from the GitHub release's `latest.json`. Updates are
  signed with an updater key of their own (separate from the APK's; private key in a GitHub
  secret, its password stays with the owner). The update dialog shows the release notes, like
  the Android app's.
- **Release**: `release.yml` gets a job per OS (`windows-latest`, `ubuntu-22.04`): build the
  web app's bundled setup page, the module with PyInstaller, fetch Deno, `tauri build`, and
  attach the installer, the AppImage and `latest.json` to the release next to the APK.
- **Code**: a new `desktop/` folder (Tauri project); the module and the web app stay where they
  are.

## Memory, roughly

| Part | When | About |
|---|---|---|
| Window (web view) | always | 100–150 MB |
| YTM module (Python) | always | 60–80 MB |
| Deno | during a song lookup, a few seconds | ~250 MB |
| BotGuard window | while lookups happen, closes after 10 min without | ~20 MB |

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

## Order of work

1. ~~Check the risky parts on Linux first~~ ✅, see above.
2. `desktop/`: the shell, the setup page, the window on the server, the initialization script.
3. The module in the app: PyInstaller builds, Deno, starting and stopping it, the web app
   using *This app* as its own module.
4. PO tokens, and `YTMP_YTM_UPDATE_DIR` for yt-dlp updating itself (built in the module).
5. ✅ Search, Home and pages through the own module (frontend), with the server as fallback.
6. The YouTube Music sign-in in the app.
7. Media keys (if WebKitGTK/WebView2 don't do it), updater, release workflow, README.

## Open questions

- Closing the window: quit, or keep playing with a tray icon?
- Whether the AUR package is wanted once the AppImage works.
