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
- Host broadcasts `{songId, positionMs, hostTimeMs, playing}`. A client computes
  `expectedPos = positionMs + (now + offset − hostTimeMs)`.
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

Local files, media server songs and cached songs are always served by the host.

## Sync accuracy

Where small desyncs between devices come from, and what addresses them:

| Cause | Size | Fix |
|-------|------|-----|
| Output delay (speakers, and especially Bluetooth) is invisible to the player | 20–300 ms | Per-device **sync adjustment** (manual), later set automatically by [mic sync](../features/future-ideas.md#automatic-sync-with-the-microphone) |
| Drift tolerance: each device only corrects above a threshold | up to 2× the threshold between two devices | Small threshold with gentle, proportional speed changes |
| Clock offset estimate | ~10–30 ms on Wi-Fi | Best-of-N ping samples (already done) |

Implemented (A + B), and tested on a Pixel 7:

- **Android (ExoPlayer):** constant speed, drift fixed with a **seek**. Every speed change
  makes ExoPlayer's reported position jump ~200 ms (audio already buffered at the old
  speed), so speed-based correction swung back and forth. The position readings also
  jitter ±120 ms, so decisions use the **median of 8 readings** (2 s). Seeks go slightly
  ahead of the target by a learned lead (playback resumes a moment after a seek). Result:
  within about ±50 ms of the room after a few seconds, with a seek about once a minute.
  See `android/.../SyncCorrection.kt`.
- **Browser:** plays 3% faster or slower (pitch kept) when more than 40 ms off, back to normal
  below 10 ms, seeks above 400 ms. See `frontend/src/lib/sync.ts`.
- **Both:** add the device's **sync adjustment** (per device, ±500 ms) for speaker and
  Bluetooth delay, which no player can see.

For sub-millisecond multi-room sync, the plan is a [Snapcast output](../features/future-ideas.md#snapcast-output)
rather than building it into YTMP.

