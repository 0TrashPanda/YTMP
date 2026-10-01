# Development Setup & Decisions

Practical decisions for starting development.

## Platforms

| Topic | Decision |
|-------|----------|
| Android | **minSdk 33 (Android 13)**, targetSdk = latest. Everyone in the group has a recent phone. |
| iOS | **Not supported.** iPhones can use the web UI, but background playback in Safari is unreliable and won't be worked around. |
| Browsers | Recent Chrome, Firefox, Edge (and Safari as best effort) |
| Server | **x86-64 Debian**, on Proxmox (VM or LXC with Docker), around an i5-7600. No ARM builds needed for now. |

Why Android 13: it brings the permissions we need in their modern form
(`READ_MEDIA_AUDIO` for local files, `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES` for
LAN discovery without location permission), so there's no code for older Android.
Android 14 and 15 add nothing that makes YTMP noticeably easier, so there's no reason to
require them.

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
server/         Kotlin (Ktor): Linux server, web UI hosting, auth server, admin page
android/        Kotlin: Android app (WebView, Media3, Chaquopy, Ktor host)
frontend/       Svelte 5 + SvelteKit (static) + Tailwind + TypeScript
ytm-module/     Python: ytmp_ytm package (core.py + http.py)
docs/           these docs
deploy/         docker-compose.yml, example ytmp.toml, Caddyfile
```

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

## Milestones

1. **Server + browser**: YTM search, one room, shared queue, playback in the browser.
2. **Android app as a client**: join a room, native playback (Media3), lock screen and
   Bluetooth controls.
3. **Android host**: hosting on the phone, solo rooms, LAN discovery and QR, on-device YTM.
4. **Sonos** output.
5. **Everything else**: accounts, permissions and roles, history, song graph, radio, party
   screen, Chromecast, …
