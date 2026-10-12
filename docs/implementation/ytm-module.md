# YouTube Music Source Module

Feature description: [features/sources.md](../features/sources.md#youtube-music-source).

## Server variant

- Separate **Python service**.
- Uses **[ytmusicapi](https://github.com/sigma67/ytmusicapi)** for search and metadata.
- Uses **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** to resolve the audio stream URL.
- The stream URL is fetched **on demand**, when the song is about to be played.
- Stream links are cached until shortly before they expire (`YtmCore._streams`); a link
  YouTube refuses is dropped (`forget_stream`).
- Planned: an optional **local cache** of audio for frequently played songs, for faster
  start and fewer requests to YouTube.

## Logging

Python `logging` under `ytmp_ytm` (the HTTP service: console, level `YTMP_YTM_LOG_LEVEL`;
on the phone: the app's log, see [android-app](android-app.md#logs-)). Each stream lookup
logs its time, format, link host and expiry. yt-dlp gets a logger per lookup (`_YdlLog`):
its warnings are logged right away (the same one at most once an hour), its steps are kept
and only logged when the lookup fails or takes longer than 8 s. The audio proxy logs 403s
and the fresh lookup after one, links YouTube refuses (dropped from the cache), pieces
slower than 5 s, and streams that break off. httpx and uvicorn's access log stay quiet
(YouTube links contain the server's IP address).

## Linking a YouTube account

**Phone app (done):** `YoutubeLoginActivity` shows Google's sign-in in a WebView (without
the `; wv` user-agent marker, which Google refuses). Once it lands on music.youtube.com with
`__Secure-3PAPISID`, the cookies go to `PhoneYoutubeAccount` (app-private SharedPreferences
`youtube`, excluded from backup and device transfer) and to the on-device module's
`YtmCore.sign_in()`, which keeps a second, signed-in `YTMusic` next to the anonymous one.
Only `home(personal=True)`, `library()` and `playlist(personal=True)` use it; search, rooms
and yt-dlp stay anonymous. The host serves it through `PersonalCatalog`: `GET /api/home`
(own home for the phone itself), `GET/DELETE /api/me/youtube`, `GET /api/me/library`,
`GET /api/me/playlists/{id}`, all only for loopback requests. An expired sign-in
(`NotSignedIn`) is forgotten; being offline isn't.

**Servers (done):** cookies / request headers, "browser auth" in ytmusicapi. The user
pastes their YouTube Music browser cookies, request headers or a "Copy as cURL" once (the
profile menu has a short guide), or hands over the phone app's sign-in. The server keeps
them encrypted with the account (`YoutubeLinks.kt`, AES-GCM) and sends them to the module in
`X-Ytm-Cookie` (see [module API](module-api.md#library-user-scoped-needs-x-ytm-cookie)).
Works without a Google Cloud project. An expired sign-in shows as signed out. yt-dlp stays
anonymous (it doesn't use the cookies).

## Android on-device variant ✅ (Chaquopy)

Built: the app embeds the module with Chaquopy, see [android-app](android-app.md). Not built:
updating yt-dlp at runtime (below); yt-dlp is only updated with app releases, and using the
server's YTM service from the phone.

### Constraint
YouTube changes often, and **yt-dlp releases fixes very frequently**. If extraction
only gets updated through app updates (Play Store review, users who don't update),
the on-device module will regularly be broken.

### Plan: embed Python with Chaquopy, and update yt-dlp at runtime

- Embed a Python runtime in the app with **[Chaquopy](https://chaquo.com/chaquopy/)**.
- Run the **same Python module code** as the server service, so there is one
  implementation of the YTM logic.
- `yt-dlp` and `ytmusicapi` are **pure Python**. The app ships a known-good version and
  can **download newer yt-dlp releases at runtime** (from PyPI/GitHub), then load them
  instead of the bundled one, with a fallback to the bundled version if the new one fails.
  This decouples yt-dlp fixes from app releases.

Costs and risks:

- Larger APK (Python runtime), and slower cold start of the Python part.
- **JavaScript runtime:** recent yt-dlp versions need a JS runtime (Deno, Node, Bun or
  QuickJS) for full YouTube support. The server's image has Deno. On Android the app gives
  yt-dlp the WebView's JavaScript engine instead, through `ytmp_ytm/jsc.py` (see
  [Android app](android-app.md)).
- Loading downloaded code at runtime would conflict with Play Store policy. This is not an
  issue, because YTMP is distributed through **GitHub Releases**.

### Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| **NewPipe Extractor** (Java) | Native to Kotlin, small, proven on Android | A second implementation next to the server's; fixes only arrive through app updates; YT Music support is limited |
| **Use the server's YTM service from the phone** | No extraction on the phone | Needs a server, so it breaks "works without a server". Good as an *optional* mode. |

Suggested behaviour: if a YTMP server is configured and reachable, the phone **may**
use its YTM service. Otherwise it uses the on-device module.
