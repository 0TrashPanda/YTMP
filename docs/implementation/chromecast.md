# Chromecast Output Module

Feature: [host outputs](../features/playback-and-outputs.md#host-outputs).

## How casting works

1. The host **finds** Cast devices on the network (mDNS, `_googlecast._tcp`).
2. It tells the device to open the **Default Media Receiver** (Google's built-in player)
   and gives it a **URL** to play, plus title, artist and album art for the TV screen.
3. The Chromecast fetches the audio **from that URL itself**.

Because the Chromecast fetches the audio itself, the URL must be one it can reach and
play: the direct YouTube URL when possible, otherwise the host's proxy (see below).

## Implementation ✅ (milestone 4)

**Our own Cast client**, no Google Cast SDK: the `cast/` module (Kotlin, shared by the Linux
server and the Android app) speaks the Cast v2 protocol directly: TLS to port 8009, protobuf
`CastMessage` frames with JSON payloads, heartbeats, and the Default Media Receiver
(`CC1AD845`) for playback. Tested read-only against real devices, and end-to-end against a
simulated device (`host/src/test/.../FakeCastDevice.kt`).

- **Discovery:** mDNS `_googlecast._tcp` (TXT `fn` = name, `id`). Server: JmDNS, plus fixed
  devices in `ytmp.toml` (`[cast] devices = ["Bose=10.0.0.109"]`), because mDNS doesn't work
  inside Docker without `network_mode: host`. Phone: Android's NsdManager, only while hosting.
- **Driver** (`host/.../CastOutputs.kt`): one per room. For every Cast device the room plays
  on it launches the media player, loads the current song from the host's `/api/audio/…`
  (a little ahead of the room's position, since devices start late), follows play/pause,
  applies volume, and seeks when more than 2 s off. Switching an output off stops the player.
- **Audio: hybrid**, like phones. A device first gets the song's **direct stream URL** (saves
  the host the traffic). If it can't load that (`LOAD_FAILED`), or drops to `IDLE`/`ERROR`
  mid-song, it gets the host's `/api/audio/…` proxy for the rest of the session, continuing
  from the same position. Tested at home: the Bose ("Cast Lite") and a TV both play direct
  links. The proxy is still needed when the host's stream URLs are tied to another public IP
  (a server in a datacenter), when a URL goes bad early, and for local files and media servers.
- **Proxy URL:** the host's address on the device's network (our side of the Cast connection)
  plus its port. Override on the server with `[cast] audio_base_url` (e.g. behind Docker
  without host networking).
- **Volume** follows the device: changes made elsewhere (its buttons, its app, Google Home)
  arrive as status messages and update the room's slider (rounded to 1%).
- The **song info** (title, artist, album art) is sent with the load command, so TVs and
  displays show it whether the audio is direct or proxied.
- Both proxies fetch YouTube in 1 MB ranged pieces: Cast devices ask without a range, and
  YouTube throttles un-ranged requests.

## Another app takes the device

Casting another app (Spotify, YouTube, …) closes our media player and the connection. When the
driver reconnects to a device this room played on (`resuming`, or after loading a song in this
session), it first asks the receiver which apps run: another app than the Default Media
Receiver (or the idle backdrop) means it was taken over, so it doesn't launch again and calls
`Room.outputTakenOver`. Switching the output off forgets that, so switching it on again takes
the device.

## Sync

The Chromecast plays on its own clock, with a few seconds of buffering. It's controlled
like any output (play, pause, seek, volume), and the host keeps it roughly in sync through
the receiver's media status. It is not sample-accurate with client playback.

## The party screen on a TV ✅

*Play on → Show the party screen* (`SetOutput(screen = true)`, Chromecast outputs only) shows
the [party screen](../features/ui.md#party-screen) instead of Google's player. YTMP has no Cast
app of its own (that needs a registration with Google): it uses **DashCast** (app id
`84912283`, namespace `urn:x-cast:com.madmod.dashcast`), an existing receiver that opens any
URL in a frame, as Home Assistant's pychromecast does (`CastWebPage` in cast/).

- The room adds a screen participant for the output (named after the device, no role, but
  allowed to *play on its own device*), and `RoomView.screens` gives the driver its guest
  token. `CastSession.showScreen` starts DashCast (waiting until its namespace is up) and
  sends `{ url, force: true }` with `<base>/tv#cast=CODE.TOKEN`.
- `force`: DashCast replaces its own page with ours instead of showing it in its frame. In
  the frame the page shows but can't play sound by itself (tested on a TV and a Nest Hub:
  `play()` is refused, as the frame has no autoplay permission, and nobody can tap a TV).
  DashCast no longer hears messages after that, so showing another page stops DashCast and
  starts it afresh.
- A device runs one app at a time, so DashCast replaces Google's player: the `/tv` page plays
  the music itself (the browser player, in sync, at full volume; the device volume is the
  room's slider, as before). It joins with the token directly (`RoomConnection.guestToken`).
- The base address: a server uses its public address (`accounts.url` / `YTMP_URL`) when
  that's `https`; otherwise (and on a phone) the address the device reached us at.
- Every 10 s the driver checks that DashCast still runs; another app instead means someone
  else uses the TV (the room lets go, as with music). Switching back to music only, or off,
  stops DashCast and the screen participant leaves.
- Still to see on a real TV: that the page plays sound as the whole page, and that the TV
  keeps DashCast running once its own page is gone.

### From the app, in a room on another host ✅

A server can't reach the Chromecasts at your place, the phone can (`CastFinder`). In a room
on another host, the app's *Play on* lists the phone's Chromecasts that aren't the host's
outputs (same `cast:<id>`), with a switch for the party screen:

- The page asks the room for a screen participant: `POST /api/screens/cast`
  `{ roomCode, guestToken, name }` (needs `listen_locally`, max 5 screens) answers
  `{ roomCode, guestToken }`, a screen named after the TV that may play.
- Then bridge v7 `showCastScreen(deviceId, url)` with the page's own
  `<origin>/tv#cast=CODE.TOKEN` (the app only takes its own page's origin); `CastScreens` (host/)
  starts DashCast there, keeps the connection open and checks every 10 s that it still runs.
  The answer comes as `onCastScreenShown(deviceId, error)`; a failure unlinks the screen again.
- `castScreens()` / `onCastScreens` give the page the phone's devices and what it shows.
- Switched off (`stopCastScreen`), another app on the TV, or 3 failed checks (TV off): the app
  stops it and sends `POST /api/screens/unlink { roomCode, guestToken }` itself, so the screen
  leaves the room.
- Volume: the TV's own (remote, buttons); the page plays at full volume.

## Later

- Our own registered Cast receiver, if DashCast goes away or doesn't play well.

## The device's own skip buttons

A Cast device gets a small queue of its own (QUEUE_LOAD): the previous song, the current one
and the next one, each tagged with our item id in `customData`. Neighbors use the host's
`/api/audio` URL (their direct URLs aren't resolved yet). So the device's own previous/next
(its buttons, its app, Google Home, a TV remote) work:

- When a status shows the device on a neighbor, the room follows (`Room.outputSkipped`):
  next = skip (or "played" within 5 s of the end), previous = jump back. That song isn't
  loaded again.
- When the room's previous/next change (songs added, a skip in the room), the device's
  queue is updated without interrupting the song (QUEUE_GET_ITEM_IDS, QUEUE_REMOVE,
  QUEUE_INSERT). A change of the current song in the room still loads the queue again.

Tested with the simulated device; not yet on real hardware.
