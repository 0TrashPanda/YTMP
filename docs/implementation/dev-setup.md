# Development Setup & Decisions

Practical decisions for starting development.

## Platforms

| Topic | Decision |
|-------|----------|
| Android | **minSdk 30 (Android 11)**, targetSdk = latest. So older phones (friends' phones, the Galaxy A20e test phone) can join and host too. |
| iOS | **Not supported.** iPhones can use the web UI, but background playback in Safari is unreliable and won't be worked around. |
| Browsers | Recent Chrome, Firefox, Edge (and Safari as best effort) |
| Server | **x86-64 Debian**, on Proxmox (VM or LXC with Docker), around an i5-7600. No ARM builds needed for now. |

Why Android 11 (was 13 until 2026-10): only a few things differ, each behind a version
check, and Android lint (`NewApi`) flags every newer call at build time; the release build
runs lint, so none slips through. What differs on 11 and 12:
- No `POST_NOTIFICATIONS` permission to ask for (notifications just show).
- `data_extraction_rules.xml` is ignored: `backup_rules.xml` repeats its rules (the YouTube
  sign-in stays out of backups).
- Before Android 14, `NsdManager` resolves one service at a time: Cast, Sonos and room
  discovery share a queue (`NsdQueue`).
- Future: local files will need `READ_EXTERNAL_STORAGE` below 13 instead of `READ_MEDIA_AUDIO`.

## Identity

| Topic | Decision |
|-------|----------|
| App name | **YTMP** for now. Can still be renamed before the first release (ideas: Aux, Rotation, Juke, Mixtape). |
| Android application ID | `dev.trashpanda.ytmp`. A segment can't start with a digit, so `0trashpanda` isn't allowed in the ID. |
| Repository | Public, on GitHub (`0trashpanda`). The old prototype repo is renamed and archived. |
| License | **AGPL-3.0**: changed versions, including servers run for others, must stay open source. |

## Repository layout (monorepo)

```
protocol/       Kotlin: shared message types (+ generator for frontend/src/lib/protocol.gen.ts)
core/           Kotlin: host core (rooms, queue, permissions, sync), used by server and Android
host/           Kotlin (Ktor): HTTP API, room WebSocket and web app hosting, shared by server and Android
cast/           Kotlin: Google Cast client (Cast v2 protocol), shared by server and Android
server/         Kotlin (Ktor): Linux server, web UI hosting, auth server, admin page
android/        Kotlin: Android app (WebView, Media3, Chaquopy, Ktor host)
frontend/       Svelte 5 + SvelteKit (static) + Tailwind + TypeScript
ytm-module/     Python: ytmp_ytm package (core.py + http.py + jsc.py)
docs/           these docs
deploy/         docker-compose.yml, example ytmp.toml, Caddyfile, Proxmox guide
tools/          smoke-test.py (checks a running phone or server)
```

### Where modules live

- **Built-in modules** (shipped inside the app and server) live **in this repo**: YTM/YT
  (`ytm-module/`), Chromecast (`cast/` + `host/.../CastOutputs.kt`), Sonos (`host/`), and later
  local files and media servers. They change together with the host and
  the [module API](module-api.md), in the same commit.
- **External modules** (separate programs that only talk to YTMP over the module API,
  for example a future Discord bot, or modules written by others) get **their own repo**.
- The **YTM module** still gets its **own Docker image and version** (`ytmp-ytm`), so it can
  be released on its own when YouTube breaks something, without a full YTMP release.
- A module can be moved to its own repo later (with its history) if there's a reason to.

## Tooling

| Topic | Decision |
|-------|----------|
| Kotlin / JVM | Latest Kotlin, Java 21, Gradle with Kotlin DSL |
| Python | Python 3.12+, a plain venv + pip (`ytm-module/.venv`) |
| Frontend | Svelte 5 + SvelteKit (static adapter), Tailwind, Vite |
| Server install | **Docker Compose**: YTMP server + YTM module, optional **Caddy** for HTTPS |
| Config | One `ytmp.toml` |
| CI / releases | GitHub Actions build the signed APK (`release.yml`) and the Docker images (`docker.yml`, on ghcr.io) on every release tag; the YouTube Music module's image also every night when a new yt-dlp or ytmusicapi is out. Servers pull them and Watchtower updates them nightly |

## Conventions

| Topic | Decision |
|-------|----------|
| APK signing | One fixed release key (self-update only works if every version is signed with the same key). Keep a backup of it. |
| Versions | App and server come from the same release. Mismatched protocol versions get a clear "please update" message. |
| Accounts | Username + password, no email. A forgotten password is reset by the server admin. |
| Passwords | PBKDF2-HMAC-SHA256, 600 000 iterations (JDK only, no extra library) |
| YouTube cookies | Encrypted with a secret key from the server config |
| YTM region | Server settings `language` and `location`. Default: **English**, **Belgium** (`language = "en"`, `location = "BE"`). |
| Song cache | Server 5 GB, phone 1 GB by default, configurable. See [song-cache](song-cache.md) |
| Phone hosting | A foreground service with a "YTMP is hosting" notification, so Android doesn't kill the host |
| Logging | Plain log files with limits (server: 14 days / 100 MB, app: 7 days / 20 MB), no external crash reporting. See [server admin](../features/server-admin.md#logs-) and [Android app](android-app.md#logs-) |

## Releases

The Android app is released through GitHub Actions (`.github/workflows/release.yml`):

- **Push a tag** `vX.Y.Z` (e.g. `git tag v0.2.0 && git push origin v0.2.0`). The workflow
  builds the web app and the release APK, version `X.Y.Z` (version code `X*10000+Y*100+Z`,
  so it must go up every release), and publishes a GitHub release with `ytmp-X.Y.Z.apk`
  and notes generated from the commits (replace them by hand with notes for people; the
  app's update dialog shows them, without the *Install* section). `0.x` versions are marked
  as pre-releases; the app's update check still finds them.
- **Run it by hand** (Actions → Release → Run workflow) for a test APK, kept as an artifact
  of the run, without a release.
- **Signing:** every release is signed with the same release key, or phones can't update.
  It lives outside the repo (the maintainer keeps the keystore and its password backed up)
  and reaches the workflow as the repository secrets `YTMP_KEYSTORE_BASE64` and
  `YTMP_KEYSTORE_PASSWORD` (key alias `ytmp`). Locally, `./gradlew :android:assembleRelease`
  signs when `YTMP_KEYSTORE`, `YTMP_KEYSTORE_PASSWORD`, `YTMP_KEY_ALIAS` and
  `YTMP_KEY_PASSWORD` are set; `YTMP_VERSION` sets the version.
- **Server images** (`.github/workflows/docker.yml`): the same tag builds
  `ghcr.io/0trashpanda/ytmp-server` and `ytmp-ytm`, tagged with the version and `latest`.
  Every night at 2:30 UTC it rebuilds `ytmp-ytm` from the latest release with the newest
  yt-dlp and ytmusicapi, only when one of them is new (it checks for an image tagged
  `<version>-ytdlp-<v>-ytm-<v>`). It can also be run by hand. The packages must be **public**
  on GitHub, or servers can't pull them without logging in.
- Debug builds (`installDebug`) are signed with the local debug key, so a phone with a debug
  build has to uninstall it before installing a release (and the other way round).

## Milestones

1. **Server + browser**: YTM search, one room, shared queue, playback in the browser.
2. **Android app as a client**: join a room, native playback (Media3), lock screen and
   Bluetooth controls.
3. **Android host**: hosting on the phone, solo rooms, LAN discovery and QR, on-device YTM.
4. **Chromecast** output (chosen instead of Sonos, which moves to *everything else*).
5. **Everything else**: accounts ✅, permissions and roles ✅, history ✅, radio ✅, Sonos ✅,
   song graph, recap, party screen, …
