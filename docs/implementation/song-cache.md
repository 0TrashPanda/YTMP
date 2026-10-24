# Song cache

> Status: **built** on the server and the phone (`SongCache` in `host/`). Verified on a Galaxy
> A20e (Android 11) with a local server and the real module: kept as the phone plays (its own
> solo room and a server room, from YouTube's link and from the server's), played again from the
> phone without a lookup, offline in a solo room with *Cached on this phone*, a download found by
> its ID tag and played from the Music folder, the server's fetch from the 2nd play and its link
> after, *Clear cache*, the size setting. Not verified: moving to an SD card (the A20e has none),
> Chromecast with a cached song, `proxy = "lan"` redirects outside the network (unit-tested).

Feature: [song cache](../features/sources.md#song-cache).

## Where it lives

In the **host** (`host/.../SongCache.kt`), shared by the server and the phone, in front of
the YouTube Music source and the audio route, next to [downloads](downloads.md). Not in the
module: the phone has no module process of its own, and the host already sees every song that
is played and serves the audio for speakers and proxied players.

| | Folder | Default size | Set in |
|---|---|---|---|
| Server | `[cache] dir` in `ytmp.toml` (`YTMP_CACHE_DIR`); default `cache/` next to the SQLite database, so in Docker the existing data volume | 5 GB | `[cache] size = "5GB"` (`YTMP_CACHE_SIZE`; `"0"` turns it off), or the admin page |
| Phone | *Settings → Playback*: the app's own storage (`files/song-cache`, default) or its folder on the SD card (`getExternalFilesDirs`, no permission needed). Not the Music folder: these aren't the user's files | 1 GB | *Settings → Playback* (prefs `ytmp.cache`) |

Changing the place moves what's cached, in the background (the settings show *Moving…*);
when the SD card is gone the phone uses its own storage and starts empty. The phone's cache is
left out of Android backups (`backup_rules.xml`, `data_extraction_rules.xml`).

### Identifying a cached song

The cache is keyed by the **song ID** (`ytm:<video id>`), the same ID the song has
everywhere. A cached song isn't a different song or source: it stays a YouTube Music song
(likes, radio, lyrics, history, the song graph), and only its audio comes from the cache.
So nothing outside the audio path needs to know about the cache.

Each cached song is one file named after its ID (`ytm_<video id>.m4a`: no `:`, which SD cards
refuse), the audio as YouTube sent it, and its cover (`ytm_<video id>.jpg`, 544 px, for
offline). What belongs to them is in `index.json` in the same folder: the song (title,
artists, album, length), its file, type and size, when it was saved and last played. The
index is in the folder rather than the host's database, so moving the folder moves everything,
and the server and the phone keep it the same way. The cache only ever deletes files it made
(`ytm_…`), whatever else is in the folder.

### Play counts

The index also keeps when each song was played in the last 90 days, also songs that aren't
cached. A song counts as played when a room starts it (`RoomHooks.onSongStarted`); when it's
skipped within 30 s the play is taken back (`FinishedPlay`, like YouTube's own count). The
phone's player counts songs it plays in rooms elsewhere itself. The counts decide when a song
is worth an extra download and which songs stay.

## Filling it

- Only **music**: podcast episodes (`Song.podcast` set), local music and "songs" over an hour
  (mixes, whole concerts) are never cached.
- **Free copies, always**:
  - **The host's audio route** (speakers, proxied players): a song a room started (so the
    cache knows what it is) is fetched **once** with `AudioFiles`, written to a file, and every
    request for it is answered from that file as it comes in, any range, several at once
    (`Fill`). When it's all in, it's kept. When nobody listens any more before that (a skip),
    it goes on only if the song is worth an extra download (below).
  - **The phone's own player** keeps what it reads: Media3's `TeeDataSource` hands the bytes
    to the cache (`CacheSink` → `SongCache.Copy`), in pieces at whatever place they are
    (seeks, a restart at the beginning). Once the pieces cover the whole file it's kept. When
    the player moves on to another song with pieces missing, they're dropped, and the song is
    fetched whole in the background if it's worth it.
- **Extra downloads, from the 2nd play in 30 days**: when a room starts a song that was played
  before in the last 30 days and isn't cached, the host fetches it in the background 30 s later
  (it may come in for free meanwhile, or be skipped), like a [download](downloads.md)
  (`AudioFiles`), one at a time. One-off radio and autoplay songs cost no extra YouTube traffic.
- A song that is already cached isn't fetched again. A fetch that fails leaves nothing behind
  (written to `….part`, renamed when complete).
- The phone caches in rooms it hosts **and** in rooms on a server it listens to: there the
  app's own player (`PlaybackService`) is the one playing, and fetches go through the phone's
  own YouTube lookup.

## From your YouTube Music

Phone only, with the owner's account (`PhoneYoutubeAccount`). `CachePrefill` (`host/`) looks at
the account when a setting changes, at app start and every 6 hours (after an error 30 minutes):

- **Recent likes** (pref `keepLiked`, 0–5000; `SongCacheSettings.keepLiked`): the first X songs
  of Liked music (`LM`, newest liked first), read in parts (`PhoneYoutubeAccount.recentLikes`).
- **History** (pref `fromHistory`): `YtmCore.history()` (ytmusicapi `get_history`), each song with
  about how many days ago (`parse.days_ago` from the day group's title: Today, Yesterday, a
  weekday, This week, Last week, a month; another language: unknown, counted as 31 days ago). Each
  becomes a play at noon that day in the index's play counts (`SongCache.importPlays`), unless
  the song already has a play that day: plays here go into that history too
  ([`addToHistory`](../features/accounts.md)), and the history is read again. Every song in it
  is fetched, newest first: YouTube Music lists a song once (its last play) and only about 200,
  so it can't tell what was played often (a [Takeout import](#from-a-google-takeout-history)
  can). The imported plays count in the keep score and toward [worth an extra download](#filling-it).

Both go through `SongCache.fetch` (one song at a time, together with the background fetches),
20 s apart, behind `fetchGate` (*Keep songs in the background*). They're ordinary entries: no
pinning. To not push songs out for songs nobody played here, it only fills free room
(`SongCache.hasRoom`: a song of the cache's average size, 4 MB when it's empty, still fits under
90 %), else it stops the round and says `full`. The status has `keepLiked`, `likedOf`,
`likedCached` (of the liked songs that can be cached, how many are here, downloads included),
`fromHistory` and `fromYoutubeWaiting` (`Wi-Fi`, `full`, `signed out`, `error`).

## From a Google Takeout history

Phone only; no sign-in needed. *Choose Takeout export* in the settings sends the file
(`<input type="file">`; in the app `MainActivity` answers the WebView's file chooser with
Android's document picker) to `POST /api/cache/takeout` as is. `Takeout.read` (`host/`,
`TakeoutHistory.kt`) takes the zip (any `watch-history.html` or `.json` in it, streamed) or one of
those files, and keeps the plays with the product *YouTube Music* or a `music.youtube.com` link:

- **HTML** (Takeout's default, 50 MB and more): read a cell (`outer-cell`) at a time. The
  first link is the song (its video ID), the second its channel (`… - Topic` is the artist),
  the last line the date, written in the account's language: `TakeoutDates` reads English,
  Dutch, German, French, Spanish and Italian month names, US order with AM/PM, and numeric
  dates, at the phone's time zone (the zone name is ignored). Entries without a readable date
  are left out; when none can be read, the error asks for the JSON export.
- **JSON**: `titleUrl`, `title` (without `Watched `), `subtitles[0].name`, `time` (ISO).

A zip that ends early (`EOFException`/`ZipException` while reading), or a file that isn't a zip's
start and isn't text (the back part of a zip: a download saved in two files, seen from Discord),
is refused with one message, `Takeout.INCOMPLETE`: download it again. Nothing is kept from it.
The settings show why an export was refused under its button until the next one is chosen.

`TakeoutHistory` keeps the result in `takeout-history.json` in the app's files (per song: ID,
title, artist, the play times; about 1.5 MB for 31,000 plays) and replaces it on the next
import; `DELETE /api/cache/takeout` removes it and switches the setting off.
`TakeoutHistory.pick(days, minPlays, now)` gives the songs with at least `minPlays` plays in the
last `days` days (0: all), by those plays, then the last play. `POST /api/cache/takeout/preview`
(`TakeoutPreviewRequest` → `TakeoutPreview`) answers that count for the settings while you
choose, with how many are here and about how much room they take (`SongCache.usualSongBytes`:
the cached songs' average, 4 MB when empty).

`CachePrefill` does the rest in its round, after likes and history (prefs `fromTakeout`,
`takeoutDays` 0–3650 (365), `takeoutMinPlays` 1–1000 (5)): every Takeout play goes into
`SongCache.importPlays` (the last 90 days count, once a day), and each picked song that's wanted
is looked up first (`songInfo`: `ytm.song`, for its title, length and cover; the Takeout entry
has only an ID and title), then fetched like the others: 20 s apart, only into free room. A
song that turns out not to be kept (an episode, too long) or can't be looked up is left out
until the app restarts; three failed lookups in a row stop the round (YouTube blocks it, likely:
`error`). The status has `takeout` (`TakeoutSummary`), `fromTakeout`, `takeoutDays`,
`takeoutMinPlays`, `takeoutOf` and `takeoutCached`.

## Using it

- **The host's audio route** (`/api/audio/{songId}`) answers from the cache when it has the
  song (with `Range`), before asking YouTube. So speakers and proxied players use it without
  changes. A download (`POST /api/download`) of a cached song is made from its file too.
- **No lookup**: for a cached song the room's `StreamResolver` (`SongCache.streams`) answers
  the host's own path, `/api/audio/ytm%3A…`, without a `stream` call to the module or yt-dlp.
  The exact length comes from the index.
- **All listeners in a server room**: that path is the song's stream link
  (`NowPlaying.streamUrl` may be a path on the room's host). Browsers play it as it is; the
  app adds the room's host. A device that may not stream through the host (`[audio] proxy =
  "lan"`, outside the network) gets a redirect to YouTube's link instead, looked up then (and
  remembered half an hour), so it plays as it would without the cache. Chromecasts take the
  path as their proxy link.
- **The app** (`PlaybackService`) plays a song from the phone first: a download, then the
  cached file (`SongCache.localUri`, Media3 reads it directly). Only if that fails does it
  use the room's link.
- **Offline on the phone**: a solo room on the phone plays cached songs without internet (no
  lookup, the file on the phone). The Local tab has a library **Cached on this phone**
  (`SongCache.library`, kind `cache`): the cached and downloaded songs, keeping their own IDs
  (`LocalTrack.song`), with albums and artists of their own that work offline, and folders
  *Downloaded* and *Cached*. It shows once it has a song.

## Downloaded songs

On the phone, songs [downloaded](downloads.md) to the Music folder show in the cache too. A
download carries its song ID in its tags (a freeform iTunes item `----:dev.trashpanda.ytmp:id`,
written by `M4aTags`), so the app finds them in the media store (`PhoneDownloads` reads the
ID from the start of each of its files in `Music/YTMP`, once per file) and knows which song
each one is. They:

- are listed with the cached songs, in the folder *Downloaded* of *Cached on this phone*,
  with what the app knew of the song when it downloaded it (kept in the index),
- play from the Music folder instead of streaming (checked before the cache), also through
  the phone's audio route for speakers and friends,
- don't count toward the cache size and are never removed by it (the user deletes them).

A song that is downloaded isn't also cached: its cached copy goes when the download is
saved. Downloads made before the ID tag existed just stay plain local music.

On a server this doesn't apply: downloads go to the browser.

## Songs from this phone

With the setting on (`ytmp.cache` pref `localSongs`; `SongCacheSettings.localSongs`), `LocalMatcher`
(`host/`) goes through the phone's music (`PhoneLibrary.tracks()`, without `Music/YTMP`, under
an hour, with a title and artist) and searches YouTube Music songs for "artist title", one
search every 4 s per worker (`LocalMatcher.parallel`, 1–8 workers, the setting *Lookups at once*, pref `localParallel`). Before that, a YouTube video ID in the file's name (`[id]` at the end, or `-id`; `LocalMatcher.idInName`) or in a YouTube link in the first 128 KB of the file (a `PURL`/comment tag in any encoding; `idInBytes`) is looked up as a song (`ytm.song`); if its length is within 10 s of the file's it is the match, else the search below runs. A search result is a match when its normalised title (no accents, brackets or
punctuation) is the same, one of its artists is in the file's artist or album artist, and the
length differs by at most 3 s. The answer, also "no match", is kept per file in the cache's
`index.json` (`local`: media store ID → `LocalMatch(title|artist|length, song?)`), so it moves with
the cache folder and survives *Clear cache*; a changed file is looked up again, a removed one forgotten.
A failed search (bot check, no internet) stops the round and tries again in 10 minutes; it looks at the media
store every 5 minutes.

`PhoneMatchedSongs` serves the matches as `DownloadedSongs` (`local = true`), combined with
`PhoneDownloads` in `CombinedDownloads`, so they play and are listed like downloads, in the folder
*On this phone*. The status says how many (`local`) and how many are still to look up (`checking`).

Each check keeps why a file has no match (`LocalMatch.why`) and whether the owner decided it
(`manual`, from `POST /api/cache/local/link`: a link or ID, looked up without the length check;
empty: skipped). `GET /api/cache/local/unmatched` lists the files without a match,
`POST …/recheck` forgets the automatic "no match" answers and `POST …/forget` all automatic
answers; both wake the matcher at once. `DataRules` (android) holds the Wi-Fi/charging settings
(prefs `ytmp.cache`): the matcher's `hold` pauses lookups, `SongCache.fetchGate` the background
fetches, `PhoneDownloads`' gate the downloads (the page hears `onDownloadWaiting`). Wi-Fi means a
network without `NET_CAPABILITY_NOT_METERED`.

## Removing songs

When saving a song would take the cache over its size, songs are removed until it is back
under **90 %** of it (so it doesn't clean up on every song).

The order: lowest **keep score** first, where

    keep score = last played (as a time) + 7 days × (plays in the last 90 days − 1)

So it's mostly *least recently played first*, but each extra recent play counts as a week
more recent: a song played ten times last month outlives one played once yesterday. Songs
playing now or next in a room, the song the phone's own player plays, and songs coming in are
never removed, and downloads aren't in the cache to remove.

*Clear cache* removes everything that isn't playing. A smaller size removes songs right away
by the same rule; *Off* (`0`) empties it.

## Settings

- **Phone**: *Settings → Playback → Song cache*, on the phone's own pages (the room list and
  rooms on the phone): how full it is, *Size* (Off, 500 MB, 1 GB, 2 GB, 5 GB, or typed in GB), *Kept on*
  (this phone or the SD card, with their free space), *Clear cache*. `GET/PUT /api/cache`,
  `POST /api/cache/clear`, only from the phone itself (`HostOptions.cacheSettings`).
- **Server**: *Settings → Server admin → Settings* for the server admin: how full it is,
  *Clear cache* (`/api/admin/cache`) and its size (`/api/admin/settings`, over `ytmp.toml`);
  its folder in `ytmp.toml`.

## Open points

- The 2-plays-in-30-days rule and the keep score are first guesses: tune them once there's
  real use (like the [song graph weights](../open-questions.md)).
