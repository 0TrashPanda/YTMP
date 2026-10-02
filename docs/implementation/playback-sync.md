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

Local files, media server songs and cached songs are always served by the host.

## Sync accuracy

Where small desyncs between devices come from, and what addresses them:

| Cause | Size | Fix |
|-------|------|-----|
| Output delay (speakers, and especially Bluetooth) is invisible to the player | 20–300 ms | Per-device **sync adjustment** (manual), later set automatically by [mic sync](../features/future-ideas.md#automatic-sync-with-the-microphone) |
| Drift tolerance: each device only corrects above a threshold | up to 2× the threshold between two devices | Small threshold with gentle, proportional speed changes |
| Clock offset estimate | ~10–30 ms on Wi-Fi | Best-of-N ping samples (already done) |

For sub-millisecond sync, see [precise sync mode](../features/future-ideas.md#precise-sync-mode-like-snapcast).

