# Playback & Sync

Feature description: [features/playback-and-outputs.md](../features/playback-and-outputs.md).

## Approach

1. The host resolves the **stream URL** for the current song (on demand, through the
   source module).
2. The host sends the stream URL and the **playback state** (song, position, playing or
   paused, the timestamp of that state) to each playing client over the WebSocket
   (`wss://` on servers, `ws://` on the LAN by default, see [networking](networking.md)).
3. Clients play the stream themselves and correct drift from the periodic state
   updates.

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
- **Browser** (`WebPlayer`, `ownModule.ts`): a YTM module the listener runs on their own
  computer, set in *Settings → Playback* (`saved.ownModule`: address and key, checked with
  `GET /info`). The player calls `GET /songs/{id}/stream` there and prefetches the next
  song. The module answers pages from any origin only when it has a key (CORS, including
  Chrome's private network preflight; see [module API](module-api.md)).
- A lookup that fails (module unreachable, wrong key, unavailable) is reported to the host
  log and the song goes through the proxy.

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
  and Sonos speakers. A player that can't play the song says so too, so nobody waits for it.
- When everyone is ready, the song starts for all of them at that moment (`Room.startTogether`);
  they start within the time a message takes, and the usual sync takes it from there. The
  room waits at most `RoomSettings.startWaitSeconds` (2 s by default, 0–10; 0 doesn't wait)
  for a slow player, which then joins late, as before.
- **Who's waited for:** online participants listening with `SetListening(startsTogether =
  true)`, phones with `SetSpeakers(startsTogether = true)`, and the host's active outputs
  (not a TV showing the party screen: its page is a participant). Older apps don't send the
  flag (the page asks the app for bridge version 9), so they're not waited for. Someone who
  stops listening while the room waits isn't waited for either (checked every 100 ms).
- Pausing or seeking while it waits ends the wait. The song ends by its length counted from
  when it started, not from when its stream was ready. Pages show a starting song as playing
  (and loading).
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

