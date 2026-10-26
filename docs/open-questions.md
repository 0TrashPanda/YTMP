# Open Questions

Things not decided yet. Move answers into the relevant feature/implementation docs.

## Features

_None open right now._

## Implementation

- **Updating yt-dlp on the phone**: it only changes with app releases now. Download newer
  yt-dlp releases at runtime (see [ytm-module](implementation/ytm-module.md#plan-embed-python-with-chaquopy-and-update-yt-dlp-at-runtime)),
  or just release the app more often?
- **Song graph weights**: the defaults are still a first guess; each account can now tune
  them with the graph view's sliders, and those choices are the data to pick better defaults
  from. See [storage](implementation/storage.md#raw-counters-weights-computed-at-query-time).
- **Loudness for local files / media servers**: YouTube gives a loudness value, but local
  files don't always have one. Use ReplayGain tags when present, otherwise measure the
  loudness on the host (for example with ffmpeg on the server)? How on a phone host?

## Settled

- **YouTube bot check**: PO tokens (bgutil's server on a server, BotGuard in a WebView on the
  phone), and a pause before asking again; not the account's cookies. See
  [ytm-module](implementation/ytm-module.md#youtubes-bot-check-and-po-tokens-).

- **Module HTTP API**: built as described in [module-api](implementation/module-api.md).
- **Protocol messages**: built as described in [protocol-messages](implementation/protocol-messages.md).
- **Kotlin → TS types**: a small custom generator (`protocol/.../tsgen`, `./gradlew
  :protocol:generateTs`), not kxs-ts-gen.
- **On-device YTM**: Chaquopy with the same Python module; yt-dlp's JavaScript runs in the
  WebView's JavaScript engine instead of QuickJS (see [android-app](implementation/android-app.md)).
