# Sources

A **source** provides music: search, metadata and the actual audio.
Sources are **modular**: a host can use several at once, and new ones can be added
over time.

## Modules vs. sources

- A **module** is the code that knows how to talk to a *kind* of service: YouTube Music,
  Plex, Jellyfin, local files, …
- A **source** is one specific connection that uses a module: "YouTube Music",
  "Jonah's Plex at home", "the server's /music folder", "the music on my phone".

You can only add a source if there is a **module** for it. All built-in modules are in
every build (server and Android app). External modules only exist on a server whose
admin installed them.

## Where sources are added

| Level | Added by | Examples | Available to |
|-------|----------|----------|--------------|
| **Host** | Server admin (server config) or the hosting phone's owner | YouTube Music, YouTube, the server's music folders, the music on the hosting phone | Everyone in rooms on that host (server music folders can be limited to account holders in the server config) |
| **Account** | You, in your account settings | Your Plex/Jellyfin/Subsonic server, your linked YouTube account | You, in every room you join with your account |
| **Device** | Automatic | The music stored on your own phone | You (see [songs from your own device](#songs-from-your-own-device)) |

In a room:

- When you search, you see results from the **host's sources plus your own**.
- If the host has **no module** for one of your account sources, that source shows as
  *not available on this host*.
- The **host must be able to reach** the source. A Plex server at home works for a
  phone or home-server host, but a server in a datacenter can only use it if it is
  reachable over the internet (see [multiple addresses](#multiple-addresses)).

### Sharing a source with the room

Your account sources are **only searchable by you** by default. Per room you can turn on
**Share with this room**, so everyone in the room can search and add from it.

### Multiple addresses

A source that is reached over the network (Plex, Jellyfin, a file server, …) can have
**several addresses** (IPs or domain names), in **order of priority**, for example:

1. `192.168.1.10:32400` (local network)
2. `plex.example.com` (over the internet)

The host uses the **first address that works**, so it uses fast local access when it is
on the same network, and falls back to the internet otherwise. Addresses are re-checked
now and then, for example when the host changes network.

### Songs from your own device

When you are a client in someone else's room, you can add songs stored **on your own
phone**, if the room allows it (permission: *Send songs from own device*).

- Your phone **sends the whole file** to the host when you add the song, so it keeps
  working after you leave.
- The host only keeps these files **temporarily**, and deletes them when the room
  closes.

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

Users can link their own music servers to their **YTMP account** as an
[account source](#where-sources-are-added): a file server, a Plex server, or another
music file server. After linking, they can **search** it and **add
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
