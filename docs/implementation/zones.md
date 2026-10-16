# Amplifier zones

A multi-room amplifier plays one input in several rooms (zones), each with its own volume. With
a player on one of its inputs (an [MPD](mpd.md), say a Raspberry Pi), every zone is a speaker of
its own in *Play on* (see [playback](../features/playback-and-outputs.md)). Switching one on
switches that zone to the player on the amp; the player plays the room once, however many
zones are on.

The house describes its amp in a **zones file** on its own network, usually served by the Pi
next to it. YTMP has no code for any one amp: the file puts the amp together from general
building blocks. Any host on that network (the phone, or a server at home) finds the file and
offers the zones, with nothing to set up in YTMP.

## The zones file

JSON, e.g. for an AudioSystem 8.8 with the Pi on its input "computer"
([`deploy/zones/ytmp-zones.json`](../../deploy/zones/ytmp-zones.json)):

```json
{
  "version": 1,
  "player": {"type": "mpd"},
  "amp": {
    "base": "http://192.168.0.156/audio_set?output={zone}",
    "state": "{base}&settings=?",
    "stateAfter": "settings=",
    "ours": {"channel": 3, "muted": 0},
    "save": ["vchan", "muted"],
    "on": ["{base}&vchan=2", "{base}&muted=0"],
    "off": ["{base}&muted=1", "{base}&vchan={vchan}", "{base}&muted={muted}"],
    "volume": {"set": "{base}&volume={volume}", "field": "volume", "max": 50}
  },
  "zones": [
    {"zone": 1, "name": "Zithoek achter"},
    {"zone": 5, "name": "Keuken"}
  ]
}
```

- **`player`**: the MPD that feeds the zones: `host` (default: the machine that serves the
  file) and `port` (6600). That MPD isn't offered by itself any more, only as its zones.
- **`zones`**: each zone's `name` in *Play on*, and `zone`, the amp's own number or name for
  it, which the requests use as `{zone}`. `"hidden": true` leaves a zone out of *Play on* (say
  the amp's headphones output), like leaving it out of the list.
- **Requests** are web addresses (GET), with placeholders: `{base}` (the shared start,
  `base`, which may use `{zone}`), `{zone}`, `{volume}` and saved fields.
  - `on`: switch a zone to the player, in order.
  - `off`: switch it back. A request that needs a saved field that wasn't saved is left out.
- **`state`** (optional): a request whose answer is the zone's state as a JSON object; with
  `stateAfter`, the object comes after that text (this amp answers
  `/audio_set?output=5&settings={…}`). Fields can be nested (`a.b`). With a state:
  - **`ours`**: the fields that say the zone plays the player. Checked every 2 s; another
    value means someone switched the zone to something else on the amp itself: the zone is
    switched off in the room (*"Keuken is playing something else now"*) and left as it is.
  - **`save`**: fields saved when the zone is switched on (unless it plays the player
    already), and put back with `off`. So a room that was on the TV goes back to the TV,
    muted if it was.
  - **`volume`**: the zone's own volume, from `min` (0) to `max` (100) in the amp's steps:
    `set` sends it, `field` reads it. The room's slider starts at the zone's volume and
    follows changes made on the amp. Without it, the zone has no volume slider.

The file is fetched again every minute, so changes to it show up. A file that can't be read
(the Pi is off) keeps its zones for three tries, then they go; a broken file has none (the
host's log says why).

**Only the local network:** every request, and the player, must be on a private, link-local or
loopback address, so a file found on someone else's Wi-Fi can't make the host send requests
to the internet. The worst it can do is offer zones that don't work.

## Playing

`ZoneOutputs` (`host/`), one driver per room, every 2 s and on every change in the room:

- A zone switched on in the room: its state is saved and the `on` requests sent; then its
  state is checked (`ours`) and its volume kept in step.
- The player plays the room through the same MPD session as an MPD speaker (`MpdSession`, see
  [MPD](mpd.md)), once per player whatever the number of zones, at full volume: the zones have
  the volume. A new song waits until the player has it, for every zone.
- A zone switched off: `off`, with the saved fields. The player stops when the room has none
  of its zones on any more.
- **Two rooms on one host**: the room that switched on a zone of a player last gets the
  player. The other room's zones of it are switched off there (and back on the amp, unless
  the new room has them too), with the usual notice.
- **Another YTMP host** (say a second phone) takes the player: first it asks the host whose
  music plays there (the address in MPD's current song) which zones it has on and what they
  had before (`GET /api/zones/handover?host=…&port=…`). The zones it switches on too keep that
  state, so they go back to it when it's done; the others go back right away. So however many
  hosts take over in turn, the house ends up as it was before the first. The host that lost
  the player switches its zones off in its room and leaves the amp to the new one. It's asked
  while it's still playing (so a phone host is still awake and hosting); if it can't be asked
  (gone, or an older version), its zones are left as they are.
- **Another app** takes the MPD (it plays something that isn't a YTMP host's audio): the
  zones are switched off in the room and left as they are on the amp.

## Finding the file

- mDNS service `_ytmp-zones._tcp`, TXT `path` (default `/ytmp-zones.json`).
- **Phone:** `ZoneFinder` (NsdManager), like the other finders; the zones go when the phone
  is on another network.
- **Server:** JmDNS, plus fixed files in `ytmp.toml` (mDNS doesn't work inside Docker without
  `network_mode: host`):

```toml
[zones]
discovery = true
files = ["http://192.168.0.50:8766/ytmp-zones.json"]
```

## Setting up the Pi

The files are in [`deploy/zones/`](../../deploy/zones/): the zones file, a systemd unit that
serves it (Python's own web server, port 8766) and the mDNS announcement for Avahi.

```sh
sudo mkdir -p /etc/ytmp-zones
sudo cp ytmp-zones.json /etc/ytmp-zones/              # edit it for your amp and zones
sudo cp ytmp-zones.service /etc/systemd/system/
sudo systemctl enable --now ytmp-zones
sudo cp ytmp-zones.avahi.service /etc/avahi/services/ytmp-zones.service
```

MPD on the Pi plays into the amp's input (an ALSA output, e.g. a USB sound card). It needs no
mixer: YTMP sets it to full volume while zones play, and that holds for MPD's other enabled
outputs too.

## Not yet

- Requests other than GET (POST, a body, a password).
- Amps that don't speak HTTP (a serial port, telnet).

Tested against a simulated amp and MPD (`host/src/test/.../FakeAmp.kt`, `FakeMpd.kt`), and on
a real AudioSystem 8.8 with MPD 0.23 on a Raspberry Pi 5 (a USB sound card into the amp's input)
with the file in `deploy/zones/`: switching on and off, volume, and putting back the input.
