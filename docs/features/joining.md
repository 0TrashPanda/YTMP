# Joining: Room Codes & Finding Hosts

## Room codes

Room codes are short and easy to **read out loud and type**. A server is expected to
host a handful of rooms, not millions, so codes can be short.

- **Case-insensitive** when typing.
- **No look-alike characters**: no `0/O`, `1/I/L`, `5/S`, `8/B`, `2/Z`.
- Configurable per server (see below).

### Default

4 letters, for example `KQFM`, `HWAR`, `TJXP`, `MECY`.
With the reduced alphabet (≈20 letters) that is ~160 000 codes, far more than needed.

### Configurable styles

| Style | Example | Config |
|-------|---------|--------|
| Letters (default) | `KQFM` | length (default 4) |
| Digits | `7342` | length (default 4–6) |
| Letters + digits | `K7QM` | length |

A code is never reused while its room exists.

### Your own code ✅

When you host a room you can **choose its code** instead (*Room code (optional)*, under the
room's name): 3 to 12 letters and digits, like `PARTY` or `TREESEE42`. Any letters work
here, look-alikes too, since you pick it yourself. If another room on that phone or server
has it, you're told it's taken.

**Changing it later** ✅: *Room settings → Overview → Room Code* (for people who may change
the room's settings). Everyone in the room follows along (their address and their saved
login for the room move to the new code), and the **old code keeps working**: links, QR
codes on paper and anyone typing it still end up in the room, also after a restart. A room
keeps leading from its last 10 codes, so another room can't take them.

## Joining a phone-hosted room on the local network

There are three ways to find a phone host, with automatic discovery as the main one:

1. **Automatic discovery (main)** ✅: the app lists public rooms on the same network
   (*Nearby rooms*). Tap one to join, with no code or IP needed. (Not tested between two
   real phones yet.)
2. **QR code** ✅: the room's share sheet shows a QR code with its link. Scanning it with any
   phone camera opens it in a browser and joins the room. On an Android phone with the YTMP
   app, the link opens in the app instead (with *Continue in the browser* to stay there).
3. **Manual IP**: type the link (`http://<phone's IP>:8765/room/CODE`) in a browser. Planned:
   a field for it in the app.

> Browsers cannot discover devices on the network by themselves, so browser clients
> use the **QR code** or **manual IP**. The phone host also serves the web UI, so
> scanning the QR code with a normal camera app is enough to join from a browser.

A phone-hosted room can only be joined **from the same network**. To share a room over
the internet, use a server (or [clone](room-lifecycle.md#cloning-a-room) the room to one).

## Joining a server-hosted room

Open the server's website and enter the **room code**
(see [rooms](rooms-and-roles.md#via-a-ytmp-server-web)).
