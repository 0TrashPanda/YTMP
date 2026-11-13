# Playback & Sync

Feature description: [features/playback-and-outputs.md](../features/playback-and-outputs.md).

## Approach

1. The host resolves the **stream URL** for the current song (on demand, through the
   source module). The next song in the queue is looked up ahead of time: when a song
   starts, and again when another song becomes next (added to play next, moved up), once
   per song (`Room.warmUpNext`). It waits until the change is done, so jumping far ahead in
   the queue looks up only the song that ends up next, not each one passed.
2. The host sends the stream URL and the **playback state** (song, position, playing or
   paused, the timestamp of that state) to each playing client over the WebSocket
   (`wss://` on servers, `ws://` on the LAN by default, see [networking](networking.md)).
3. Clients play the stream themselves and correct drift from the periodic state
   updates.

YTMP's own Play button goes through `play()` in `frontend/src/lib/player.svelte.ts` (Pause
through `pause()`). With *Press play to play here* on (`settings.playStartsHere`), in a
private (solo) room where `RoomConnection.listeningAlone` holds (no other participant
listening, no speakers near a phone, no active output) and this page may `listen_locally`,
it calls the player's `enable()` inside the click (a browser's audio unlock) before sending
`Play`; `SetListening` and `Play` go over the same socket, in that order.

## Clock sync

"Position X at time T" only works if clients know the host's clock. Proposal:

- NTP-style ping/pong over the WebSocket to estimate the **clock offset** and
  round-trip time for each client.
- Host broadcasts `{songId, positionMs, hostTimeMs, playing, speed}`. A client computes
  `expectedPos = positionMs + (now + offset − hostTimeMs) × speed`.
- **Speed**: a podcast episode can play faster or slower (`speed`, the room's episode speed,
  0.5–2 in steps of 0.05, `isEpisodeSpeed`; music is always 1). The room's clock moves at that speed (and the song ends sooner or later),
  players play at it, and their small corrections (±3%) are on top of it (`ownRate` in
  `sync.ts`, `SyncCorrection.ownSpeed` on Android). Chromecasts get `SET_PLAYBACK_RATE`; Sonos,
  MPD and amplifier zones can't change speed and sit such an episode out (paused, with a notice).
- Small drift → nudge playback rate slightly. Large drift → seek.

## Known risk: IP-bound stream URLs

YouTube stream URLs (googlevideo) are usually **bound to the IP address that resolved
them**. A URL that the server resolved will likely return 403 when a client on another
network uses it. Options:

| Option | Pros | Cons |
|--------|------|------|
| **Host proxies the stream** (client gets a host URL, host fetches from YouTube) | Always works; makes the cache easy; hides the source | Host bandwidth for every playing client |
| **Each client resolves its own URL** (host only sends the song ID) | No host bandwidth | Every client needs extraction (impossible in a plain browser without a server) |
| **Hybrid**: send the direct URL, fall back to the proxy on failure | Cheap when it works | More complex |

**Decision: hybrid.**

1. The host sends the **direct stream URL** first.
2. If the client can't play it (403, or another error, within a short timeout), it asks
   the host for a **proxied URL** and the host streams the audio through itself.
3. The client remembers the result for the room, so it doesn't retry the direct URL for
   every song.

A server can limit step 2 to save its bandwidth: `[audio] proxy = "always" | "lan" | "never"`
in `ytmp.toml` (`AudioProxyMode` in server/). With `lan`, only clients with a private,
loopback or link-local address may use `/api/audio`; behind a reverse proxy on the local
network (e.g. Caddy) the last `X-Forwarded-For` entry counts, and the header is ignored when
the request doesn't come from such a proxy. Others get 403 and the player shows it can't
play the song. Verified: the stream URL carries `ip=<server's public IPv4>` in `sparams`.

**Own lookups** ✅ (between steps 1 and 2): after the direct URL failed, a client that can
resolve YouTube links itself does so for the rest of the room, and only uses the proxy for a
song whose own link fails too. Songs that aren't YouTube's (`ytm:` IDs) keep the host's URL.

- **Android app** (`PlaybackService`, `Source`): the phone's own module (`OnDeviceYtm`,
  `LocalHost.ytm`), not for rooms on the phone itself. Links are kept per song while they
  have 10 min left; the next song (queue, else autoplay, from `RoomFollower`) is looked up
  as soon as one starts.
- **Browser** (`WebPlayer`, `ownModule.svelte.ts`, `songSources.ts`): a YTM module the listener
  runs on their own computer, set in *Settings → Where songs play from* (`saved.ownModule`:
  address, key and on/off). `moduleStatus` checks it with `GET /info` when the page opens and
  every minute while it doesn't answer (or a lookup can't reach it); meanwhile it's skipped
  and the room shows a line until it answers or is switched off. The player tries the ways in
  `saved.songSources` (default: room's link, own module, host; in the desktop app own module first) with `pickSource`, once each per
  song; a room link from YouTube that failed is skipped for the next songs too, until nothing
  worked for a song. The player calls `GET /songs/{id}/stream` there and prefetches the next
  song. The module answers pages from any origin only when it has a key (CORS, including
  Chrome's private network preflight; see [module API](module-api.md)).
- A lookup that fails (module unreachable, wrong key, unavailable) is reported to the host
  log and the song goes to the next way in the order.

**Fetching in pieces** ✅: YouTube sends a request for the whole rest of a file (no end to its
`Range`) only about as fast as the song plays, so a player's buffer runs dry: short pauses
(seen on 2026-10-15 with only 1–2 s buffered ahead). Both proxies (`OnDeviceAudioProxy`, the
module's `/songs/{id}/audio`) and the app's own player (`ChunkedDataSource` for
`*.googlevideo.com`) ask for 1 MB pieces with an explicit end, like yt-dlp does.

**When YouTube refuses a song** ✅: at times YouTube serves a link's first MB and then answers
403, for every connection (the phone's own link and the server's alike), for minutes. The
proxies then resolve the song again once and go on from that byte. When a song doesn't play
from anywhere, the app tries it again after 10 s and 30 s (fresh links each time) and the
player says so ("Trying again…", then that it can't play the song); the room doesn't wait
for that device.

Local files, media server songs and cached songs are always served by the host.

### Search and pages from your own module

✅ With your own module usable (`moduleUsable()`: set up, on, not known to be down),
`api.ts` asks it for the catalog as well: `fromOwnModule()` decides per request, `lookUp()`
asks the module (`askOwnModule`, 12 s timeout) and on any failure (unreachable, wrong key,
`blocked`, timeout, another error) asks the host's `/api/…` route instead. A `not_found`
answer is passed on as the host's 404 would be, without asking the host again. Unreachable
or a refused key marks the module down (`moduleStatus`), so the next requests go straight to
the host until the minute's check finds it again; `blocked` and other errors only fall back
for that request. Per route:

| What | Module | What the host adds, and where it went |
|------|--------|---------------------------------------|
| Search, suggestions | `/search?…&limit=20`, `/search/suggestions` | Its 30 min cache: the page's own cache (`cached`, 30 min) does that. Same `SearchPage`; *In your library* was already the page's own (`librarySearch`). With your sign-in on this host and *personalize* on, or your server's account ([`useYoutubeFrom`](../features/accounts.md)), searches stay with the host (`/api/search`, `/api/me/search`): the sign-in lives there |
| Home | `/home` | Its 30 min shared cache: the page keeps Home anyway (`sinceLast`, *Settings → Home*). Signed in to YouTube Music here (Home is then yours) or through your server: stays with the host |
| Artist, album, playlist (and its parts), podcast, song (a pasted link) | `/artists/…`, `/albums/…`, `/playlists/…?more=`, `/podcasts/…`, `/songs/…` | Nothing for YouTube Music's IDs. The host's own music (`local:` IDs: local libraries, the song cache) stays with the host |
| Lyrics | `/songs/{id}/lyrics` | LRCLIB isn't YouTube, so the host still looks there: `/api/lyrics?…&from=lrclib` (only LRCLIB, kept apart in `LyricsLookup`'s cache). Timed lyrics win as on the host: LRCLIB's timed ones, else the module's timed ones, else LRCLIB's plain ones, else the module's. Podcast episodes and other sources: the host as before |

Stays with the host: Find similar, radio and autoplay (the host's radio mixes in the song
graph and picks the room's songs), *Recognize a song*'s lookup (`/api/recognize/song`, the
host's import matcher), everything under `/api/me/…` (the sign-in is on the server), the
room's own links, the song cache, imports and anything that changes the room. Pasted links
are read in the page (`links.ts`) and opened as the pages above; the module's planned
`/resolve` isn't needed.

### Equalizer in a browser

✅ `WebPlayer` sends the audio element through Web Audio (`createMediaElementSource`, a
`BiquadFilterNode` per band, a gain) once the equalizer is first switched on, and keeps it
there (switched off it's flat). Web Audio only gets the sound of a file from another site when
that site allows it (CORS), and plays silence otherwise, so the element then asks for that
(`crossOrigin = "anonymous"`). A link that doesn't allow it fails to load and goes down the
same steps as above: your own module (which allows any page with the key) or the host's
`/api/audio` (same site). A song already loaded when it's switched on is loaded again. The
`AudioContext` starts with the tap that turns on *this device*. The app's player does the
same in native code (see [Android app](android-app.md)).

## Sync accuracy

Where small desyncs between devices come from, and what addresses them:

| Cause | Size | Fix |
|-------|------|-----|
| Output delay (speakers, and especially Bluetooth) is invisible to the player | 20–300 ms | Per-device **sync adjustment** (manual), later set automatically by [mic sync](../features/future-ideas.md#automatic-sync-with-the-microphone) |
| Drift tolerance: each device only corrects above a threshold | up to 2× the threshold between two devices | Small threshold with gentle, proportional speed changes |
| Clock offset estimate | ~10–30 ms on Wi-Fi | Best-of-N ping samples (already done) |

Implemented (A + B), and tested on a Pixel 7:

- **Android (ExoPlayer):** the same rules as the browser (below), since 0.11.2. Before, it
  only seeked, and in party rooms it kept jumping. Measured in the emulator (2026-10-14):
  `ExoPlayer.currentPosition` isn't advanced between the playback thread's updates, so it
  stands still for ~250 ms and then jumps: a ±125 ms sawtooth (the "±120 ms jitter" measured
  on a Pixel 7 earlier). Read every 250 ms, the median of 8 readings drifted through that
  sawtooth, crossed the 80 ms tolerance and seeked when nothing was wrong; the same steps
  looked like ~200 ms jumps after every speed change. Now:
  - **Position from the audio output** (`AudioClock`, a `ForwardingAudioSink`): the playback
    thread asks it every few ms; each answer is kept with its exact time (minus the stream
    offset from `setOutputStreamOffsetUs`) and extrapolated. ±1 ms at a steady speed, and
    correct right after a seek.
  - **Speed on the AudioTrack** (`setEnableAudioTrackPlaybackParams`): heard at once.
    ExoPlayer's own speed change only reaches what you hear after the ~400 ms already
    buffered (measured: 12–31 ms behind after a 3% change); on the AudioTrack it's within
    ±15 ms, with one ~30 ms blip in the reading right at the change.
  - **Clock offset followed gently**: a new estimate from the pings moves the target by at
    most 2 ms per reading (every 250 ms), so it becomes a speed change, not a seek; more than
    500 ms is taken at once.
  - Tested in a party room on the emulator next to a browser listener: after the start (two
    seeks for a slow start, then 6 s at 0.97×), no correction at all for over 3 minutes, and
    none after a pause and resume. See `android/.../SyncCorrection.kt`.
- **Browser and Android:** play 3% faster or slower (pitch kept) when more than 40 ms off, back
  to normal below 10 ms, seek above 400 ms. Listening alone, only more than 1.5 s is fixed.
  The party screen on a Chromecast syncs loosely instead (`looseCorrection`): no speed
  changes, a jump only beyond 1 s, then 8 s to settle, since every jump makes it buffer.
  See `frontend/src/lib/sync.ts`.
- **Both:** add the device's **sync adjustment** (per device, ±500 ms) for speaker and
  Bluetooth delay, which no player can see.

## Starting a song together

A new song's clock used to start as soon as the host had its stream. Every player then still
had to load it (a phone in a server room first looks the song up itself, a Chromecast buffers
for a second or two) and seeked to where the song "already was": the first seconds were
skipped, most on a TV. Now a new song waits for the players, like Sonos or Spotify Jam:

- After `StreamReady`, the room is **starting** (`PlaybackStatus.starting`, `RoomView.starting`):
  `playing` is false and the position stays at the start. Players load the song paused there
  and say so: `PlayerReady(itemId)` from a participant's player, `PlayerReady(itemId,
  speakers = true)` from a phone once all its speakers near it have it
  (`NearbySpeakers.outputReady`), and `OutputRoom.outputReady` from the host's own Chromecasts
  and Sonos speakers. A player that can't play the song says so too (`PlayerReady(itemId,
  failed = true)`), so nobody waits for it; the Android app says so only after its retries.
- When everyone has answered, the song starts for all of them at that moment
  (`Room.startWhenReady`, `startTogether`); they start within the time a message takes, and
  the usual sync takes it from there. Once one player has it, the room waits at most
  `RoomSettings.startWaitSeconds` (2 s by default, 0–10; 0: it starts with the first) for the
  others, which then join late. Before that it waits as long as it takes: a lone player that
  started late came in seconds into the song (the clock had run without it).
- **Nobody can play it** (every player answered `failed`): the song is skipped
  (`Room.playersFailed`), with a notice; after three such songs in a row the room pauses
  instead, like when the host's own lookups fail.
- **Who's waited for:** online participants listening with `SetListening(startsTogether =
  true)`, phones with `SetSpeakers(startsTogether = true)`, and the host's active outputs
  (not a TV showing the party screen: its page is a participant). Older apps don't send the
  flag (the page asks the app for bridge version 9), so they're not waited for. Someone who
  stops listening while the room waits isn't waited for either (checked every 100 ms).
- A song's length of 0 means unknown, not "ends at 0": seeks (`Room.withinSong`, the web
  app's prediction) and the position a player aims for (`SyncCorrection.expectedPosition`)
  are only kept within a known length. Clamped to 0, an hour-long DJ set without a length
  went back to its first second every couple of seconds.
- Pausing or seeking while it waits ends the wait. The song ends by its length counted from
  when it started, not from when its stream was ready. Pages show a starting song as playing
  (and loading), and so a song whose stream is still being looked up
  (`PlaybackStatus.loading`).
- The browser loads it paused and reports at `canplay`; Android (`PlaybackService.reportReady`)
  at `STATE_READY`, over its own room connection; a Chromecast after `LOAD` (exactly at the
  start, without the usual 1 s lead); Sonos after `SetAVTransportURI`, playing when the room
  starts.

### A stall in the middle of a song

A player that runs out of data mid-song used to leave the room's clock running: the progress
bar moved on, and the phone caught up afterwards (the room followed it for up to 10 s, else the
phone jumped ahead and skipped what it hadn't heard). Now, when it is the **only player**
(`players() == [its id]`; the browser and the app also check `listeningAlone` first) and still
has no data 600 ms after it ran dry, it sends `PlayerStalled(itemId, positionMs)`. The room sets
its clock to that position and waits like for a new song (`starting`, so pages show playing and
loading); the player pauses there and says `PlayerReady` when it has data (the same code path as
a new song: `reportReady` / `reportStall` in `PlaybackService`, `reportStall` in `player.svelte.ts`).
The wait ends after `Room.STALL_WAIT_MS` (30 s) if the player never answers. With other players,
nothing changes: they go on and the stalled one catches up as before.

For sub-millisecond multi-room sync, the plan is a [Snapcast output](../features/future-ideas.md#snapcast-output)
rather than building it into YTMP.

## Skip silence

`PlaybackTarget.skipSilence` (the page's `saved.skipSilence`) turns on ExoPlayer's
`skipSilenceEnabled` for an episode when `alone`. `AudioClock` reads the audio sink's media
position, which counts the skipped silence, so the device runs ahead of the room; the existing
alone rule (`correct`: drift over `ALONE_ROOM_TOLERANCE_MS` and up to `MAX_ROOM_FOLLOW_MS` makes the
room `Seek` to this device, and `followRoomJump` sees it follow) keeps the room up with it. Not
verified on a device yet.


## Known problem: Windows media controls freeze Firefox

Found on Windows 10 22H2, Firefox 159 (2026-11): the Firefox window freezes after 10–30
minutes of playing; the sound goes on. Traced with the process logger, a dump and Windows' wait
chains (`GetThreadWaitChain`):

1. Firefox reports a media element's state to Windows' System Media Transport Controls (SMTC)
   by itself, for any long, audible element, whatever the page does
   (`HTMLMediaElement::IsControllableMediaSource`). Play, pause, every seek and every
   `playbackRate` change sends a timeline update (`NotifyMediaPositionState` →
   `WindowsSMTCProvider::SetPositionState`); `mediaSession.playbackState` only does when it
   changes (`MediaSession::SetPlaybackState` ignores the same value).
2. Each update fires `MediaPlaybackDataChanged` on a thread pool thread
   (`Windows.Media.MediaControl`), a COM call into **explorer.exe**'s Now Playing manager
   (`NPSM!CNowPlayingSession` / `CMTCEventProxy::OnMediaPlaybackDataChangedEvent`), which
   passes it on to the **AVCTP service** (`BthAvctpSvc`, its own svchost).
3. `BthAvctpSvc` stops answering (seen twice, with no Bluetooth device connected, with both
   Windows' generic and Realtek's own driver for an RTL8821CE). Every call then waits forever:
   within a minute ~770 threads wait in Firefox and in Explorer (`LpcReply`), and Firefox's
   window hangs a few minutes later.
4. Ending the service (`taskkill` on its svchost; `Stop-Service` hangs) released every waiting
   call at once and Firefox answered again; it's disabled since.

YTMP can't avoid it: sending fewer updates (fewer sync corrections) only delays it, and keeping
Firefox off the SMTC would cost media keys and headphone buttons. Fix and workaround for users:
[features/playback-and-outputs.md](../features/playback-and-outputs.md#known-problems).
Worth reporting: to Mozilla (a hung SMTC subscriber shouldn't freeze the window) and to
Microsoft (Feedback Hub; Windows 10 no longer gets fixes).
