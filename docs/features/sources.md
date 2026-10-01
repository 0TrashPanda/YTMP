# Sources

A **source** provides music: search, metadata and the actual audio.
Sources are **modular**: a host can use several at once, and new ones can be added
over time.

## Planned sources

| Source | Status |
|--------|--------|
| YouTube Music | First / core |
| YouTube | Planned |
| Local files | Planned. Includes songs downloaded through YTMP. |
| Media servers (Plex, Jellyfin, Subsonic/Navidrome, file servers, …) | Planned, linked to an account |
| Spotify | Future (see [Spotify module](future-ideas.md#spotify-module)) |
| SoundCloud | Future |
| … | Future |

## Media servers

Users can link their own music servers to their **YTMP account**: a file server, a Plex
server, or another music file server. After linking, they can **search** it and **add
songs** from it, just like any other source.

- Songs added to a room from your media server **keep playing after you leave** the room.
- Others can **download** them only if you have **enabled downloads** on that source.

## YouTube source

Mainly for music that is **only on YouTube** and not on YouTube Music (covers, live
versions, remixes, …). It plays the audio like any other song.

Watching **videos** together in a room is possible later, but it is **not the primary
focus** (see [future ideas](future-ideas.md)).

## YouTube Music source

- Search and fetch metadata for songs, albums, artists and playlists.
- Play without YouTube Premium.
- Optional **local cache** so frequently played songs start faster.
- Available both **as a server-side service** and **on-device on Android**, so a
  phone can host without a server.
- With a [linked YouTube account](accounts.md#linked-youtube-account): access
  to the user's playlists and likes.

Implementation details: [implementation/ytm-module.md](../implementation/ytm-module.md).
