# Genres

> Status: **built** for the songs in the song cache and downloads, and the songs in rooms (for
> the [room list](../../features/rooms/room-lifecycle.md#the-room-list)), on server, phone and
> desktop app. Not built: a shared list of genres on the owner's server (one lookup per song for
> all their devices), genres in history, radios and the song map.

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
