# YouTube Music Source Module

Feature description: [features/sources.md](../features/sources.md#youtube-music-source).

## Server variant

- Separate **Python service**.
- Uses **[ytmusicapi](https://github.com/sigma67/ytmusicapi)** for search and metadata.
- Uses **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** to resolve the audio stream URL.
- The stream URL is fetched **on demand**, when the song is about to be played.
- Optional **local cache** of audio for frequently played songs, for faster start and
  fewer requests to YouTube.

## Linking a YouTube account

**Decision: cookies / request headers.** The user pastes their YouTube Music browser
cookies or request headers once ("browser auth" in ytmusicapi). yt-dlp can use the same
cookies. They are stored encrypted with the user's account on the auth server.

- Works without a Google Cloud project.
- Cookies expire now and then. YTMP shows a clear "relink your YouTube account" message
  when that happens.
- The UI needs a short guide on how to copy the headers or cookies from the browser.

## Android on-device variant (proposal)

### Constraint
YouTube changes often, and **yt-dlp releases fixes very frequently**. If extraction
only gets updated through app updates (Play Store review, users who don't update),
the on-device module will regularly be broken.

### Recommendation: embed Python with Chaquopy, and update yt-dlp at runtime

- Embed a Python runtime in the app with **[Chaquopy](https://chaquo.com/chaquopy/)**.
- Run the **same Python module code** as the server service, so there is one
  implementation of the YTM logic.
- `yt-dlp` and `ytmusicapi` are **pure Python**. The app ships a known-good version and
  can **download newer yt-dlp releases at runtime** (from PyPI/GitHub), then load them
  instead of the bundled one, with a fallback to the bundled version if the new one fails.
  This decouples yt-dlp fixes from app releases.

Costs and risks:

- Larger APK (Python runtime), and slower cold start of the Python part.
- **JavaScript runtime:** recent yt-dlp versions need an external JS runtime (Deno,
  Node, Bun or QuickJS) for full YouTube support. On Android this most likely means
  bundling **QuickJS** as a native library. *This must be verified with a prototype.*
- Loading downloaded code at runtime would conflict with Play Store policy. This is not an
  issue, because YTMP is distributed through **GitHub Releases**.

### Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| **NewPipe Extractor** (Java) | Native to Kotlin, small, proven on Android | A second implementation next to the server's; fixes only arrive through app updates; YT Music support is limited |
| **Use the server's YTM service from the phone** | No extraction on the phone | Needs a server, so it breaks "works without a server". Good as an *optional* mode. |

Suggested behaviour: if a YTMP server is configured and reachable, the phone **may**
use its YTM service. Otherwise it uses the on-device module.
