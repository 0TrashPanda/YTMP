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
| Server | `cache/` next to the database (a Docker volume) | 5 GB | `[cache] size = "5GB"` in `ytmp.toml` (`0` turns it off) |
| Phone | the app's own storage (not the Music folder: these aren't the user's files) | 1 GB | *Settings → Playback* |

Each cached song is one file, the audio as YouTube sent it (`.m4a`, no tags needed), plus a
row in the host's database: song ID, size, when it was saved, when it was last played and how
often (plays counted when a song is played, not when it is served).

## Filling it

- When the room plays a song from an online source, the host saves its audio **once**,
  complete. It doesn't duplicate work it already does:
  - **Through the host** (speakers, proxied players): the bytes the host streams go into the
    cache as they pass. A song that wasn't streamed from start to end (a skip, a seek) is
    finished in the background, or dropped.
  - **Played elsewhere** (players on a direct YouTube link): the host fetches the whole file
    itself in the background, like a [download](downloads.md) (`AudioFiles`), one at a time.
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

## Removing songs

When saving a song would take the cache over its size, songs are removed until it is back
under **90 %** of it (so it doesn't clean up on every song).

The order: lowest **keep score** first, where

    keep score = last played (as a time) + 7 days × (plays in the last 90 days − 1)

So it's mostly *least recently played first*, but each extra recent play counts as a week
more recent: a song played ten times last month outlives one played once yesterday. A song
playing now (or next in a room) is never removed. Podcast episodes follow the same rule;
being big, they free a lot at once.

*Clear cache* removes everything that isn't playing. Changing the size to something smaller
removes songs right away by the same rule.

## Open points

- The server's Docker volume: the default compose file needs a `cache` volume (or the cache
  goes in the existing data volume).
- How the phone's cache and the Music-folder [downloads](downloads.md) relate: a downloaded
  song could play from the Music folder too, without being cached twice.
