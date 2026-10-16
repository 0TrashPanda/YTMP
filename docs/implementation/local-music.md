# Local music

Music the host reaches itself, next to YouTube Music: the music folder of a Raspberry Pi with
MPD (shared with the [zones file](zones.md)), and the music stored on the hosting phone. It's in
the **Local** tab (see [sources](../features/sources.md#local-music-)).

## Libraries

`LocalMusic` (`host/`) holds the host's libraries (`LocalLibrary`): each one hands out its songs
as tracks (title, artist, album artist, album, disc, track, year, length, folder), and knows a
link to each file and cover. From the tracks it makes the songs, albums and artists:

- **Ids:** songs `local:<library>:s:<key>` (MPD: the file's path; phone: the media store id),
  albums `local:<library>:a:<folder>/<album>`, artists `local:<library>:r:<name>`.
- **An album** is the songs with the same album name in the same folder, in disc and track
  order. Without one album artist (songs by several artists): *Various Artists*.
- **Copies** (the same artist, album, disc, track and title, about as long) show once: the one
  with the shortest path. Music folders often have the same album twice.
- **Autoplay, Start radio, Find similar** from a local song: up to 50 songs of the library,
  about 10 of them by the same artist, mixed. The owner's own YouTube Music radio isn't asked.
- Local songs stay out of YouTube Music's history, likes and playlists (the web app hides them).

`LocalMusic` sits in front of the YouTube Music source on both hosts (server and phone): stream
links (`streams`), radio, album and artist pages (`catalog`) and `/api/audio` (`audio`) go to
the library for `local:` ids, to YouTube Music for the rest.

## API

- `GET /api/local`: the libraries (id, name, kind, number of songs).
- `GET /api/local/{id}`: one whole (albums and artists as search items, and the songs); the
  Local tab filters it as you type.
- `GET /api/albums/{id}`, `GET /api/artists/{id}`: as for YouTube Music.
- `GET /api/local/art/{songId}?size=N`: covers a library serves through the host (the phone).
- `GET /api/audio/{songId}`: the file through the host, with ranges.

## Playing

- **The room's stream link** is the file's own link: the Pi's web server, or the phone's
  `/api/audio`. Players on the network play it directly; elsewhere they fall back to the host
  (`/api/audio`, which passes the file on with ranges, so seeking works).
- **Speakers and TVs** (Chromecast, Sonos, MPD, amplifier zones) fetch from the host's
  `/api/audio` like every song. The Pi's own MPD too: it fetches its own file through the host,
  so taking over the player and handing it over between hosts work as for YouTube songs.

## The Pi's music (MPD)

`MpdLibrary`: the songs from MPD's database (`listallinfo`, or folder by folder with `lsinfo`
when the database is too big for MPD's output buffer), read again when MPD's `db_update`
changes (checked at most every minute). The zones file's `music` says where the files and
covers are:

```json
"music": {"name": "Music on the Pi", "files": "/music/", "art": "/art/"}
```

`files` and `art` (these are the defaults) are relative to the file's own address. A file can
share only music, without an amp (`player` and `music`).

[`ytmp-share.py`](../../deploy/zones/ytmp-share.py) on the Pi serves it all on one port:
the zones file, MPD's music folder (with ranges) and the covers, which it gets from MPD
(`readpicture`, the picture in the file, else `albumart`, a cover file in the folder) and
shrinks when `python3-pil` is installed (else they're sent as they are, often 500 KB).
Setting it up: see [zones](zones.md#setting-up-the-pi).

**Copies in the music folder:** YTMP shows each song once, but the folder keeps them.
[`tools/music-dedupe.py`](../../tools/music-dedupe.py) on the Pi finds them the same way (from
MPD's tags) and moves the extra copies out (to `~/Music-duplicates`, keeping their paths), then
has MPD update. Without `--move` it only shows what it would move.

## The music on the phone

`PhoneLibrary` (`android/`): Android's media store (`IS_MUSIC`), read again when its generation
changes. The phone serves the files (`/api/audio/…`, from a file descriptor, with ranges) and
covers (`ContentResolver.loadThumbnail`) itself, at its address on the network, so speakers and
friends reach them too.

Settings → Local music → *Music on this phone* (bridge version 10: `phoneMusic`,
`setPhoneMusic`, answered with `onPhoneMusic`) asks Android once for `READ_MEDIA_AUDIO`
(`READ_EXTERNAL_STORAGE` before Android 13). It's in rooms on the phone only.

## Not yet

- The phone's music in a room on a server (the phone would send each song to the server).
- Playlists from MPD or the phone; sorting choices in the Local tab.
- Local music in the main search.

Tested against a simulated MPD (`LocalMusicTest`), with the Pi's real library (721 songs after
copies) through a test server, and the phone's music on the emulator.
