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

> Planned. Today every host has one source, YouTube Music, set up with the host (server
> config, or built into the app).

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
| YouTube Music | ✅ Built (server service and on the phone) |
| YouTube | Planned |
| Local music | ✅ A Raspberry Pi's music folder (with MPD) and the music on the hosting phone, in the [Local tab](#local-music-). Other folders and songs downloaded through YTMP: planned. |
| Media servers (Plex, Jellyfin, Subsonic/Navidrome, file servers, …) | Planned, linked to an account |
| Spotify | Future (see [Spotify module](future-ideas.md#spotify-module)) |
| SoundCloud | Future |
| … | Future |

## Local music ✅

Music in the house, next to YouTube Music, in its own **Local** tab in the bottom bar (on a
computer: the left side). It shows when there's local music:

- **Music on the network**: a Raspberry Pi with MPD (say next to the stereo) shares its music
  folder with YTMP (see [implementation](../implementation/local-music.md)). Every phone and
  home server on that Wi-Fi finds it by itself.
- **Music on this phone**: the songs stored on the phone, in rooms on the phone. Android asks
  once whether YTMP may read them.

The tab is like YouTube Music's library: **Albums** (the default, with covers), **Artists** and
**Songs**, a search box that only searches this music, and **Shuffle all** (plays everything
mixed, the queue stays as it is). Albums and artists open their pages, with Play, Play next
and Add to queue. When an album ends, more of the same music follows (some of the same
artist), never YouTube. Copies of a song in the music folder show once. These songs play
everywhere: this phone, browsers, Chromecasts, Sonos, MPD and the amplifier's rooms.

Settings → **Local music** switches *Music on the network* and *Music on this phone* on or off
(on this device). Likes and YouTube Music playlists aren't for local songs, so those buttons
don't show for them.

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
- Optional **local cache** so frequently played songs start faster (planned).
- Available both **as a server-side service** and **on-device on Android**, so a
  phone can host without a server.
- With a [linked YouTube account](accounts.md#linked-youtube-account) ✅: access
  to the user's playlists and likes.
- **YouTube's bot check** ✅ ("confirm you're not a bot", for a whole home network at once)
  comes rarely: song lookups carry a token that says they come from a real YouTube player,
  on a server and in the app. When it comes anyway, the room pauses on the song with
  *"YouTube is blocking requests from this network for now (bot check). Try again in 2 min."*,
  instead of skipping songs, and YTMP doesn't ask YouTube again before then.

Implementation details: [implementation/ytm-module.md](../implementation/ytm-module.md).
