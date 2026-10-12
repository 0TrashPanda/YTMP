# Open Questions

Things not decided yet. Move answers into the relevant feature/implementation docs.

## Features

_None open right now._

## Implementation

- **YouTube bot check**: after many requests, YouTube answers "Sign in to confirm you're not a
  bot" for the whole network (seen during testing on 2026-10-02, from both the server and the
  phone). Options: send the linked YouTube account's cookies with yt-dlp requests, a PO token
  provider plugin, and caching more aggressively so fewer requests are made.
- **Updating yt-dlp on the phone**: it only changes with app releases now. Download newer
  yt-dlp releases at runtime (see [ytm-module](implementation/ytm-module.md#plan-embed-python-with-chaquopy-and-update-yt-dlp-at-runtime)),
  or just release the app more often?
- **Song graph weights**: tune the weight formula and radio walk once there is real data
  (see [implementation/storage.md](implementation/storage.md)).
- **Loudness for local files / media servers**: YouTube gives a loudness value, but local
  files don't always have one. Use ReplayGain tags when present, otherwise measure the
  loudness on the host (for example with ffmpeg on the server)? How on a phone host?

## Settled

- **Module HTTP API**: built as described in [module-api](implementation/module-api.md).
- **Protocol messages**: built as described in [protocol-messages](implementation/protocol-messages.md).
- **Kotlin → TS types**: a small custom generator (`protocol/.../tsgen`, `./gradlew
  :protocol:generateTs`), not kxs-ts-gen.
- **On-device YTM**: Chaquopy with the same Python module; yt-dlp's JavaScript runs in the
  WebView's JavaScript engine instead of QuickJS (see [android-app](implementation/android-app.md)).
