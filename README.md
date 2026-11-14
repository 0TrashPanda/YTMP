# YTMP

**A shared music queue for you and your friends.** Like Spotify Jam, but for YouTube
Music, without Premium, and not tied to a single music service.

> 🚧 **In development, already usable day to day.** The full design is in
> [`docs/`](docs/README.md).

- **One queue, many people.** Someone hosts a room; others join with a short code, a QR
  code, or automatically on the same Wi-Fi, and add, remove and reorder songs together.
- **Host anywhere.** On a Linux server (everyone joins from a browser), or on an Android
  phone with no server at all.
- **Listen anywhere.** Every phone and browser plays along in sync, or the room plays on
  Chromecast, Sonos, MPD (say a Raspberry Pi on your stereo), TVs, or the rooms of a
  multi-room amplifier.
- **Feels like YouTube Music.** Home, search, artist, album, playlist and podcast pages, a
  mini player and a full player with Up next.

## What works today

- **Rooms**: party and solo rooms, a shared queue with Undo, fair order, shuffle, repeat,
  vote to skip and limits; a party screen for the TV; roles and permissions; handing over,
  cloning and moving rooms. → [queue](docs/features/rooms/queue.md),
  [rooms](docs/features/rooms/rooms-and-roles.md), [sharing](docs/features/rooms/sharing.md)
- **Finding music**: YouTube Music's Home, search and pages, links pasted in search, song
  recognition, synced lyrics with karaoke, radio and autoplay, podcasts that resume, a song
  graph from your listening, Spotify playlist import.
  → [UI](docs/features/ui/README.md), [history and recommendations](docs/features/music/history-and-recommendations.md),
  [importing playlists](docs/features/music/importing-playlists.md)
- **Playing**: in sync on every device, on Chromecast, Sonos, MPD, YouTube TVs and
  amplifier zones; equalizer, sleep timer, downloads on the phone, a song cache and offline
  playlists on the phone and the computer, a *Local* tab with your own music.
  → [playback and outputs](docs/features/playback-and-outputs.md), [sources](docs/features/music/sources.md)
- **People and accounts**: optional YTMP accounts with listening history, your YouTube Music
  sign-in (on the phone or the server), friends and shared songs, your rooms on all your
  devices, settings that follow you, an admin page for the server.
  → [accounts](docs/features/accounts/accounts.md), [friends](docs/features/accounts/friends.md),
  [server admin](docs/features/accounts/server-admin.md)
- **Apps**: Android (lock screen, widget, hosting on the phone, updates itself) and a desktop
  app for Windows and Linux (its own YouTube lookups, media keys, local music, speakers
  nearby). → [platforms](docs/features/platforms.md)

## What's next

- The desktop app: your YouTube Music sign-in in the app, and the bot-check token
  ([status](docs/implementation/apps/desktop-app.md#order-of-work))
- More sources: YouTube, other music folders, Plex / Jellyfin
- Recaps from your listening history ("you listened 12 hours with Sam")
- Importing playlists from other exports (Google Takeout) and accounts, and into YTMP playlists
- YTMP playlists, an Explore page

More ideas: [future ideas](docs/features/future-ideas.md).

## Install

| Platform | |
|----------|---|
| **Android 11+** | The APK from the [latest release](https://github.com/0TrashPanda/YTMP/releases). Open it on your phone (allow installing apps when asked); after that the app updates itself. |
| **Windows, Linux** | The desktop app from the [latest release](https://github.com/0TrashPanda/YTMP/releases): `…-windows-x64-setup.exe` (Windows warns once: *More info → Run anyway*) or `…-linux-x86_64.AppImage`. Or use a browser. |
| **Any browser** | Join rooms, or host them through a server. |
| **iOS** | Not supported (the web app may work, but not in the background). |

### Running a server

Needs Docker with Compose.

```sh
cd deploy
cp ytmp.example.toml ytmp.toml          # optional, every setting has a default
echo "YTMP_MODULE_KEY=$(openssl rand -hex 32)" > .env
docker compose up -d
```

Open `http://<server>:8080`. The server updates itself every night. For HTTPS, your own
domain, bandwidth (`[audio] proxy`) and updating by hand, follow the step-by-step guide:
[running YTMP on Proxmox](deploy/proxmox.md).

## Known problems

- **Firefox on Windows 10 freezes after a while of playing** (the music keeps going). It's a
  Windows service, not YTMP: stop and disable the **AVCTP service**.
  [Details](docs/features/playback-and-outputs.md#firefox-on-windows-freezes-while-it-plays).

## Development

How to run each part, tests and checks: [dev setup](docs/implementation/dev-setup.md#running-it-locally).
How it's built: [implementation docs](docs/README.md#implementation), starting with the
[architecture](docs/implementation/architecture.md). Releases:
[dev setup](docs/implementation/dev-setup.md#releases).

## License

[AGPL-3.0](LICENSE). You can use, change and share YTMP freely, but changed versions,
including servers you run for others, must also be open source.
