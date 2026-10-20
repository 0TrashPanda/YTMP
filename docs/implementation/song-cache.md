# Song cache

> Status: **planned, not built yet.**

Feature: [song cache](../features/sources.md#song-cache).

## Where it lives

In the **host** (`host/`), shared by the server and the phone, next to the audio proxy and
[downloads](downloads.md). Not in the module: the phone has no module process of its own,
and the host already sees every song that is played and serves the audio for speakers and
proxied players.

| | Folder | Default size | Set in |
|---|---|---|---|
| Server | `[cache] dir` in `ytmp.toml` (`YTMP_CACHE_DIR`); default `cache/` next to the database (a Docker volume) | 5 GB | `[cache] size = "5GB"` (`0` turns it off) |
| Phone | *Settings → Playback*: the app's own storage (default) or the SD card (the app's folder there, `getExternalFilesDirs`, no permission needed). Not the Music folder: these aren't the user's files | 1 GB | *Settings → Playback* |

Changing the folder moves what's cached (or starts empty when the old one is gone).

### Identifying a cached song

The cache is keyed by the **song ID** (`ytm:<video id>`), the same ID the song has
everywhere. A cached song isn't a different song or source: it stays a YouTube Music song
(likes, radio, lyrics, history, the song graph), and only its audio comes from the cache.
So nothing outside the audio path needs to know about the cache.

Each cached song is one file named after its ID, the audio as YouTube sent it (`.m4a`, no
tags needed), plus a row in the host's database: song ID, the song's metadata (title,
artists, album, length, as JSON) and its cover (saved as a file, for offline), size, when it
was saved, when it was last played and how often.

### Play counts

A small table counts plays per song ID for the last 90 days, also for songs that aren't
cached (rows older than that are dropped). Plays are counted when a song is played in a
room, not when its audio is served. It decides when a song is worth an extra download and
which songs stay.

## Filling it

- Only **music**: podcast episodes (`Song.podcast` set) are never cached.
- **Free copies, always**: the bytes the host streams anyway (speakers, proxied players)
  go into the cache as they pass. On the phone, its own player caches what it plays (Media3's
  cache data source writes while reading). A song that wasn't streamed from start to end (a
  skip, a seek) is kept only if it's worth an extra download (below), and then finished in
  the background; otherwise dropped.
- **Extra downloads, from the 2nd play in 30 days**: a song played where the host doesn't
  see the bytes (players on a direct YouTube link) is fetched by the host itself in the
  background, like a [download](downloads.md) (`AudioFiles`), one at a time, once the play
  count says so. One-off radio and autoplay songs cost no extra YouTube traffic.
- A song that is already cached isn't fetched again. A failed fetch leaves nothing behind
  (written to a temporary file, renamed when complete).
- The phone caches in rooms it hosts **and** in rooms on a server it listens to: there the
  app's own player (`PlaybackService`) is the one playing, so the phone fetches the song with
  its own YouTube lookup.

## Using it

- **The host's audio route** (`/api/audio/{songId}`) answers from the cache when it has the
  song (with `Range`, like local files), before asking YouTube. So speakers and proxied
  players use it without changes.
- **All listeners in a server room**: the playback target the host sends for a cached song
  points at the host's audio route instead of the YouTube link, for devices allowed to
  stream through the host (`mayProxyAudio`, so `proxy = "lan"` still applies). Other devices
  get the YouTube link as now.
- **No lookup**: for a cached song the host doesn't resolve a stream at all (no `stream`
  call to the module or yt-dlp). The exact length comes from the cache row.
- **The app in a server room**: `PlaybackService` checks the phone's cache before using the
  room's link, and plays the cached file (Media3 reads it directly).
- **Offline on the phone**: a solo room on the phone plays cached songs without internet.
  The Local tab gets a library **Cached on this phone** (a `LocalLibrary` over the cache
  rows, with the songs' saved metadata), so they can be picked without search.

## Downloaded songs

On the phone, songs [downloaded](downloads.md) to the Music folder show in the cache too. A
download carries its song ID in its tags (a freeform iTunes item `----:dev.trashpanda.ytmp:id`,
written by `M4aTags`), so the app finds them in the media store (`PhoneDownloads`) and knows
which song each one is. They:

- are listed with the cached songs, marked *Downloaded* (also under *Cached on this phone*),
- play from the Music folder instead of streaming (checked before the cache),
- don't count toward the cache size and are never removed by it (the user deletes them).

A song that is downloaded isn't also cached. Downloads made before the ID tag existed can be
matched by title and artist, or just stay plain local music.

On a server this doesn't apply: downloads go to the browser.

## Removing songs

When saving a song would take the cache over its size, songs are removed until it is back
under **90 %** of it (so it doesn't clean up on every song).

The order: lowest **keep score** first, where

    keep score = last played (as a time) + 7 days × (plays in the last 90 days − 1)

So it's mostly *least recently played first*, but each extra recent play counts as a week
more recent: a song played ten times last month outlives one played once yesterday. A song
playing now (or next in a room) is never removed, and downloads aren't in the cache to remove.

*Clear cache* removes everything that isn't playing. Changing the size to something smaller
removes songs right away by the same rule.

## Open points

- The server's Docker volume: the default compose file needs a `cache` volume (or the cache
  goes in the existing data volume).
- The 2-plays-in-30-days rule and the keep score are first guesses: tune them once there's
  real use (like the [song graph weights](../open-questions.md)).
