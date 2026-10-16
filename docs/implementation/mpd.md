# MPD

MPD (Music Player Daemon) players are room outputs next to Chromecasts and Sonos (see
[playback](../features/playback-and-outputs.md)). Typical setup: a Raspberry Pi with MPD that
plays on a speaker or an amplifier, wired or over Bluetooth.

## How it works

- Control is MPD's **text protocol over TCP** on port 6600 (`host/.../MpdPlayer.kt`), one
  short connection per call: `clear` + `addid <url>` (the room's song in place of MPD's own
  queue), `play 0`, `pause 1` / `pause 0`, `seekcur`, `stop`, `status` + `currentsong`,
  `setvol`, and `outputs` for the name.
- MPD fetches the audio from the host's `/api/audio/{songId}` proxy (which supports ranges,
  so seeking works), like Sonos. The host's address is the one MPD reaches it on.
- `MpdOutputs` keeps each active MPD in step with its room, like the Sonos driver: the room is
  in charge of the song, play/pause and volume. MPD reports its position in milliseconds, so
  it's seeked when more than 2 s off: right away when the room's own position changed (someone
  seeked), otherwise only once a song has settled (6 s after loading or seeking, while MPD may
  still be fetching it).
- **Volume** is MPD's own (`setvol`), and changes made in another MPD app show up in the room.
  An MPD output without a mixer has no volume: the room shows no slider then. For Bluetooth or
  ALSA outputs without one, `mixer_type "software"` in that output's block of `mpd.conf` gives
  MPD a volume.
- A song MPD can't fetch or decode shows up in its `status` (`error`): the room gets a notice
  and goes on; MPD stays quiet until the next song.
- Switching the output off stops MPD and empties its queue.

## Finding them

MPD announces itself with mDNS (`_mpd._tcp`, its zeroconf, on by default when it's built with
Avahi). The name is MPD's `zeroconf_name`; the default one ("Music Player @ host") says little,
so then it's the name of MPD's one enabled output (`audio_output { name "…" }`, e.g.
"Bluetooth Speaker"), else the host name.

- **Phone:** Android's NsdManager (`MpdFinder`), like the Cast and Sonos finders.
- **Server:** JmDNS, plus fixed players in `ytmp.toml` (mDNS doesn't work inside Docker
  without `network_mode: host`). A fixed one that's off when the server starts is added once
  it answers (checked every minute).

```toml
[mpd]
discovery = true
devices = ["Woonkamer=192.168.0.50"]   # "host", "host:port" or "Name=host[:port]"
```

## Another app takes it

Each check reads MPD's current song. Once a song has settled, a file that isn't the host's
`/api/audio/…` (someone plays MPD's own library, or another app queued something) means it's
taken: the driver lets go without `stop` and calls `Room.outputTakenOver`, which switches the
output off in the room with a notice.

## Not yet

- MPD with a password (`password` in `mpd.conf`).
- The next song in MPD's queue for gapless playback; MPD's own next/previous (an MPD app's
  skip buttons): the queue holds only the current song.
- A pause pressed in another MPD app is undone by the room (the room is in charge).

Tested against a simulated MPD (`host/src/test/.../FakeMpd.kt`); not yet on a real one.
