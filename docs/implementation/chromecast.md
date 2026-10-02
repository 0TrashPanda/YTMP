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

## Sync

The Chromecast plays on its own clock, with a few seconds of buffering. It's controlled
like any output (play, pause, seek, volume), and the host keeps it roughly in sync through
the receiver's media status. It is not sample-accurate with client playback.

## Later

- A **custom Cast receiver** (a small web app that runs on the Chromecast) could show the
  [party screen](../features/ui.md#party-screen) on a TV.
