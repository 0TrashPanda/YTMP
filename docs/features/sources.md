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
| Local music | ✅ A Raspberry Pi's music folder (with MPD) and the music on the hosting phone, in the [Local tab](#local-music-). Songs downloaded in the app are in the phone's music (*Music/YTMP*). Other folders: planned. |
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
  once whether YTMP may read them. Music Android's own scan doesn't know (an SD card, a folder
  with a `.nomedia` file) comes in through **Music folders** (*Settings → Local music → Add a
  folder*): pick a folder with Android's folder picker (no permission needed) and its songs show
  with the rest of the phone's music. *Use Android's music scan* can be switched off to use only
  the picked folders. A song that is in both shows once.

The tab is like YouTube Music's library: **Albums** (the default, with covers), **Artists**,
**Songs** (300 at a time, with *Load more*; see [songs per page](ui.md)), **Genres** and
**Folders** (when the top holds only one folder, like the phone's *Music*, the list starts inside it), a search box that only searches this music (folders at any depth too), and
**Shuffle all** (plays everything mixed, the queue stays as it is). Albums, artists, genres and
folders open their pages, with Play, Play next and Add to queue (a folder: also what's in the
folders inside it); a folder page lists its folders, then its songs.

The tab **remembers where you were**: the music, the chip, the search and how far you
scrolled. Going back (from an album, say) always returns there; with *Remember where you were*
on (the default) the tab also opens there, even after closing the app. Off: it opens at the
start. When an album ends, more of the same music follows (some of the same
artist), never YouTube. Copies of a song in the music folder show once. These songs play
everywhere: this phone, browsers, Chromecasts, Sonos, MPD and the amplifier's rooms.

Settings → **Local music** switches *Music on the network*, *Music on this phone* and
*Remember where you were* on or off (on this device). Likes and YouTube Music playlists aren't for local songs, so those buttons
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
- A [song cache](#song-cache) ✅ on the server and on the phone.
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

## Song cache

> Built. Implementation: [song-cache](../implementation/song-cache.md). Tried on a phone, offline
> too, and a test server; not yet with an SD card.

Songs you play are kept, so they start at once next time, need no YouTube lookup, and play
without internet on the phone. It works by itself: there is nothing to pick.

- **Songs are saved when that costs nothing extra**: a song the phone plays itself, or that
  the server or phone already streams (to speakers, or to players that stream through it),
  is kept as it passes.
- **Songs played elsewhere are fetched only once they're played again**: from the 2nd play
  in 30 days. So one-off radio and autoplay songs don't cost an extra download each (and
  extra YouTube traffic), but your favourites end up cached.
- **Music only**: podcast episodes are never cached (they're big and mostly heard once), and
  neither are "songs" over an hour (mixes, whole concerts).
- A cached song **stays the same YouTube Music song** (likes, radio, lyrics and history work
  as before); only its audio comes from the cache.
- **A maximum size**, on by default: **5 GB on a server** (`ytmp.toml`), **1 GB on a phone**
  (*Settings → Playback*; about 250 songs). Both show how full the cache is and have
  **Clear cache** (on a server for the admin, under *Settings → Server admin*; see
  [server admin](server-admin.md)).
- **Where it's kept is a setting**: a folder of your choice on a server (`ytmp.toml`), the
  phone's own storage or its SD card on a phone.
- **When the cache is almost full**, songs are removed to make room: first the songs that
  haven't been played for the longest time, and of those the ones played least. A song played
  often stays longer than one played once, even if that one was a bit more recent.
- **Downloads show in the cache** (on the phone): a song you [download](ui.md#song-menu) is
  listed with the cached songs (in the folder *Downloaded*), and plays from the Music folder
  instead of streaming. It doesn't count toward the cache's size and is never removed: it's yours.
- **Songs from this phone** (a setting in *Settings → Playback → Song cache*, off by default; needs
  *Music on this phone*): the music stored on the phone that is also on YouTube Music is
  listed in its own folder, *On this phone*, and plays as that YouTube Music song (likes, radio
  and history work) from the file, also offline. A file counts when a YouTube Music search finds a
  song with the same title, an artist in common and the same length (within 3 seconds); anything
  less sure is left out. A file that says which YouTube video it is, in its name (`Song [dQw4w9WgXcQ].mp3`,
  `Song-dQw4w9WgXcQ.mp3`) or in a tag with a YouTube link (what yt-dlp writes), is matched by that
  instead, as long as its length agrees (within 10 seconds). Files with no artist tag are matched by
  such an ID too.
- **Songs from this phone, in the settings:** *Lookups at once* (1, 2, 4 or 8), *Look up* (any time,
  only on Wi-Fi, only while charging, or both), a progress bar, **Check again** (looks again at the
  songs without a match), **Forget matches** and **Not found**: the songs without a match and why,
  where you can paste a YouTube Music link or video ID to say which song it is, or skip it. What
  you chose yourself stays when matches are forgotten.
- **Mobile data:** songs kept in the background (from their 2nd play) wait for Wi-Fi by default
  (*Keep songs in the background*); *Downloads* can wait for Wi-Fi too (the page says
  "downloads when you're on Wi-Fi"); the app's update dialog warns when you're on mobile data. *Settings → Playback → Downloads* lists
  the phone's downloads that are going, waiting (for Wi-Fi) or failed, with **Download now** (this
  once, on mobile data), **Retry** and **Cancel**. The waiting ones carry on after the app is closed. Each file is looked up once and slowly (so YouTube doesn't block the
  network), and the answer is kept. *Lookups at once* (1, 2, 4 or 8; 1 by default) makes it faster, with more risk of YouTube's bot check. The setting shows how far it is ("120 of 800 phone songs looked up · 95 found on YouTube Music"). They don't count toward the cache's size and are never removed.
- Only songs from online sources (YouTube Music, later YouTube) are cached. Local music and
  media servers are already files.

Where the cache is used:

| | Phone | Server |
|---|---|---|
| Its own playing | Rooms on the phone, and **rooms on a server** the phone listens to: cached songs play from the phone itself | — |
| Speakers | Speakers the phone plays on (Chromecast, Sonos, MPD, zones) get cached songs from the phone | The server's speakers get cached songs from the server |
| Others in the room | Friends' browsers and phones in a room on the phone get cached songs from the phone | **All listeners** in a server room get cached songs from the server instead of from YouTube (with `proxy = "lan"` only devices on the server's network; others stream from YouTube as now) |
| No YouTube lookup | A cached song isn't looked up on YouTube | A cached song isn't looked up on YouTube (fewer lookups, less risk of the [bot check](../implementation/ytm-module.md)) |
| Offline | Without internet, cached songs still play in a solo room on the phone; the Local tab shows **Cached on this phone** (cached and downloaded songs) to pick them from | — |

