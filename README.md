# YTMP

**A shared music queue for you and your friends.** Like Spotify Jam, but for YouTube
Music, without Premium, and not tied to a single music service.

> 🚧 **Early development.** The design is written down in [`docs/`](docs/README.md).
> Code is coming.

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
| Linux server (Docker) | Planned: hosts rooms and serves the web app |
| Android 13+ | Planned: host and join, works without a server |
| Web browser | Planned: join, or host through a server |
| iOS | Not supported (the web app may work, but background playback won't) |

## Documentation

- [Features](docs/README.md#features): what YTMP does
- [Implementation](docs/README.md#implementation): how it's built (Kotlin, Svelte, Python)
- [Open questions](docs/open-questions.md)

## License

[AGPL-3.0](LICENSE). You can use, change and share YTMP freely, but changed versions,
including servers you run for others, must also be open source.
