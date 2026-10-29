# Playlist converter

> Status: **planned**. First step: the Spotify export reader, the matcher, the YTM playlist
> writer, the stored jobs and the screens. Readers and writers after that are separate steps.

Feature: [importing playlists](../features/importing-playlists.md).

## The shape

One converter for every input and output, in the **host** (`host/.../convert/`), so the
server and the phone run the same code:

```
reader  →  lists (one shared format)  →  matcher  →  writer
```

- A **reader** turns an input into lists. File readers: the Spotify export (first), later
  Google Takeout's playlists. Account readers: YTM (`/me/playlists`, `/me/playlists/{id}`),
  later Spotify. A reader first answers a **preview** (the lists and how many items each has),
  then the items.
- The **matcher** turns an item into a song of the target source, using that source's normal
  module `search` (modules don't match; see [song matching](storage.md#song-matching)).
  When the reader's source is the target's (YTM to YTMP playlists), the IDs go straight
  through, without matching.
- A **writer** puts the matched songs somewhere: a YTM playlist (first), later a YTMP
  playlist. It says what it can do (make a new playlist, add to an existing one, put a song in
  its place).

New readers and writers are a class each; the jobs, the matcher and the screens stay.

## The shared format

```kotlin
ConvertList(
    name: String, description: String?,
    kind: ConvertListKind,              // PLAYLIST, LIKED_SONGS, SAVED_EPISODES, FOLLOWED_SHOWS
    items: List<ConvertItem>,
)

ConvertItem(
    type: ConvertItemType,              // SONG, EPISODE, LOCAL_FILE, SHOW
    title: String, artists: List<String>, album: String?,
    durationMs: Long?, isrc: String?,   // each only when the source has it
    sourceId: String?,                  // "spotify:track:…", a YTM video ID, …
    show: String?,                      // an episode's podcast
    addedAt: Instant?,
)
```

The order of `items` is the playlist's order.

## Readers

### Spotify data export

The zip from *Download your data → Account data*, sent as it is (like
`POST /api/cache/takeout`) and read streaming. In its folder `Spotify Account Data/` only
these are read; the rest (user data, streaming history, inferences) is skipped and nothing of
the zip is kept:

- `Playlist1.json`, `Playlist2.json`, …: `{ playlists: [{ name, lastModifiedDate, description,
  items: [{ track, episode, localTrack, addedDate }] }] }`. One of the three is set per row:
  - `track: { trackName, artistName, albumName, trackUri }`: a song. Only the first artist.
  - `episode`: a podcast episode (its fields still to check with a real export, probably
    `episodeName`, `showName`, `episodeUri`).
  - `localTrack: { uri }`: a file added from the user's own disk,
    `spotify:local:<artist>:<album>:<title>:<seconds>`, form-encoded; still a song, often one
    YTM has.
- `YourLibrary.json`: `{ tracks: [{ artist, album, track, uri }], albums, artists, shows,
  episodes, bannedTracks }`. `tracks` is *Liked songs*; `shows` and `episodes` the podcasts.
  Saved albums, followed artists and hidden songs aren't imported (bulk saving and following
  is what YouTube's spam filters look for).

There's no length and no ISRC, so matching is on title, artist and album. A zip without any of
these files is refused with one message (the wrong export, or not unpacked by Spotify yet).

To check with a real export: the episode fields, whether followed (not own) playlists are in
it, and whether `YourLibrary.json` has more fields than the above. A test zip with these fields
is in the host's tests.

## Matching

`SongMatcher` (host), for each item, in order:

1. **Match memory** (`song_matches`): matched before, by anyone on this host? By `sourceId`
   when there is one, else by the [match key](storage.md#song-matching) (title and main
   artist, normalised as `SongMatch` does) plus the album. A pick made by hand always wins.
2. **ISRC**, when the item has one and the target module has `isrc_lookup`.
3. **Search** `artist + title` (episodes: `type=episodes` with the show; shows:
   `type=podcasts`) and score each result:
   - title and artist, after normalising
   - album, when known
   - length, when both are known (within ±3 s scores high)
   - a song (the album version) before a music video; a video only when the setting allows it
4. Above the threshold: **matched**. Just below: **unsure**, with the best ~5 candidates kept.
   Otherwise **not found**, still with what the search gave. The thresholds are set against a
   real export.

Every match (also by hand) goes into `song_matches` and into `song_sources`, so the
[song graph](storage.md#song-graph) knows the Spotify song and the YTM song are one.

The same item in several lists (or twice in one) is matched once per job.

## Jobs

A conversion is a stored **job**, so it survives the app closing and the server restarting:

- **Job**: owner (the account, or the phone's owner), reader and target kind, settings, status:
  `MATCHING`, `PAUSED` (by hand, or by the bot check until a time), `NEEDS_YOU` (only unsure or
  not-found songs left), `DONE`, `CANCELLED`, `FAILED` (with why).
- **List**: its place in the job, name, kind, the target (`new` with a title, or an existing
  playlist's ID), the target playlist's ID once made, and its state (`WAITING`, `WRITTEN`).
- **Item**: its place, the `ConvertItem`, its state (`PENDING`, `MATCHED`, `UNSURE`,
  `NOT_FOUND`, `LEFT_OUT`), the chosen song, the candidates and score, whether it was picked by
  hand, and whether it's written.

`ConvertStore` is the interface. The server: tables `conversions`, `conversion_lists`,
`conversion_items` and `song_matches` (new steps in `Database.kt`'s migration list). The phone:
the same tables in its own SQLite next to `PhoneRoomStore`. The parsed lists are stored with
the job; the uploaded file is not.

A job runs on the host where it's started, with that host's YouTube Music sign-in: started in
the app, the phone's own sign-in; on a server, the account's. On the phone there's one store
and one match memory per phone.

## Background work

`ConvertWorker` (host): one item at a time across all jobs, oldest job first, with a pause
between searches (a constant to tune, starting cautious). It shares one YouTube budget with
[learning radios](../features/history-and-recommendations.md#learning-from-youtube-music),
so the two never search at the same time.

- On the bot check (the module's existing error) every job goes to `PAUSED` for the same wait
  the rooms use, then on by itself.
- The server: always. The phone: while the app is open or playing, and with *Also overnight*
  like learning radios (the same background window, charging on Wi-Fi).
- Restarting picks up the first `PENDING` item; an item being looked up when it stopped is
  looked up again.

## Writing

As soon as every item of a list is past `PENDING`, the writer writes it:

- **New YTM playlist**: `POST /me/playlists` with the title and the first part of the songs,
  then `POST /me/playlists/{id}/songs` in parts of 50 with a pause between, in the list's order.
- **Existing playlist**: read it (`/me/playlists/{id}`) and add only what isn't in it.
- Items picked later (unsure, not found) are added and put in their place with
  `POST /me/playlists/{id}/move` (`itemId` from `PlaylistPage.itemIds`).
- *Liked songs* into a playlist, or as likes (`PUT /me/likes/{songId}`, a few a minute);
  followed shows with `PUT /me/podcasts/{id}/saved`, also slowly.
- Each item is marked written, so running it again or going on after a crash never adds a song
  twice. A target playlist that was deleted meanwhile makes the list `FAILED`, with *Make it
  again*.

## API (host)

In `ConvertRoutes` (with `PersonalRoutes`: your own imports only):

| Method | Path | What |
|---|---|---|
| POST | `/api/convert/read?reader=spotify-export` | The file as the body → `ConvertPreview` (`readId`, lists with name, kind, item count). The parsed lists are kept in memory for an hour |
| POST | `/api/convert/jobs` | `{ readId, lists: [{ index, target }], settings }` → the job |
| GET | `/api/convert/jobs` | Your jobs with their counts |
| GET | `/api/convert/jobs/{id}` | One job, its lists and counts, the time left |
| GET | `/api/convert/jobs/{id}/items?state=&cursor=` | Its items, in parts, by state (for *Checking songs*) |
| PUT | `/api/convert/jobs/{id}/items/{itemId}` | Pick: `{ song }` (a candidate or from your own search) or `{ leftOut: true }` |
| POST | `/api/convert/jobs/{id}/accept-unsure` | Accept every unsure item as it is |
| POST | `/api/convert/jobs/{id}/pause`, `/resume` | |
| DELETE | `/api/convert/jobs/{id}` | Cancel; the playlists made stay |

Searching by hand uses the normal search. While an import screen is open it asks for the job
every few seconds; a push over the WebSocket can come later. The types are in `protocol/`
(`ConvertPreview`, `ConvertJob`, `ConvertJobList`, `ConvertItemRow`, `ConvertSettings`, …) and
generated for the web app.

## Web app

`frontend/src/routes/import/`: the source and file, *What to import*, the settings, *Your
imports* and *Checking songs*, the same on the phone and in a browser. Reached from *Library*
and *Settings*. The phone's notification comes from the app (`android/`), when a job becomes
`NEEDS_YOU` or `DONE`.

## Tests

- The Spotify reader on a test zip (songs, episodes, local files, a blank row, a zip without
  the files).
- The matcher's scoring on a set of real-looking cases (remasters, "feat.", live versions,
  videos only), with a fake search.
- Jobs: going on after a stop, the bot check pause, writing in order and never twice, a pick by
  hand put in its place, the match memory used across jobs.
