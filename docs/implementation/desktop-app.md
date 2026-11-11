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
  The page learns where it is from an **initialization script** that the shell runs on every
  page (`window.YtmpDesktop = { version, module: { url, key } }`), so no IPC is needed for
  that. `ownModule.svelte.ts` uses it instead of `saved.ownModule` and shows it in *Settings →
  Where songs play from* as *This app* (can be switched off, not removed). The module already
  answers other origins when it has a key (CORS and Chrome's private network preflight, see
  [module API](module-api.md)).

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

Today the own module is only used for **stream links** (`ownStreamUrl`). For the desktop app
the web app also asks it for:

- search and suggestions (`/search`, `/search/suggestions`), Home (`/home`), artist, album,
  playlist, podcast and song pages, pasted links (`/resolve`), lyrics;
- with a YouTube Music sign-in: the `/me/…` requests (library, likes, playlists, history,
  the account's own Home and search), with the sign-in in `X-Ytm-Cookie`.

How:

- One place in the frontend picks where a catalog request goes (own module when it's usable,
  else the server's `/api/…`), so the pages don't each decide. Where the host adds something to
  the module's answer (Home's 30-minute cache, the *In your library* section of search, the
  shape of `SearchResponse` vs. the module's `SearchPage`), that part moves to the frontend
  or stays on the host's route and is skipped for local answers; checked per route when
  building.
- When the module fails (down, blocked by the bot check, timeout), that request goes to the
  server instead, and `moduleStatus` already makes the next ones skip the module for a while.
- Browsers with an own module get the same (it's the same code), not only the app.
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
  of the app (`android/…/assets/botguard.html`, shared), and `ytmp_ytm/pot.py` asks the shell
  for it over the loopback. If BotGuard doesn't run in WebKitGTK, Linux uses bgutil's provider
  in script mode with the bundled Deno instead.
- **yt-dlp updates itself** (the plan the phone has too, see
  [ytm-module.md](ytm-module.md#plan-embed-python-with-chaquopy-and-update-yt-dlp-at-runtime)):
  the module checks PyPI once a day, puts a newer yt-dlp in the app's data folder and loads it
  before the bundled one; when it fails to load or its first lookups fail, it goes back to the
  bundled one. Built once in the module, used by the desktop app and the phone.

## Media keys

The web app already sets the Media Session API (title, art, play/pause/next/previous).

- **Windows**: WebView2 passes it to Windows' media controls.
- **Linux**: WebKitGTK's own MPRIS support depends on its version. If it isn't there or not
  good enough, the shell does MPRIS itself (the `souvlaki` crate, which also covers Windows),
  fed by the page over Tauri's IPC.

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

## Order of work

1. **Check the risky parts on Linux first** (a throwaway Tauri window on the real server):
   songs play in WebKitGTK (Opus/WebM and M4A), the page may fetch from `http://127.0.0.1`
   while it's on `https`, media keys reach MPRIS, BotGuard runs. Whatever fails decides the
   fallbacks above before anything else is built.
2. `desktop/`: the shell, the setup page, the window on the server, the initialization script.
3. The module in the app: PyInstaller builds, Deno, starting and stopping it, the web app
   using *This app* as its own module.
4. PO tokens and yt-dlp updating itself.
5. Search, Home and pages through the own module (frontend), with the server as fallback.
6. The YouTube Music sign-in in the app.
7. Media keys (if WebKitGTK/WebView2 don't do it), updater, release workflow, README.

## Open questions

- Closing the window: quit, or keep playing with a tray icon?
- Whether the AUR package is wanted once the AppImage works.
