# Playlist converter

> Status: **built**: the Spotify export reader, the matcher with its match memory, stored
> jobs, the YouTube Music writer (new or existing playlists, likes, follows), the API and the
> screens. Tried in a browser against a local server with a fake module (an import of two
> playlists, a song picked by hand put in its place), and on a Pixel with a test export and
> real YouTube Music; not yet with a real Spotify export. Not built yet: a phone notification,
> matches in the song graph's `song_sources`, other readers and writers.

Feature: [importing playlists](../features/importing-playlists.md).

## The shape

One converter for every input and output, in the **host**, so the server and the phone run
the same code:

```
reader  →  lists (one shared format)  →  matcher  →  writer
```

| Part | Where |
|---|---|
| Readers | `SpotifyExport.kt` (later Google Takeout's playlists, YTM and Spotify accounts) |
| Matcher | `ConvertMatcher.kt`: searches the target source with its normal module `search` (modules don't match; see [song matching](storage.md#song-matching)) |
| Jobs, the match memory, the worker, the YTM writer | `PlaylistConverter.kt` |
| API | `ConvertRoutes.kt` (`/api/me/convert/…`) |
| Wire types | `protocol/.../Convert.kt` |

When a later reader's source is the target's (YTM to YTMP playlists), the IDs can go straight
through without matching. A new reader is a function from the upload to `ConvertRead`; a new
writer a branch in `PlaylistConverter.write`.

## The shared format

A reader gives `ConvertList(name, kind, items)` and `ConvertItem(type, title, artists,
album, durationMs, isrc, sourceId, show)`, each field only when the source has it
(`protocol/.../Convert.kt`). `kind`: playlist, liked songs, saved episodes, followed shows;
`type`: song, episode, local file, show. The order of `items` is the list's order.

## Readers

### Spotify data export

The zip from *Download your data → Account data* (or one JSON from it, picked alone), sent
as it is and read with `ZipInputStream`. Only `Playlist1.json`, `Playlist2.json`, … and
`YourLibrary.json` are read (in that order); the rest (user data, streaming history) is
skipped and nothing of the upload is kept.

- `Playlist*.json`: `{ playlists: [{ name, items: [{ track, episode, localTrack, … }] }] }`.
  - `track: { trackName, artistName, albumName, trackUri }`: a song (only the first artist).
  - `episode`: a podcast episode, read as `episodeName`/`name`, `showName`/`show`,
    `episodeUri`/`uri` (still to check with a real export).
  - `localTrack: { uri }`: a file from the user's own disk,
    `spotify:local:<artist>:<album>:<title>:<seconds>`, form-encoded.
  - Anything else (an audiobook, a blank row) is counted as skipped.
- `YourLibrary.json`: `tracks: [{ artist, album, track, uri }]` is *Liked songs*, `episodes`
  *Saved episodes* and `shows` the followed podcasts. Saved albums, followed artists and hidden
  songs aren't imported (bulk saving and following is what YouTube's spam filters look for).

There's no length and no ISRC, so matching is on title, artist and album. An upload with none
of these files is refused with one message. Tests: `SpotifyExportTest` (a zip built in the
test).

To check with a real export: the episode and show fields, whether followed (not own)
playlists are in it.

## Matching

`ConvertMatcher.match`:

- Songs and local files: a search (`type=songs`) for "artist title", with Spotify's
  " - Remastered 2011"-style notes left out of the title. When no song scores as sure and
  *music videos* is on, a second search (`type=videos`).
- Episodes: `type=episodes` for "show title"; shows: `type=podcasts` for the name (a podcast
  is kept as a `Song` with its ID and `podcast` set, so all rows look alike).
- Each result is scored 0..1 (`score`): title 55%, artist 35%, album 10% (after
  `SongMatch.normalize`: no accents, brackets or "feat."); within 3 s of a known length adds
  a little, over 20 s off takes a lot; a different version (live, remix, acoustic, sped up, …)
  takes 0.3; a video a little.
- **Matched** from 0.85, **unsure** from 0.5 (with the best guess and up to 4 others), else
  **not found** (still with what the search gave).

Before any search, the **match memory** (`matches.json`): matched before on this host (by
anyone, on a server), by the item's source ID, else by its type, title, artist and album. A
pick made by hand is kept there too (also *leave it out*) and always wins over an automatic
match. The same song in several lists of a job is one entry, so it's looked up once; a song
waiting in another import is matched with it.

## Jobs

A job is kept as `job-<id>.json` in the converter's folder: owner, title, reader, settings,
its lists (name, kind, target, the entry key of each row, the target playlist's ID once made,
which rows are written, the song IDs in the target, rows still to put in their place, the last
error) and its entries (each song once: the item, state, chosen song, candidates, score, picked
by hand). Picks and writes are kept at once, looked-up songs at most every 30 s (a crash looks
those few up again). The uploaded file isn't kept; a read is kept in memory for an hour, until
the job starts.

| | Folder |
|---|---|
| Server | `imports/` next to the SQLite database (`imports` in the working folder with PostgreSQL) |
| Phone | `files/imports` in the app's storage |

**Owner**: on a server the account (`HostSources.personalId`, the same account `personalFor`
finds); on a phone `"device"`, its own sign-in. A job uses its owner's YouTube Music
(`personalOf`) when it writes, also with nobody looking.

**Status** (worked out each time, not stored): cancelled, paused, *matching* while songs are
pending or a list has something to write, *needs you* while unsure songs wait, else *done*.
Not-found songs don't hold it up.

Lists longer than 5,000 songs (a YouTube Music playlist's limit) are split into parts
"Name (2)", ….

## Background work

One worker (`PlaylistConverter.run`) for all jobs, oldest first:

- A list that's looked up whole and has something to write goes first, but never twice in a
  row while songs wait, so a long list of likes doesn't hold the rest up.
- Otherwise the next pending song, then a pause of 4 s (`GAP_MS`, LocalMatcher's pace).
  Radio learning keeps its own pace next to it.
- YouTube's bot check (`SourceBlocked`, or "bot"/"confirm"/429 in the message): every job
  waits 30 minutes (*"YouTube asked to confirm this isn't a bot: again at 14:20"*), another
  error 5 minutes. A write that fails (also *not signed in*) is tried again after 10 minutes,
  with its error shown.
- The server: always. The phone: while its host runs, and woken with the radio crawl's
  background runs (`crawlAwake`).

## Writing

- **New playlist**: `createPlaylist` with the first 50 songs, then `addToPlaylist` 50 at a
  time with 2 s between, in the list's order.
- **Existing playlist**: read whole first; only what isn't in it is added.
- Each song goes in **once** per playlist (YouTube refuses a whole part when one of its songs
  is in the playlist already; then they're added one by one).
- Songs picked later are added, then put in their place with `movePlaylistItem` (before the
  next song of the list that's in place), reading the playlist for the `itemId`s.
- **Likes** (`setRating`) and **follows** (`setPodcastSaved`): 4 per step, 15 s apart.
- Each row is marked written, so going on after a restart never adds a song twice.

## API (host)

Under `/api/me/` like the rest of your YouTube Music, so a page of a room on another host
reaches the host with your sign-in (`youtubeFrom` in the web app; the server's YouTube token
allows these paths, `AccountService.verifyYoutubeToken`).

| Method | Path | What |
|---|---|---|
| POST | `/api/me/convert/read?reader=spotify-export` | The file as the body → `ConvertPreview` (`readId`, the lists with name, kind, count; skipped rows) |
| POST | `/api/me/convert/jobs` | `ConvertStartRequest { readId, lists: [{ index, target }], settings }` → `ConvertJob` |
| GET | `/api/me/convert/jobs` | `ConvertJobList`, newest first |
| GET | `/api/me/convert/jobs/{id}` | `ConvertJob`: status, waiting and until when, time left, counts, lists |
| GET | `/api/me/convert/jobs/{id}/items?state=&cursor=` | `ConvertItemPage`: its songs, each once, 50 at a time, by state |
| PUT | `/api/me/convert/jobs/{id}/items/{key}` | `ConvertPick { song }`, `{ leftOut: true }` (its match becomes the first option) or `{ back: true }` (left out → what it was before, kept as `leftFrom`) |
| POST | `/api/me/convert/jobs/{id}/accept-unsure`, `/pause`, `/resume`, `/cancel` | → `ConvertJob` |
| DELETE | `/api/me/convert/jobs/{id}` | Off the list; its playlists stay |

Targets: `new_playlist` (with `title`), `existing_playlist` (with `playlistId`), `likes`,
`follow` (only for followed shows, and they only that). Refusals are 400 with a message (404 for
someone else's import).

## Web app

`ImportView.svelte`, a page of the room (`{ kind: 'import', jobId? }`), reached from
*Library → Import* when signed in to YouTube Music: *Your imports*, a new import (the how-to,
the file, *What to import* with a target per list, the settings) and an import's page (its
lists with *Open*, *Pause / Go on / Cancel*, *Accept all*, and *Checking songs* by filter: the
the song on Spotify, its match now, the candidates, a search of your own, *Leave it out*).
Listening to an option plays `/api/audio/{id}` in an `Audio` element of the page (this device
only, from 30 s in for songs over 1½ minutes), not in the room; holding the cover opens the
song menu (`hold`, place `search`), but not for a followed podcast. While an import goes, its page
asks every 3 s.

## Tests

- `SpotifyExportTest`: playlists, library, episodes, local files, skipped rows, wrong files.
- `ConvertMatcherTest`: scoring (remasters, accents, feat., covers, live and remix versions,
  lengths, videos, episodes, shows) and the songs-then-videos searches.
- `PlaylistConverterTest`: each song looked up once, the playlist in order, an unsure song
  picked later put in its place, the memory across imports and restarts, the bot check pause,
  an existing playlist, likes, per-person imports.
- `ApplicationTest`: the routes on a phone host, refused for a friend on the Wi-Fi.
