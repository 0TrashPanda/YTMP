# YTMP

**A shared music queue for you and your friends.** Like Spotify Jam, but for YouTube
Music, without Premium, and not tied to a single music service.

> 🚧 **In development, already usable day to day.** Rooms, the Android app (with hosting on
> the phone itself), Chromecast and Sonos, accounts, roles, radio, podcasts and a YouTube
> Music–style app work. See [what works](#what-works-today) and [what's next](#whats-next).
> The full design is in [`docs/`](docs/README.md).

## What it does

- **One queue, many people.** Someone hosts a room, others join with a short room code,
  a QR code, or automatically on the same Wi-Fi, and add, remove and reorder songs together.
- **Host anywhere.** On a Linux server (everyone joins from a browser), or on an Android
  phone with no server at all.
- **Listen anywhere.** Every phone or browser can play along in sync, or the room plays on
  Chromecast and Sonos speakers.
- **Feels like YouTube Music.** Home with suggestions, search with chips, artist, album,
  playlist and podcast pages, a mini player and a full player with Up next.
- **Music and podcasts.** Episodes resume where you left off, with skip buttons.
- **Radio & recommendations.** Start a radio, keep the party going with autoplay, or
  browse similar songs.
- **Permissions & roles.** Decide who can add, skip, remove or manage, per person or per
  role.
- **Optional accounts.** Listening history now; later moving a room between your
  devices, playlists and recaps ("you listened 12 hours with Sam").

## What works today

**Rooms**
- Party rooms that friends join from a browser or the app (room code, QR code, or rooms
  nearby on the same Wi-Fi), and solo rooms just for you. Rooms survive restarts.
- One shared queue: add, play next, drag to reorder, swipe to remove, jump to any song,
  clear. Played songs stay above the current one; autoplay songs below.
- Everyone who listens stays in sync, with a per-device sync adjustment (for Bluetooth).

**Finding music** (through YouTube Music, no Premium needed)
- Home with YouTube Music's suggestions (quick picks, new releases, mixes).
- Search with suggestions while typing and YouTube Music's chips: songs, videos, albums,
  artists, playlists, podcasts, episodes.
- Artist, album, playlist and podcast pages.
- Radio from a song, autoplay when the queue runs out, find similar.
- Podcasts: exact episode lengths, resume where you stopped, back 10 s / ahead 30 s, no
  music radio after an episode.

**The player**
- A mini player that opens a full player (tap or swipe up): album art, controls, like,
  save to playlist, and Up next / Related tabs.
- Play on this device, on Chromecasts (including speakers with Chromecast built in) and on
  Sonos speakers, with their volume.
- Android: lock screen and notification controls, keeps playing with the screen off.

**People and accounts**
- Roles and permissions with a Discord-style editor (one role per person plus per-person
  overrides), kicking and bans.
- YTMP accounts (username and password) on a server, also usable in rooms on a phone that
  is linked to that server.
- Listening history (off by default), with search, delete and export.
- **Sign in to YouTube Music on your phone:** your own Home, a Library with your playlists,
  liked songs and podcasts, liking and saving to playlists. The sign-in stays on the phone,
  and guests in a room on your phone never see it.

## What's next

- Using your YouTube Music sign-in in rooms on someone else's phone or a server
- New podcast episodes on Home, playback speed, played marks
- Fair queue (round-robin, so nobody takes over the queue)
- More sources: YouTube, local files, Plex / Jellyfin
- Learning which songs go together (song graph) for better radio, and recaps
- Moving a room between your devices, YTMP playlists
- Lyrics, explore page, party screen for a TV

## Platforms

| Platform | Status |
|----------|--------|
| Linux server (Docker) | ✅ Hosts rooms, serves the web app, accounts and listening history |
| Android 13+ | ✅ Join rooms with native playback and lock screen controls; host solo rooms or parties on the phone itself, with YouTube Music running on the phone (no server needed); sign in to YouTube Music |
| Web browser | ✅ Join rooms, or host through a server |
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
