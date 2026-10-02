# Open Questions

Things not decided yet. Move answers into the relevant feature/implementation docs.

## Features

_None open right now._

## Implementation

- **Module HTTP API**: review the draft (see [implementation/module-api.md](implementation/module-api.md)).
- **On-device YTM**: validate the Chaquopy + runtime-updated yt-dlp + QuickJS proposal
  with a prototype (see [implementation/ytm-module.md](implementation/ytm-module.md)).
- **Protocol messages**: review the draft (see [implementation/protocol-messages.md](implementation/protocol-messages.md)).
- **Kotlin → TS generator**: try kxs-ts-gen in a prototype, or fall back to a custom Gradle task.
- **Song graph weights**: tune the weight formula and radio walk once there is real data
  (see [implementation/storage.md](implementation/storage.md)).
- **Loudness for local files / media servers**: YouTube gives a loudness value, but local
  files don't always have one. Use ReplayGain tags when present, otherwise measure the
  loudness on the host (for example with ffmpeg on the server)? How on a phone host?
- **YouTube bot check**: after many requests, YouTube answers "Sign in to confirm you're not a
  bot" for the whole network (seen during testing on 2026-10-02, from both the server and the
  phone). Options: send the linked YouTube account's cookies with yt-dlp requests, a PO token
  provider plugin, and caching more aggressively so fewer requests are made.
