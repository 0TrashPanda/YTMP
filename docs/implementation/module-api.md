# Module HTTP API

> Status: **draft for review**.

## What is this?

This is the **contract that every source module follows**: the list of things the host can
ask a source ("search for X", "give me a playable stream for song Y", "give me a radio
from song Y", …). It is **not** the YTM module itself. The YTM module is the **first
module that implements it**. A future SoundCloud module, for example, would implement
the same contract.

The same operations reach a module in three ways (see [modules](modules.md)):

| Module runs… | How the host calls it | Example |
|--------------|-----------------------|---------|
| as a **separate program** | **HTTP**, as described on this page | YTM service on the server |
| **inside the app**, in Kotlin | normal Kotlin function calls (a `SourceModule` interface with the same operations) | local files, Plex |
| **inside the app**, in Python | Chaquopy function calls | YTM on Android |

This page describes the HTTP form, because that's the one that needs an exact
definition: the host and the module are separate programs, possibly in different
languages.

### Example: from search to playback

1. Someone types *daft punk* in the search bar. The frontend asks the host.
2. The host asks every enabled source: `GET /search?q=daft punk` on the YTM module, and
   the same operation on the local files and Plex modules. It merges the results.
3. Someone taps *One More Time* (`ytm:FGBhQbmPwH8`). It goes into the queue.
4. When the song is about to play, the host asks `GET /songs/ytm:FGBhQbmPwH8/stream`
   and gets a playable URL, which it sends to the listening clients.
5. A client that can't use that URL gets the audio through the host instead, which uses
   `GET /songs/ytm:FGBhQbmPwH8/audio`.

### Not every module supports everything

`GET /info` returns a list of **capabilities**. A module only implements what it can.
For example, a module for internet radio stations has no `lyrics`, and only a module with
user accounts has `library`. The host hides features a module doesn't support.

### What modules do *not* do

- **Matching** songs across sources ("which YTM song is this local file?") is done by the
  **host**, using the module's `search`. See [storage](storage.md#song-matching).
- **Downloads** (a finished, tagged file) are made by the **host**, using the module's
  `audio`. See [downloads](downloads.md).

That way this logic is written once, and works the same for every source.

### What is YTM-specific?

The contract is generic. A few parts only make sense for YTM/YT, and other modules
leave them out:

- `X-YTMP-Credentials` (the linked YouTube account), and the `/me/...` library endpoints
- `/admin/update` (updating yt-dlp)
- `loudnessDb` from YouTube, used for loudness normalisation

## Code layout of the Python YTM module

```
ytmp_ytm/            # Python package: all YTM/YT logic (ytmusicapi + yt-dlp)
  core.py            # plain functions: search(), song(), stream(), radio(), …
  http.py            # thin HTTP layer (e.g. FastAPI) that exposes core.py as this API
```

- **Server**: runs `http.py` as a service. The host's *remote adapter* calls it over HTTP.
- **Android**: Chaquopy calls `core.py` **directly**, with no HTTP. The *embedded Python
  adapter* maps the same operations onto function calls.

So the operations below are the contract for both. HTTP is only the transport on the server.

## General

- JSON over HTTP. The base URL and a **shared key** are set in the host's config.
  Every request sends `Authorization: Bearer <key>`.
- IDs are **namespaced**: `ytm:dQw4w9WgXcQ`, `yt:…`. The prefix is the module ID.
- Lists are **paginated** with an opaque `cursor`: the response has `next` (or `null`).
- **User-scoped** calls (a linked YouTube account) get the user's credentials per request
  in the `X-YTMP-Credentials` header. The module stores no user data.
- Errors:

  ```json
  { "error": { "code": "unavailable", "message": "Video unavailable in this country" } }
  ```

  Codes: `not_found`, `unavailable`, `auth_required`, `auth_expired`, `rate_limited`,
  `upstream_error`, `unsupported`.

## Data types

```jsonc
// Song
{
  "id": "ytm:dQw4w9WgXcQ",
  "title": "Never Gonna Give You Up",
  "artists": [{ "id": "ytm:UCuAXFkgsw1L7xaCfnd5JJOw", "name": "Rick Astley" }],
  "album": { "id": "ytm:MPREb_…", "name": "Whenever You Need Somebody" },
  "durationMs": 213000,
  "thumbnails": [{ "url": "https://…", "width": 544, "height": 544 }],
  "explicit": false,
  "isrc": null                 // when known, helps matching songs across sources
}
// Album, Artist, Playlist: id, name/title, thumbnails, and their songs/albums (paginated)

// SearchPage: sections; one for a single type, several for "all"
{
  "sections": [
    { "title": "Top result", "type": null, "items": [ SearchItem ] },
    { "title": "Songs", "type": "songs", "items": [ SearchItem, … ] }   // type: the chip for "More"
  ]
}
// SearchItem, by "kind":
{ "kind": "song", "song": Song, "video": false }        // videos play like songs
{ "kind": "album", "album": AlbumSummary, "artists": [ArtistRef] }
{ "kind": "artist", "id": "UC…", "name": "Daft Punk", "thumbnails": [...] }
{ "kind": "playlist", "id": "VLPL…", "title": "…", "author": "…", "itemCount": 35, "thumbnails": [...] }
{ "kind": "podcast", "id": "MPSP…", "title": "The Daily", "author": "…", "thumbnails": [...] }   // episodes are "song" items with song.podcast set
```

For `all`, the YTM module runs YTM's mixed search (for the top result, albums, artists
and playlists) plus a `songs` and a `videos` search at the same time: YTM's mixed results
mostly lack song durations, and a song without a duration can't end on its own in a room.

## Endpoints

### Module info

| Method | Path | Description |
|--------|------|-------------|
| GET | `/info` | `{ id, name, version, apiVersion, capabilities: [...] }` |
| GET | `/health` | Liveness check |

`capabilities` tells the host which of the optional endpoints below are supported:
`search`, `suggestions`, `browse`, `home`, `explore`, `resolve_url`, `stream`, `audio`,
`radio`, `related`, `lyrics`, `library`, `isrc_lookup`.

### Search & browse

| Method | Path | Description |
|--------|------|-------------|
| GET | `/search?q=&type=&limit=` ✅ | Search. `type`: `all` (default), `songs`, `videos`, `albums`, `artists`, `community_playlists`, `featured_playlists`, `podcasts`, `episodes`. Answers a `SearchPage` (below). Paging (`cursor`) later |
| GET | `/search/suggestions?q=` ✅ | Suggestions while typing: `{ items: ["daft punk", …] }` (the host: `GET /api/search/suggestions?q=`, empty without a source) |
| GET | `/search?isrc=` | Find a song by ISRC (optional `isrc_lookup` capability, helps [matching](storage.md#song-matching)) |
| GET | `/songs/{id}` | Song details |
| GET | `/albums/{id}` | Album with songs |
| GET | `/artists/{id}` | Artist: top songs, albums, singles |
| GET | `/podcasts/{id}` ✅ | A podcast: `PodcastPage` (`id, title, author, description, thumbnails, episodes: [{ song, date, description }]`). Episodes are `Song`s with `podcast: { id, name }` |
| GET | `/playlists/{id}?cursor=` | Public playlist with songs ✅ (`PlaylistPage`: `id, title, author, description, thumbnails, songs`; the first 200 songs, no paging yet) |
| GET | `/resolve?url=` | Turn a YTM/YT URL into `{ type, item }` (for links pasted in search) |
| GET | `/home` ✅ | Home page: `{ sections: [{ title, items: [SearchItem] }] }` (quick picks, new releases, mixes, …). Songs there have no length (the stream has it). Personalised with credentials (later). The host: `GET /api/home`, cached 30 min |
| GET | `/explore` | Explore page: new releases, charts, moods & genres |
| GET | `/explore/{sectionId}` | One explore section, for example a mood or genre |

### Playback

| Method | Path | Description |
|--------|------|-------------|
| GET | `/songs/{id}/stream` | Resolve a **direct stream URL** on demand: `{ url, expiresAt, mimeType, bitrate, loudnessDb, durationMs }`. `durationMs` ✅ is the exact length (listings round podcast lengths); the host gives the current song that length |
| GET | `/songs/{id}/audio?format=` | The **audio bytes** themselves, with HTTP `Range` support. Used for proxying, the cache, and [downloads](downloads.md). `format` is a preference (for example `m4a`). |

- `/stream` is for the direct path of the [hybrid approach](playback-sync.md). `/audio` is
  for the proxied path and the cache.
- `loudnessDb` (YouTube's loudness value, when available) is used for loudness normalisation.

### Recommendations

| Method | Path | Description |
|--------|------|-------------|
| GET | `/radio?seed=&cursor=` | YTM radio from a song, artist, album or playlist ID (`seed`): an endless list via `cursor`. ✅ Songs only, `limit` (default 25) instead of `cursor`; the host keeps it endless by asking again from the last song |
| GET | `/songs/{id}/related` | Related songs |
| GET | `/artists/{id}` | ✅ Artist page: name, art, description, top songs, albums, singles |
| GET | `/albums/{id}` | ✅ Album page: title, kind, year, artists, art, songs |

The host stores every radio/related result as **YTM edges** in the song graph (see
[storage](storage.md#song-graph)).

### Lyrics

| Method | Path | Description |
|--------|------|-------------|
| GET | `/songs/{id}/lyrics` | `{ plain, synced: [{ timeMs, text }] \| null, source }` |

Lyrics from modules are a **fallback**. The host first looks up the song on **LRCLIB**
itself (by title, artist, album and duration), so synced lyrics work for every source,
including local files.

### Library (user-scoped, needs `X-Ytm-Cookie`)

Implemented in the YTM module: the server sends the account's music.youtube.com cookies in
`X-Ytm-Cookie` with every request (it stores them encrypted, see `YoutubeLinks.kt`); the
module keeps a signed-in session per cookie (up to 50). Cookies that aren't (or no longer)
signed in give `401 not_signed_in`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/me/account` | `{ name, handle, photoUrl }`: checks the sign-in |
| GET | `/me/home` | The account's own home page |
| GET | `/me/library` | Playlists (Liked music first) and podcasts, as home sections |
| GET | `/me/playlists` | The account's own playlists (to save songs to) |
| POST | `/me/playlists` | Create a private playlist: `{ title, songIds }` → `{ id }` |
| GET | `/me/playlists/{id}` | A playlist, also private ones (`LM` = Liked music) |
| POST | `/me/playlists/{id}/songs` | Add songs: `{ songIds: [...] }` |
| GET / PUT | `/me/likes/{songId}` | `{ liked }` |
| POST | `/me/history/{songId}` | Add a play to the account's YouTube Music history |
| GET | `/me/search`, `/me/search/suggestions`, `/me/radio` | Like `/search`, `/search/suggestions` and `/radio`, as the account |

Not yet: removing songs, unliking from a list, liked songs paging.

### Admin

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/status` | yt-dlp / ytmusicapi versions, cache size, last update |
| POST | `/admin/update` | Update yt-dlp now |
| DELETE | `/admin/cache` | Clear the audio cache |

## Output modules

Remote **output** modules (for example a future Discord bot) would use a similar,
much smaller API: `POST /play { audioUrl, positionMs }`, `POST /pause`,
`POST /volume { level }`, `GET /state`. This will be defined when the first remote
output module is built.
