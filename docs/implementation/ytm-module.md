# YouTube Music Source Module

Feature description: [features/sources.md](../features/sources.md#youtube-music-source).

## Server variant

- Separate **Python service**.
- Uses **[ytmusicapi](https://github.com/sigma67/ytmusicapi)** for search and metadata.
- Uses **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** to resolve the audio stream URL.
- The stream URL is fetched **on demand**, when the song is about to be played.
- Stream links are cached until shortly before they expire (`YtmCore._streams`); a link
  YouTube refuses is dropped (`forget_stream`).
- The audio cache is not in the module but in the host (server and phone alike): see
  [song-cache](song-cache.md).

**On a listener's computer:** the same module can run next to a browser, so the web app
gets stream links from the listener's own IP address (see
[playback-sync](playback-sync.md#known-risk-ip-bound-stream-urls)). With a key set, it allows
requests from any web page (CORS, `Access-Control-Allow-Private-Network`); the key keeps
other pages out. Without a key it sends no CORS headers.

## YouTube's bot check and PO tokens ✅

After too many lookups from one network (yt-dlp's estimate: ~300 songs an hour signed out),
or when YouTube distrusts the network for other reasons, it answers lookups with "Sign in to
confirm you're not a bot", for the whole network, for minutes to hours. Seen on a home server
on 2026-10-18 with only 8–16 lookups an hour, so it's not only about the rate.

- **PO tokens** ("proof of origin"): a token from YouTube's BotGuard script that says a
  request comes from a real YouTube player. yt-dlp asks as the usual player (`visionos`, no
  token, no ads) **and** as the mobile web player (`mweb`) with a token for its player
  request (`fetch_pot=always`, `_PO_TOKEN_ARGS`). The usual player's link is used when it
  gives one; when the bot check turns it away, the mobile web player's link is the fallback.
  That one comes with a pre-roll ad and only works once the ad would be over (a few seconds,
  yt-dlp's `available_at`): `stream()` waits that long (at most 60 s) before answering.
  The log line of each lookup names the player its link came from (`visionos` / `mweb`).
- **Server:** the [bgutil](https://github.com/Brainicism/bgutil-ytdlp-pot-provider) plugin
  (`.[pot]` extra) asks the `pot` container (bgutil's server, ~180 MB of memory) for tokens:
  `YTMP_YTM_POT_URL=http://pot:4416` in [docker-compose.yml](../../deploy/docker-compose.yml).
  The module image follows the newest plugin every night, the container its newest image
  (Watchtower), so the two stay on the same version.
- **Phone:** the app runs BotGuard itself in a hidden WebView (`BotGuard.kt`,
  `assets/botguard.html`), with YouTube's address but no network of its own; `ytmp_ytm/pot.py`
  is the yt-dlp provider around it and does the requests: YouTube's page (BotGuard's
  challenge and settings), BotGuard's script (kept while it's the same), then Google's
  integrity token (`GenerateIT`). The minter that comes out makes a token per video in an
  instant; it's used until Google's expiry (12 h) or until the phone's IP address changes
  (tokens are bound to it). The page closes after 10 minutes without lookups, and starts over
  (~1 s) on the next one. When BotGuard fails, lookups go without tokens for 10 minutes
  (each try fetches YouTube's 1 MB page). Only with yt-dlp's JavaScript engine (the mobile
  web player needs it). Costs ~16 MB in the WebView's process while the page is open; BotGuard
  runs in ~0.3 s.
- **When it still happens:** no lookups for 2 minutes, then 4, 8, 16 and at most 30 while it
  lasts, back to 2 after a lookup works (`_BotCheck`): asking again right away makes the
  network look even more like a bot. Lookups fail at once with `Blocked` (HTTP 503 `blocked`,
  see [module API](module-api.md)) and "Try again in N min."; the room pauses on the song
  instead of skipping (`SourceBlocked`, see [Room](../../core/src/main/kotlin/dev/trashpanda/ytmp/core/Room.kt)).
- Not used: the linked YouTube account's cookies for yt-dlp (yt-dlp warns the account can be
  banned), and proxies.

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
Settings → YouTube Music has a short guide), or hands over the phone app's sign-in. The server keeps
them encrypted with the account (`YoutubeLinks.kt`, AES-GCM) and sends them to the module in
`X-Ytm-Cookie` (see [module API](module-api.md#library-user-scoped-needs-x-ytm-cookie)).
Works without a Google Cloud project. An expired sign-in shows as signed out. yt-dlp stays
anonymous (it doesn't use the cookies).

**Your playlists in parts:** Liked music and your own playlists load part by part (YouTube
Music's own parts, ~100 songs), so a big library opens quickly. ytmusicapi's `get_playlist`
loads all parts itself and doesn't hand out the token for the next one, so `core.py` wraps
its `get_continuations_2025`: with `limit=0` it stops after the first part and keeps that
part's token (per thread) as the page's `more`. The next part is one `browse` request with
that token, parsed with ytmusicapi's own `parse_playlist_items`. If a newer ytmusicapi
renames that function, playlists simply load whole again.

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
