# Downloads

> Status: **built** for YouTube Music songs and podcast episodes. Not built: downloading
> someone's local music or media server songs (see [download rules](../features/ui.md#download-rules)).

Feature: *Download* in the [song menu](../features/ui.md#song-menu).

## Who does what

| Step | Done by |
|------|---------|
| Get the audio | The host's `AudioFiles`: on a server the module's `/songs/{id}/audio` (the whole file), on a phone straight from YouTube in 1 MB pieces (`OnDeviceAudioProxy`) |
| Make it a proper file: tags and album art | The host (`SongFiles`, `M4aTags` in `host/`) |
| Save it | **A browser**: `POST /api/download` on the room's host, saved like any download. **The app**: the bridge's `downloadSong`, saved in the phone's `Music/YTMP` folder |

The host does the work so it is written **once** for every host, and the device only
receives a finished file. The app doesn't ask the room's host: it is a host itself, so it
looks the song up on the phone and makes the file there, also in a room on a server
elsewhere (whose audio may not reach the phone, see [`[audio] proxy`](playback-sync.md)).

`POST /api/download` takes the song as the page knows it (a `Song`, for the tags) and
answers the file with a `Content-Disposition` name ("Artist - Title.m4a"). It follows the
same rule as audio through the host: a server with `proxy = "lan"` only sends songs to
devices on its own network. Local music is refused.

## Format: no conversion needed

YouTube offers audio in a few formats, including **AAC in an `.m4a`** container, which the
module already prefers. That's a normal music file that every phone, PC and music player
can play, so the host only **adds tags**: title, artist, album (a podcast's name for an
episode, with genre *Podcast*), album artist and the cover (the song's largest picture,
1200 px when Google can make it so; only from YouTube's picture servers), and the song's ID (a
freeform item `----:dev.trashpanda.ytmp:id`), so the phone's
[song cache](song-cache.md#downloaded-songs) knows which song a download is. It doesn't
re-encode, so there is no quality loss and no ffmpeg.

- The tags are written by `M4aTags`, a small writer of iTunes-style tags
  (`moov/udta/meta/ilst`) in plain Kotlin, so it runs on the server and on Android alike.
  YouTube's files are fragmented MP4 (`moof`/`mdat` pieces after a `sidx`), whose offsets
  count from each fragment, so a bigger `moov` moves nothing that matters. For other `.m4a`
  files it moves the absolute offsets along (`stco`/`co64`, a `tfhd` base offset, `tfra`).
- When YouTube has no `.m4a` for a song, the file is kept as it came (for example `.webm`),
  without tags.
- **Local files and media server songs** are already files. They could be passed through as
  they are, if the owner has [enabled downloads](../features/ui.md#download-rules) (not built).
- **ffmpeg** is optional, on the server only, if we ever want other formats (for example
  MP3). It's not needed for the default.

## In the app

`PhoneDownloads` saves the file through Android's media store in `Music/YTMP/` (no
permission needed for the app's own files), so music players see it, and so does the
Local tab's *Music on this phone*. A song that is already there (the same file name, saved
by the app) isn't downloaded again; tapping twice downloads once. The page hears back
through `onDownloaded(songId, error)` and shows a message.

## The queue in the app

`PhoneDownloads` keeps a job per song (`waiting`, `downloading`, `failed`), saved in
`files/download-queue.json` and taken up again at start (`restore()`). A job waits in `gate`
(`DataRules.awaitDownload`: Wi-Fi, if the owner wants that; checked every second, so *Download now*,
which sets the job's `now`, starts it at once). The settings page lists them through
`GET /api/cache/downloads` and `POST …/{cancel,retry,now}` (`CacheSettings`, phone only).

## Why not in the browser?

A browser can save a file, but adding tags to `.m4a` files in JavaScript is poorly supported
and would only work in the browser. Doing it on the host means the same result in the
browser and the app, and the browser just downloads the finished file.
