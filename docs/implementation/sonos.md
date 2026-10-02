# Sonos

Sonos speakers are room outputs next to Chromecasts (see [playback](../features/playback-and-outputs.md)).

## How it works

- Control is **UPnP over HTTP** on port 1400, the calls the Sonos apps use
  (`host/.../SonosPlayer.kt`): `SetAVTransportURI` (with DIDL-Lite metadata, so the Sonos app
  shows title, artist, album and art), `Play`, `Pause`, `Stop`, `Seek` (REL_TIME),
  `GetTransportInfo`, `GetPositionInfo`, and `GetVolume` / `SetVolume` (RenderingControl).
- The speaker fetches the audio from the host's `/api/audio/{songId}` proxy (which supports
  ranges, so seeking works). The host's address is the one the speaker reaches it on.
- `SonosOutputs` keeps each active speaker in step with its room, like the Cast driver: the
  room is in charge of the song, play/pause and volume; a speaker more than 3 s off (Sonos
  reports whole seconds) is seeked. Volume changed in the Sonos app or on the speaker shows
  up in the room.
- Discovery: the **phone** uses Android's NSD (`_sonos._tcp`); the **server** sends an SSDP
  M-SEARCH for ZonePlayers, which needs UDP multicast replies to get through (a firewall or
  Docker without host networking blocks them). Speakers can also be listed by IP address:

```toml
[sonos]
discovery = true
devices = ["192.168.68.100"]   # named after their Sonos room
```

## Not yet

- **Groups**: commands go to the speaker itself. A speaker that's part of a Sonos group
  answers "part of a group; play on the group's main speaker".
- The speaker's own skip buttons and the Sonos app's next/previous (no queue on the speaker).
- A pause pressed in the Sonos app is undone by the room (the room is in charge).

Tested on a Sonos Connect:Amp: start, pause, resume, seek, volume both ways, next song, stop.
