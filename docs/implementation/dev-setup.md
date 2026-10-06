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
protocol/       Kotlin: shared message types (+ generated protocol.d.ts)
core/           Kotlin: host core (rooms, queue, permissions, sync), used by server and Android
host/           Kotlin (Ktor): HTTP API, room WebSocket and web app hosting, shared by server and Android
cast/           Kotlin: Google Cast client (Cast v2 protocol), shared by server and Android
server/         Kotlin (Ktor): Linux server, web UI hosting, auth server, admin page
android/        Kotlin: Android app (WebView, Media3, Chaquopy, Ktor host)
frontend/       Svelte 5 + SvelteKit (static) + Tailwind + TypeScript
ytm-module/     Python: ytmp_ytm package (core.py + http.py)
modules/        Kotlin: built-in modules (local-files, plex, sonos, chromecast, …)
docs/           these docs
deploy/         docker-compose.yml, example ytmp.toml, Caddyfile
```

### Where modules live

- **Built-in modules** (shipped inside the app and server) live **in this repo**: YTM/YT,
  local files, media servers, Sonos, Chromecast. They change together with the host and
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
| Python | Python 3.12+, managed with `uv` |
| Frontend | Svelte 5 + SvelteKit (static adapter), Tailwind, Vite |
| Server install | **Docker Compose**: YTMP server + YTM module, optional **Caddy** for HTTPS |
| Config | One `ytmp.toml` |
| CI / releases | GitHub Actions build the APK and Docker images on every release |

## Conventions

| Topic | Decision |
|-------|----------|
| APK signing | One fixed release key (self-update only works if every version is signed with the same key). Keep a backup of it. |
| Versions | App and server come from the same release. Mismatched protocol versions get a clear "please update" message. |
| Accounts | Username + password, no email. A forgotten password is reset by the server admin. |
| Passwords | Hashed with Argon2 |
| YouTube cookies | Encrypted with a secret key from the server config |
| YTM region | Server settings `language` and `location`. Default: **English**, **Belgium** (`language = "en"`, `location = "BE"`). |
| Audio cache | Default 5 GB, configurable |
| Phone hosting | A foreground service with a "YTMP is hosting" notification, so Android doesn't kill the host |
| Logging | Plain logs, no external crash reporting |

## Releases

The Android app is released through GitHub Actions (`.github/workflows/release.yml`):

- **Push a tag** `vX.Y.Z` (e.g. `git tag v0.2.0 && git push origin v0.2.0`). The workflow
  builds the web app and the release APK, version `X.Y.Z` (version code `X*10000+Y*100+Z`,
  so it must go up every release), and publishes a GitHub release with `ytmp-X.Y.Z.apk`
  and notes generated from the commits. `0.x` versions are marked as pre-releases.
- **Run it by hand** (Actions → Release → Run workflow) for a test APK, kept as an artifact
  of the run, without a release.
- **Signing:** every release is signed with the same release key, or phones can't update.
  It lives outside the repo (the maintainer keeps the keystore and its password backed up)
  and reaches the workflow as the repository secrets `YTMP_KEYSTORE_BASE64` and
  `YTMP_KEYSTORE_PASSWORD` (key alias `ytmp`). Locally, `./gradlew :android:assembleRelease`
  signs when `YTMP_KEYSTORE`, `YTMP_KEYSTORE_PASSWORD`, `YTMP_KEY_ALIAS` and
  `YTMP_KEY_PASSWORD` are set; `YTMP_VERSION` sets the version.
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
