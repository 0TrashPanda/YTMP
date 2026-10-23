# Playback & Outputs

Audio can be played in two kinds of places: on **host outputs** and on **clients**.

## Client playback

**Any client can play the room's audio** on its own device, unless the room's
permissions say otherwise (permission: *Play audio locally*).

- All playing clients stay **in sync** with the room's playback position.
- **A new song starts together** ✅: it waits until every phone, browser, TV and speaker
  playing the room has loaded it, then starts for all of them from the beginning, so nobody
  misses its first seconds. How long it waits at most is a room setting (*Starting a song*:
  2 s by default, up to 10 s, or not at all); a slower one joins late.
- This is the main way to hear a **server-hosted** room, because the server may be a
  remote machine without speakers.
- Each client can turn local playback on or off at any time, and controls its own
  local volume.
- **Solo rooms play on your device by default** ✅ when you open them (in a browser, from
  your first tap or key press, since browsers only allow audio after one). Switching
  *this device* off in a solo room is remembered for that room. Another app taking the
  audio stops it for now but doesn't count as switching off.
- **Next while paused** ✅ (*Settings → Playback*, per device): **Plays the next song**
  (default, like YouTube Music) or **Stays paused** (the next song loads, the room stays
  paused). Also for the notification's, lock screen's and keyboard's Next. It needs *Play /
  pause*; without it Next only skips. (`Skip` with `play`; an older host just skips.)
- **Headphones disconnected** (unplugged, Bluetooth gone) is a setting in the app (profile
  menu): **Pause** (default; pauses the room like YTM when you're the only one listening,
  otherwise stops playing here), **Stop here**, or **Keep playing** on the speaker.
- Listening **alone**, a device that starts a song late (buffering) doesn't jump ahead: the
  room moves to where the device is, so there's no audible skip.
- Other rooms remember your last choice in the app.
- **Your devices** ✅: see [below](#your-devices).
- **Where the audio comes from** ✅: straight from YouTube when possible. YouTube's links
  only work on the internet connection of the host that asked for them, so elsewhere:
  - **the app** looks songs up itself, on the phone, and plays them straight from YouTube;
  - **a browser** does the same with **your own YouTube Music module** on your computer, if
    you set one up (*Settings → Playback*: its address and key);
  - otherwise, or when that fails, the host streams the song to you (if the server allows
    that on your network, see [server admin](server-admin.md)).
  - When YouTube won't send a song for a while, the app tries it again a few times and the
    player says so, instead of staying quiet.

### Your devices

Signed in with your account on more than one device (a phone, a laptop) in the same room?
*Play on* lists your **other devices that play the room** under *Your other devices*, by
their name ("Pixel 8", "Firefox on Windows"), each with its own **volume slider**. Turn the
laptop down from your phone, like Spotify Connect.

- On a phone with the app, it's the phone's **media volume**, the one its volume buttons
  change; the slider moves along when someone presses them.
- In a browser it's the page's own volume slider.
- Only your own devices: someone else's, or a guest's, isn't listed. It needs no permission.
- Only devices in the same room. A device on an older version of YTMP shows without a slider.

## Host outputs

The host can also send audio to **output modules**. Outputs are **modular**.

| Output | Description |
|--------|-------------|
| Local device | The host's own speakers. On a phone host the phone plays as a client (*this device*); a server's own speakers: planned |
| Sonos ✅ | Streams to Sonos speakers over HTTP (see [implementation](../implementation/sonos.md)) |
| Chromecast ✅ | Casts to Chromecast / Google Cast devices: TVs, Nest speakers, speaker groups (see [implementation](../implementation/chromecast.md)). A TV or display can show the [party screen](ui.md#party-screen) while it plays |
| MPD ✅ | Plays on MPD (Music Player Daemon), e.g. a Raspberry Pi on a speaker or an amplifier, wired or over Bluetooth (see [implementation](../implementation/mpd.md)) |
| Amplifier zones ✅ | The rooms of a multi-room amplifier, each a speaker of its own, with a player (an MPD) on one of its inputs (see [below](#amplifier-zones) and [implementation](../implementation/zones.md)) |
| Snapcast | Future idea: precise multi-room sync through a Snapcast server (see [future ideas](future-ideas.md#snapcast-output)) |
| Discord bot | Future idea: plays into a Discord voice channel (see [future ideas](future-ideas.md#discord-bot-output)) |
| … | Future output modules |

Outputs like Sonos, Chromecast, MPD and amplifier zones must be on the **same network as the host**. A phone
host or a home server can use them, a server in a datacenter can't.

*Play on* lists the speakers and TVs that are there now: one that's switched off, or that's
on the network your phone just left (another Wi-Fi), goes from the list. If the room was
playing there, it's switched off with a notice.

**Speakers near your phone** ✅: in a room on another host (a server), the app's *Play on*
also lists the Sonos speakers, Chromecasts, MPDs and amplifier zones on your phone's network that the host doesn't
offer itself, under *Near this phone*. Switched on, the phone plays the room there: it
follows the room, looks the songs up itself and serves them to the speaker, so it works
wherever the server is. They have their own volume slider, and a Chromecast TV can show the
[party screen](ui.md#party-screen) instead. It needs *play on your own device*; the room
keeps playing while they play, and everyone sees *plays on Woonkamer* next to your name.
The phone must stay on that Wi-Fi with the app running (it shows a notification, like
hosting); when it goes away, so do its speakers.

**Amplifier zones** ✅: a house with a multi-room amplifier and a player on one of its inputs
(say a Raspberry Pi) describes them once, in a file on its own network. Every phone or home
server on that Wi-Fi then lists each room of the amp in *Play on* (*Keuken*, *Zithoek voor*),
with nothing to set up. Switching a room on switches it to the player on the amp; several
rooms play the same music together. Each has its own volume slider, which is that room's
volume on the amp. Switched off, the room goes back to what it had (the TV, muted or not), and
so it does when the music has been paused for 10 seconds: then it stays on in *Play on* and
switches to the player again when the music plays.
Picking something else for a room on the amp itself switches it off in YTMP. One player plays
one room's music: when another room switches on one of its zones, it gets the player, also
a room on another phone. Either way, when the last one is done the amp's rooms go back to
what they had before the first. A room can be left out of *Play on* (say the amp's
headphones).

Changing host outputs is a permission (see [room management](room-management.md#permissions)).

When **another app takes a speaker or TV** (someone plays Spotify on the Sonos, or casts to
the TV), the room lets go of it: it's switched off in the room, everyone sees *"Kitchen is
playing something else now"*, and the room doesn't take it back at the next song (it also
doesn't stop the other app). Switching it on again in *Play on* takes it back.

Implementation: [implementation/playback-sync.md](../implementation/playback-sync.md).

## Audio

> Planned. Built: songs follow each other without waiting for the next one to load (the
> host looks up the next song's stream ahead of time).

- **Loudness normalisation**: songs from different sources (YTM, local files, media
  servers) can differ a lot in volume. YTMP evens out the loudness so the volume does
  not jump between songs. Can be turned off.
- **Gapless playback**: no silence between songs (for example live albums or DJ mixes).
- **Crossfade**: optional fade between songs, with a configurable length. Off by default.

## Equalizer ✅

Changes the sound of **your own device's playback**, like a phone's or a car's equalizer. In
*Settings → Playback*, where YouTube Music has it.

- **Ten bands** (31 Hz to 16 kHz), each 12 dB up or down, and **presets**: Flat, Bass boost,
  Less bass, Treble boost, Less treble, Spoken word (for podcasts), Loudness, Pop, Rock,
  Hip-hop, Electronic, Acoustic, Classical. Moving a slider makes it *Custom*.
- Boosting turns the whole sound down by the biggest boost, so it doesn't distort.
- **Per device**: in the app, the phone's speaker, wired headphones and **each Bluetooth
  device** (headphones, the car, a Bluetooth speaker) have their own setting. Connecting other
  headphones switches to theirs at once; settings shows which one you're changing. A device
  you haven't set has it off. In a browser it's one setting for that browser.
- It doesn't change the room or anyone else's sound, and not the speakers and TVs under
  *Play on* (Sonos, Chromecast, MPD, zones): only what plays on this device.
- In a browser, with the equalizer on, songs whose own link doesn't allow it come from your
  own module or through the host instead (see [implementation](../implementation/playback-sync.md#equalizer-in-a-browser)).

## Skip silence in podcasts ✅

*Settings → Playback → Skip silence in podcasts* (the app, off by default) skips the quiet
parts of an episode. Only while you're the only one listening: the room's position follows
your phone, as it does when your phone starts a song a moment late. With others listening,
or music playing, nothing is skipped. Not tried on a phone yet (see
[playback sync](../implementation/playback-sync.md)).

## Sleep timer ✅

Stops the music automatically, for example when falling asleep. Like YouTube Music's, it's
in the ⋮ menu of the song that's playing (*Sleep timer*).

- Stop after a set time (5 / 10 / 15 / 30 / 45 minutes, 1 hour, or your own number of
  minutes), or **at the end of the current song** (*End of episode* for an episode).
- The volume fades out over the last 10 seconds.
- In a **solo room** it pauses the room. At the end of the song that means the next song,
  waiting at its start.
- In a **shared room** it only stops **your own device's playback**, and the room keeps
  playing for everyone else. So there it needs this device to be playing.
- One timer per device. While it runs, the full player shows how long is left (a moon on
  the player bar on computers); tap it to see it, change it or turn it off.
- In the app it keeps going with the screen off (the app's player runs it). In a browser
  it runs while the room's page is open.
- It fades this device's sound; speakers a solo room plays on just pause at the end.
