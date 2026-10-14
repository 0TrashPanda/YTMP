# YTMP

A shared music queue (like Spotify Jam) for YouTube Music: a Kotlin host core shared by a
Linux server and an Android app, one Svelte web app for both, and a Python module
(ytmusicapi + yt-dlp). The design is in `docs/` (start at `docs/README.md`); what's built and
what's planned is marked there.

## Layout

- `protocol/` message types (Kotlin is the source of truth) · `core/` rooms, queue, roles ·
  `host/` HTTP/WebSocket API, shared by server and phone · `cast/` Chromecast client ·
  `server/` Linux server, accounts, room sync · `android/` the app (WebView, Media3, Chaquopy,
  its own host) · `frontend/` Svelte 5 + Tailwind · `ytm-module/` Python · `deploy/` Docker
  Compose, Caddy, Proxmox guide · `tools/smoke-test.py`.

## Commands

- Tests: `./gradlew test` and `cd ytm-module && .venv/bin/python -m pytest -q`.
- Web app: `cd frontend && pnpm check && pnpm build` (the Android build bundles `frontend/build`).
- Android: `./gradlew :android:assembleDebug`; release lint: `./gradlew :android:lintRelease`.
- After changing anything in `protocol/`: `./gradlew :protocol:generateTs` (new request types
  must also be added as roots in `protocol/.../tsgen/TsGenerator.kt`).
- Running the server locally needs Java 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`):
  `./gradlew :server:installDist`, then `server/build/install/server/bin/server ytmp.toml`,
  with the module from `ytm-module/.venv/bin/ytmp-ytm` (`YTMP_MODULE_KEY`, `YTMP_YTM_PORT`,
  `YTMP_YTM_JS_RUNTIME=node`).
- Browser checks: `playwright-core` with the system `chromium`, from a scratch folder.

## Releasing

- A tag `vX.Y.Z` builds the signed APK (`.github/workflows/release.yml`) and the server
  images on ghcr.io (`docker.yml`). Servers update themselves nightly (Watchtower).
- The app's update dialog shows the release notes: replace GitHub's generated notes with short
  plain ones (`gh release edit --notes-file`), keeping an *Install* section at the end.
- New features that change behaviour bump the minor version; fixes the patch version.

## How the owner likes to work

- Ask before committing to pushing or releasing; commit locally freely.
- Ask before any test that plays sound on the home speakers or TV (say which and how loud).
- The owner's own phone runs real release builds: never install a debug build over it (it
  would need an uninstall and lose its data). For tests on it, the owner builds a signed APK
  themselves (the key's password stays with them).
- Asked to make something work in YTMP: build it, or say plainly that it isn't possible and
  why. No menus of workarounds unless asked.
- Make things feel like YouTube Music (mobile first); podcasts matter too.
- Docs are in English, features (what) separate from implementation (how); keep them in step
  with the code, and the README's *what works* / *what's next* too. Plain language in what
  users see.
- Reply in English, also when the owner writes in Dutch, unless asked to answer in Dutch.

## Gotchas

- Running the emulator next to Gradle overloads the PC: `./gradlew --stop` first, and start
  the emulator with `-memory 3072 -cores 4`.
- `pgrep -f`/`pkill -f` with a pattern that's also in the same shell command kills that shell.
  Use a pattern like `[j]ava .*server/build/install/server/lib` and run it on its own.
- Many real YouTube lookups in a short time get the home network blocked for hours ("confirm
  you're not a bot"): keep real extraction tests sparing.
- Kotlin: `/*` inside a KDoc comment starts a nested comment.
