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
- **How settings are set up**: storage is generic (one `Setting` line in
  `frontend/src/lib/settings.svelte.ts` gives account sync, a device's own value and moving
  old values), but the Settings screen places each one by hand (`Toggle`/`Choice` with its
  label and hint in `SettingsScreen.svelte`). A schema-driven configurator probably isn't
  worth it (it would only pay off for something like searching settings). Worth looking at:
  - A few settings skip `Setting`, e.g. the headphones choice is kept in `storage.ts` and
    sends `ytmp:settings` by hand. Move them onto `Setting` (as device-only), or keep it
    on purpose?
  - Settings the Android app needs are fields of what the page sends with playback
    (`player.svelte.ts` → `PlaybackTarget`): each new one is a field in TypeScript and in
    Kotlin. Send them as one settings object instead, if more of them come?
- **Code that could be shared** (found looking for repeated patterns):
  - **Sheets and dialogs**: about 12 web app components (CloneSheet, SaveSheet, ShareSheet,
    SleepTimerSheet, OutputsSheet, LeaveRoomDialog, TvLinkDialog, InterruptDialog,
    HoldChoices, PlayAllButtons, AvatarMenu, the home page's room chooser) copy the same
    backdrop and bottom-sheet panel, each with its own z-index (40 to 90). One `Sheet.svelte`
    would give one place for the layering, Escape, the Android back button and the animation.
  - **Room commands as text in the Android player**: about 18 like `"""{"kind":"Pause"}"""`
    in `PlaybackService.kt` and `SpeakerPlayer.kt`, next to 3 that use the typed `Command`.
    A helper that encodes `Command` would let the compiler catch protocol changes.
  - **The app's events to the page**: `MainActivity.kt` writes
    `js("window.__ytmpNative && window.__ytmpNative.onX && …")` out 21 times; one `emit()`
    would do. Further: generate `native.ts` from the `@JavascriptInterface` methods, as
    `protocol/` does for its types.
  - **Speaker discovery on the server and the desktop app** (`host/…/NetworkDiscovery.kt`):
    the Cast, MPD and zones finders each set up the same JmmDNS listener; one helper, like the
    phone's `NsdFinder`.
  - Already shared well: the output drivers (`OutputDriver`, `DeviceDriver`), the phone's
    finders (`NsdFinder`), the web app's `request()` in `api.ts`, and `Setting`.

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
