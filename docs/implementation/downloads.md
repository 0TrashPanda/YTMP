# Downloads

Feature: *Download* in the [song menu](../features/ui.md#song-menu).

## Who does what

| Step | Done by |
|------|---------|
| Get the audio | **Module**, through `audio` (see [module API](module-api.md)) |
| Make it a proper file: container, tags, album art | **Host** (YTMP) |
| Save it | **Your device**: a normal browser download, or the Android app saves it to the Music folder |

The host does the work so it is written **once** for every source, and the device only
receives a finished file.

## Format: no conversion needed

YouTube offers audio in a few formats, including **AAC in an `.m4a`** container. Downloads
use that one. That's already a normal music file
that every phone, PC and music player can play, so the host only has to **add tags**
(title, artist, album, track number, album art). It doesn't re-encode, so there is no
quality loss and no ffmpeg needed.

- Tags are written with a Java/Kotlin tagging library (for example **jaudiotagger**), which
  runs on both the server and Android, so a phone host can make downloads too.
- **Local files and media server songs** are already files. They are passed through as
  they are, if the owner has [enabled downloads](../features/ui.md#download-rules).
- **ffmpeg** is optional, on the server only, if we ever want other formats (for example
  converting to MP3 or Opus). It's not needed for the default.

## Why not in the browser?

A browser can save a file, but adding tags to `.m4a` files in JavaScript is poorly supported
and would only work in the browser. Doing it on the host means the same result in the
browser and the app, and the browser just downloads the finished file.
