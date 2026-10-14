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
- **Optional accounts.** Listening history, your YouTube Music sign-in on your server, and
  your rooms on all your devices; later playlists and recaps ("you listened 12 hours with
  Sam").

## What works today

**Rooms**
- Party rooms that friends join from a browser or the app (room code, QR code, or rooms
  nearby on the same Wi-Fi), and solo rooms just for you. Rooms survive restarts.
- One shared queue: add, play next, drag to reorder, swipe to remove, jump to any song,
  clear. Played songs stay above the current one; autoplay songs below.
- Shuffle and repeat (queue or one song), fair order (one song per person per round),
  vote to skip, and limits (songs per person, song length, no recent repeats).
- A party screen for a TV, laptop or tablet: big album art in its colour, what's next and
  who added it, and a QR code to join. A TV links with a code, like YouTube's, and a TV with
  Chromecast shows it from *Play on* (it plays the music too), also a TV at your place in a
  room on a server, from the app.
- Everyone who listens stays in sync, with a per-device sync adjustment (for Bluetooth).
- Hand a room to someone else, clone it (here, onto your phone or onto your server), and
  invite or move everyone to the clone, keeping their roles. Server rooms can be always on.

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
- Music comes straight from YouTube, also when you listen to a server elsewhere: the app
  looks songs up itself, and a browser can use a YouTube Music module on your own computer
  (*Settings → Playback*), instead of streaming through the server.

**People and accounts**
- Roles and permissions with a Discord-style editor (one role per person plus per-person
  overrides), kicking and bans.
- YTMP accounts (username and password) on a server, also usable in rooms on a phone that
  is linked to that server.
- Listening history (off by default), with search, delete and export.
- **Sign in to YouTube Music on your phone:** your own Home, a Library with your playlists,
  liked songs and podcasts (searchable), liking and saving to playlists, and search and radio that fit
  your taste. The sign-in stays on the phone, and guests in a room on your phone never see it.
- The same on a **server account**, in every browser you log in with; the phone and the
  server can share one sign-in.
- Thumbs up and down, and editing your own playlists (remove, move, rename, delete).
- Your YouTube Music in rooms on someone else's phone or server too, straight from your
  server (the host never gets your sign-in).
- **Your rooms on all your devices:** rooms on your phone show up on your server and the
  other way round; open one there or move it (with an account).
- Log in once: the server's pages and the app's own pages share the login.

**The app**
- Checks for new versions when it starts and installs them; *Check for updates* and *Send
  logs* in the settings.
- Server and app keep logs that clean up after themselves (what happened when a song
  stopped or buffered).

## What's next

- Dealing with YouTube's "confirm you're not a bot" block
- New podcast episodes on Home, playback speed, played marks
- More sources: YouTube, local files, Plex / Jellyfin
- Learning which songs go together (song graph) for better radio, and recaps
- YTMP playlists, lyrics, explore page, sleep timer

## Platforms

| Platform | Status |
|----------|--------|
| Linux server (Docker) | ✅ Hosts rooms, serves the web app, accounts, listening history and YouTube Music sign-ins |
| Android 11+ | ✅ Join rooms with native playback and lock screen controls; host solo rooms or parties on the phone itself, with YouTube Music running on the phone (no server needed); sign in to YouTube Music; updates itself |
| Web browser | ✅ Join rooms, or host through a server |
| iOS | Not supported (the web app may work, but background playback won't) |

## Download

**Android:** get the APK from the [latest release](https://github.com/0TrashPanda/YTMP/releases)
and open it on your phone (allow installing apps from your browser or file manager when
asked). After that the app finds new releases itself and offers to install them; they keep
your rooms and sign-ins.

## Running a server

Needs Docker with Compose.

```sh
cd deploy
cp ytmp.example.toml ytmp.toml          # optional, every setting has a default
echo "YTMP_MODULE_KEY=$(openssl rand -hex 32)" > .env
docker compose up -d
```

Open `http://<server>:8080`. The images come from GitHub, and the server **updates itself**
every night (Watchtower): new releases, and a new yt-dlp whenever YouTube breaks something.
To update right away: `docker compose pull && docker compose up -d`. See
[updating](deploy/proxmox.md#updating). For HTTPS, put a reverse proxy such as Caddy in front of it
(`deploy/docker-compose.caddy.yml` does that for you), or use your own nginx.

**Step-by-step guide:** [running YTMP on Proxmox](deploy/proxmox.md) (LXC container with
Docker, your own domain with HTTPS through Caddy or your existing nginx).

**Bandwidth:** players stream straight from YouTube, but YouTube's links only work on the
server's own internet connection. Elsewhere they fall back to streaming through the server.
To keep music off a slow upload, set `[audio] proxy = "lan"` in `ytmp.toml`: then only
devices on the server's network (like speakers at home) may do that. See the guide.
The app doesn't need the server for that: it looks songs up itself. In a browser, run the
YouTube Music module on your own computer and enter it under *Settings → Playback*:
`docker run -d -p 127.0.0.1:8401:8401 -e YTMP_MODULE_KEY=<a key you choose> ghcr.io/0trashpanda/ytmp-ytm`.

## Development

| Part | Folder | Run |
|------|--------|-----|
| YTM module (Python 3.12+) | `ytm-module/` | `python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'`, then `YTMP_MODULE_KEY=dev YTMP_YTM_JS_RUNTIME=node .venv/bin/ytmp-ytm` |
| Server (Kotlin, JDK 21 is downloaded by Gradle) | `server/`, `core/`, `protocol/` | `YTMP_MODULE_KEY=dev ./gradlew :server:run` |
| Web app (Svelte, pnpm) | `frontend/` | `pnpm install && pnpm dev`, then open http://localhost:5173 |
| Android app | `android/` | `./gradlew :android:installDebug`, then for the emulator: `adb shell am start -n dev.trashpanda.ytmp/.MainActivity --es server http://10.0.2.2:8080` |

- Tests: `./gradlew test` and `cd ytm-module && .venv/bin/pytest`.
- Smoke test of a running phone or server (search, pages, radio, streaming; read-only):
  `python3 tools/smoke-test.py URL`. For the app on a phone: `adb forward tcp:8765 tcp:8765`,
  keep the app open, and use `http://127.0.0.1:8765`.
- After changing anything in `protocol/`, run `./gradlew :protocol:generateTs` to update the
  web app's types (`frontend/src/lib/protocol.gen.ts`).
- Releases: push a tag like `v0.2.0` and GitHub Actions builds the signed APK and publishes
  the release (`.github/workflows/release.yml`). See
  [dev setup](docs/implementation/dev-setup.md#releases).
- yt-dlp needs a JavaScript runtime for YouTube: Deno by default, or set
  `YTMP_YTM_JS_RUNTIME=node` to use Node.

## Documentation

- [Features](docs/README.md#features): what YTMP does
- [Implementation](docs/README.md#implementation): how it's built (Kotlin, Svelte, Python)
- [Open questions](docs/open-questions.md)

## License

[AGPL-3.0](LICENSE). You can use, change and share YTMP freely, but changed versions,
including servers you run for others, must also be open source.
