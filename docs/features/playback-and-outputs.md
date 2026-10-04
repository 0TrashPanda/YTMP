# Playback & Outputs

Audio can be played in two kinds of places: on **host outputs** and on **clients**.

## Client playback

**Any client can play the room's audio** on its own device, unless the room's
permissions say otherwise (permission: *Play audio locally*).

- All playing clients stay **in sync** with the room's playback position.
- This is the main way to hear a **server-hosted** room, because the server may be a
  remote machine without speakers.
- Each client can turn local playback on or off at any time, and controls its own
  local volume.
- **Solo rooms play on your device by default** ✅ when you open them (in a browser, from
  your first tap or key press, since browsers only allow audio after one). Switching
  *this device* off in a solo room is remembered for that room. Another app taking the
  audio stops it for now but doesn't count as switching off.
- **Headphones disconnected** (unplugged, Bluetooth gone) is a setting in the app (profile
  menu): **Pause** (default; pauses the room like YTM when you're the only one listening,
  otherwise stops playing here), **Stop here**, or **Keep playing** on the speaker.
- Listening **alone**, a device that starts a song late (buffering) doesn't jump ahead: the
  room moves to where the device is, so there's no audible skip.
- Other rooms remember your last choice in the app.

## Host outputs

The host can also send audio to **output modules**. Outputs are **modular**.

| Output | Description |
|--------|-------------|
| Local device | The host's own speakers (mainly relevant for a phone or home server host) |
| Sonos ✅ | Streams to Sonos speakers over HTTP (see [implementation](../implementation/sonos.md)) |
| Chromecast | Casts to Chromecast / Google Cast devices: TVs, Nest speakers, speaker groups |
| Snapcast | Future idea: precise multi-room sync through a Snapcast server (see [future ideas](future-ideas.md#snapcast-output)) |
| Discord bot | Future idea: plays into a Discord voice channel (see [future ideas](future-ideas.md#discord-bot-output)) |
| … | Future output modules |

Outputs like Sonos and Chromecast must be on the **same network as the host**. A phone
host or a home server can use them, a server in a datacenter can't.

Changing host outputs is a permission (see [room management](room-management.md#permissions)).

Implementation: [implementation/playback-sync.md](../implementation/playback-sync.md).

## Audio

- **Loudness normalisation**: songs from different sources (YTM, local files, media
  servers) can differ a lot in volume. YTMP evens out the loudness so the volume does
  not jump between songs. Can be turned off.
- **Gapless playback**: no silence between songs (for example live albums or DJ mixes).
- **Crossfade**: optional fade between songs, with a configurable length. Off by default.

## Sleep timer

Stops the music automatically, for example when falling asleep.

- Stop after a set time (15 / 30 / 60 minutes, or custom), or **at the end of the
  current song**.
- The volume fades out over the last few seconds.
- In a **solo room** it pauses the room.
- In a **shared room** it only stops **your own device's playback**, and the room keeps
  playing for everyone else.
