# Sharing by holding phones together (NFC)

What it does: [features/sharing.md](../../features/rooms/sharing.md).

Android Beam (NFC peer-to-peer) is gone since Android 10, and Quick Share can only be reached
through the share sheet. What's left is **host card emulation** (HCE): the app answers as an
NFC tag. The other phone (Android, or an iPhone XS or newer) reads it like any sticker with a
link, in the background.

## The tag (`android/.../share/`)

- `TapShareService` is a `HostApduService` for the NFC Forum's NDEF application
  (AID `D2760000850101`, `res/xml/tap_share.xml`, category *other*, needs the phone unlocked).
  The manifest declares `android.hardware.nfc` and `android.hardware.nfc.hce` with
  `required="false"`: phones without NFC install the app as before.
- `Type4Tag` answers the reader as an **NFC Forum Type 4 tag** (mapping version 2.0):
  - `SELECT` the NDEF application → `9000`, or `6A82` when nothing is shared (no tag here);
  - `SELECT` file `E103` (capability container: read-only NDEF file `E104`, up to 255 bytes per
    read) and `E104` (the NDEF file: 2-byte length, then the message);
  - `READ BINARY` at an offset (Le 0 = 256); `UPDATE BINARY` → `6982`, other commands `6D00`,
    another class `6E00`.
  - The link is taken when the reader selects the application, so it can't change halfway a
    read; `onDeactivated` forgets it.
- `Ndef.uriMessage` makes one well-known URI record (`U`) with the usual prefix codes
  (`https://` = 4, …), a short record when the payload fits in a byte; with *Open in YTMP on
  their phone* (`TapShare.openInApp`) an **Android Application Record** follows
  (`android.com:pkg` = the app's package). Why: Android's NFC dispatch shows an http(s) link
  through its "open link" step, which hands it to the app that owns the link (YouTube Music
  for `music.youtube.com`, a verified app link) before our `NDEF_DISCOVERED` filter counts. An
  AAR makes it start that package's matching activity; without the package installed, Android
  opens its store page. iPhones ignore it.
- `TapShare` holds the link and whether the app is on screen (`onResume`/`onPause`). It
  answers only when both are set. While sharing on screen, `MainActivity` makes the service
  the **preferred** one (`CardEmulation.setPreferredService`), in case another app also
  answers as an NDEF tag, and turns on **foreground dispatch** for every tag: two phones held
  together also read each other, and what this phone reads (a Pixel's own tap-to-share contact
  card, the other phone's YTMP tag) comes to `onNewIntent` and is dropped instead of opening
  something over the Share sheet. A new page clears the link.

## The bridge (version 18, 19)

- `tapShare()`: `"on"`, `"off"` (NFC switched off) or `"none"` (no NFC or no HCE).
- `setTapShare(url | null)`: what the tag hands over; version 19 `setTapShareFor(url, openInApp)`
  also says whether the tag names YTMP (`TapShareHint.svelte`'s switch, `saved.tapShareInApp`). The web app's `TapShareHint.svelte` (in
  `ShareSheet.svelte` and `SongShareSheet.svelte`) sets it while the sheet shows and clears it
  when it closes (`share.svelte.ts` `shareByTap`). Only from the app's own pages.
- `openNfcSettings()`, `shareLink(title, url)` (Android's share sheet; the WebView has no
  `navigator.share`).
- `takeShared()` and the page callback `onShared()`: a song that arrived.
- `openSharedSongs()` / `setOpenSharedSongs(on)`: *Settings → Sharing → Open shared songs in
  YTMP*, kept natively because it's needed before any page is loaded.

## Getting a room or song

`MainActivity` takes:

- `NDEF_DISCOVERED` for `http(s)://*/room/…` (a room link opens in the app, not the browser)
  and `https://music.youtube.com/watch` (a song);
- `SEND` with `text/plain` (*Share → YTMP* from YouTube Music or any app; the link may come
  after the title).

`SharedLinks.parse` finds the song (YouTube Music, YouTube, `youtu.be` and shorts links →
`ytm:<videoId>`) or the room. A room goes to `openLink` like a scanned QR code. A song is kept
for the page (`takeShared`) and announced (`onShared`); with *Open shared songs in YTMP* off, a
tapped song goes on to YouTube Music instead (`ACTION_VIEW`: YTMP doesn't handle that link
itself, so it goes to YouTube Music or the browser).

## The song screen (web app)

- `SharedSong.svelte`: full page or sheet (setting `sharedSongLook`), loading the song with
  `getSong` and reusing `songActions.ts` (`playNow`, `playNext`, `addToQueue`, `startRadio`),
  `likes.svelte.ts`, `SaveSheet.svelte` and `SongShareSheet.svelte`.
- The room page shows it over the room (`onSharedSong` in `share.svelte.ts`). The room list
  shows it with no room: the playing choices open `PlayItIn.svelte` (a new solo room, the
  phone's rooms, rooms nearby), which goes to the room's link with
  `?shared=<songId>&do=play|next|end|radio`; the room page does that once it's connected and
  takes the parameters off the address.

## Tests

`android/src/test/.../share/Type4TagTest.kt` (the APDU answers, records, long links read in
parts) and `SharedLinksTest.kt`; `frontend/src/lib/share.svelte.test.ts` (song links and the
room link's parameters). Real tapping needs two phones.
