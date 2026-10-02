# YTMP

**A shared music queue for you and your friends.** Like Spotify Jam, but for YouTube
Music, without Premium, and not tied to a single music service.

> 🚧 **Early development.** Milestone 1 works: host or join a room in the browser, search
> YouTube Music, share a queue and play in sync. The full design is in [`docs/`](docs/README.md).

## What it does

- **One queue, many people.** Someone hosts a room, others join with a short room code
  (or automatically on the same Wi-Fi) and add, remove and reorder songs together.
- **Host anywhere.** On a Linux server (everyone joins from a browser), or on an Android
  phone with no server at all.
- **Fair queue.** Optional round-robin ordering, so nobody takes over the queue.
- **Listen anywhere.** Every phone or browser can play along in sync, or send the audio
  to outputs like Sonos or Chromecast.
- **Many sources.** YouTube Music first, plus YouTube, local files and your own media
  server (Plex, Jellyfin, …).
- **Radio & recommendations.** Start a radio, keep the party going with autoplay, or
  browse similar songs. YTMP learns which songs go together from what you play.
- **Permissions & roles.** Decide who can add, skip, remove or manage, per person or per
  role.
- **Optional accounts.** For moving a room between your devices, playlists, listening
  history and recaps ("you listened 12 hours with Sam").

## Platforms

| Platform | Status |
|----------|--------|
| Linux server (Docker) | ✅ Milestone 1: hosts rooms and serves the web app |
| Android 13+ | ✅ Milestone 2: join a room with native playback and lock screen controls. Hosting is next. |
| Web browser | ✅ Milestone 1: join, or host through a server |
| iOS | Not supported (the web app may work, but background playback won't) |

## Running a server

Needs Docker with Compose.

```sh
cd deploy
cp ytmp.example.toml ytmp.toml          # optional, every setting has a default
echo "YTMP_MODULE_KEY=$(openssl rand -hex 32)" > .env
docker compose up -d --build
```

Open `http://<server>:8080`. For HTTPS, put a reverse proxy such as Caddy in front of it.

## Development

| Part | Folder | Run |
|------|--------|-----|
| YTM module (Python 3.12+) | `ytm-module/` | `python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'`, then `YTMP_MODULE_KEY=dev YTMP_YTM_JS_RUNTIME=node .venv/bin/ytmp-ytm` |
| Server (Kotlin, JDK 21 is downloaded by Gradle) | `server/`, `core/`, `protocol/` | `YTMP_MODULE_KEY=dev ./gradlew :server:run` |
| Web app (Svelte, pnpm) | `frontend/` | `pnpm install && pnpm dev`, then open http://localhost:5173 |
| Android app | `android/` | `./gradlew :android:installDebug`, then for the emulator: `adb shell am start -n dev.trashpanda.ytmp/.MainActivity --es server http://10.0.2.2:8080` |

- Tests: `./gradlew test` and `cd ytm-module && .venv/bin/pytest`.
- After changing anything in `protocol/`, run `./gradlew :protocol:generateTs` to update the
  web app's types (`frontend/src/lib/protocol.gen.ts`).
- yt-dlp needs a JavaScript runtime for YouTube: Deno by default, or set
  `YTMP_YTM_JS_RUNTIME=node` to use Node.

## Documentation

- [Features](docs/README.md#features): what YTMP does
- [Implementation](docs/README.md#implementation): how it's built (Kotlin, Svelte, Python)
- [Open questions](docs/open-questions.md)

## License

[AGPL-3.0](LICENSE). You can use, change and share YTMP freely, but changed versions,
including servers you run for others, must also be open source.
