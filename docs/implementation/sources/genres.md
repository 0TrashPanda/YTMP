# Genres

> Status: **built** for the songs in the song cache and downloads, and the songs in rooms (for
> the [room list](../../features/rooms/room-lifecycle.md#the-room-list)), on server, phone and
> desktop app, with the owner's server's list shared by their phones. Not built: sharing with the
> desktop app (it looks its songs up itself), genres in history, radios and the song map.

YouTube Music gives songs no genre (YouTube's only category is "Music"; *Moods & genres* are
playlists). So the host asks music catalogs that do, by artist and title:

1. **Apple** (the iTunes Search API, `itunes.apple.com/search`, no key): one genre per song,
   like a store's ("Pop", "Hip-Hop/Rap", "Dance"). About 20 lookups a minute are allowed.
2. **Deezer** (`api.deezer.com/search`, then `/album/{id}`, no key), when Apple doesn't have
   the song: the album's genre.

Only the first artist and the title leave the host. Podcast episodes are *Podcast* without a
lookup.

## Matching

`OnlineGenres` (`host/GenreLookup.kt`) takes the results with the same title and artist:
compared in lower case without accents, punctuation, `&`/`and`, and without what stores
write differently (`(feat. …)`, `[Remastered]`, ` - Radio Edit`). One of the song's artists
must be in the result's artist. Of those, the exact title closest in length wins; another
edit (a store's album version) still counts, it has the same genre. Store-wide labels
("Music") aren't genres.

## Where they're kept

`SongCache` keeps them in its `index.json` (`genres`: song ID → genre, `""` when none was
found, so it isn't asked again), at most 50,000.

- **In the background** (`genreLoop`): 20 s after start, then one song every 4 s: first the
  songs room lists asked for, then the cached songs and downloads that have none yet. When it can't ask (offline, a service says too
  many lookups) it tries again 10 minutes later. New songs wake it up.
- **Cached on this phone**: its songs carry their genre (`LocalTrack.genre`), so its
  **Genres** list in the Local tab works as for music files.
- **Room lists**: `RoomSummaries` (`core/`) counts the genres of a room's last and next songs
  (`RoomSummary.genres`, `mixed`). It asks `SongCache.genreSoon`, which answers what's known
  and puts the rest first in line, without waiting; the next look at the list has them. The
  server does the same for your rooms on your other devices (`RoomHub`).
- **Downloads**: `SongFiles` writes it in the file's genre tag (`©gen`), from the cache or
  looked up then (`SongCache.genre`), so other music players see it too.

## Shared through the owner's server

The server's song cache holds the list for all of an account's devices, so a song is looked
up once. A phone linked to a YTMP account (`SongCache.genreShare`) asks it first:

- `POST /api/auth/genres` with the account's host token: `GenreExchange` (`found`: what the
  phone looked up itself, `wanted`: up to 100 song IDs it needs) → `GenreAnswer` (what the list
  has of them, `""` included). At most 1,000 of each per call.
- The phone asks about its next songs (the ones room lists want, then cached songs and
  downloads) before looking any up. The ones the list doesn't have it looks up itself, and
  sends them along the next time: after 20, or when it has nothing left to look up.
- The list keeps what it hears unless it has the song already (`SharedGenres.addGenres`), and
  only YouTube Music songs (local files have their own tags).
- Not reachable (away from home, an older server): the phone looks songs up itself, and asks
  again 10 minutes later. What it found meanwhile goes to the list then (unless the app was
  closed in between).
- The genres aren't personal: every account on the server shares the one list.
