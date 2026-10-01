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

## Joining a phone-hosted room on the local network

There are three ways to find a phone host, with automatic discovery as the main one:

1. **Automatic discovery (main)**: the app lists YTMP hosts on the same network. Tap one
   to join, with no code or IP needed.
2. **QR code**: the host shows a QR code containing its address. Scanning it with the
   app, or with any phone camera to open it in a browser, joins the room.
3. **Manual IP**: type the host's IP address (and port).

> Browsers cannot discover devices on the network by themselves, so browser clients
> use the **QR code** or **manual IP**. The phone host also serves the web UI, so
> scanning the QR code with a normal camera app is enough to join from a browser.

A phone-hosted room can only be joined **from the same network**. To share a room over
the internet, use a server (or [clone](room-lifecycle.md#cloning-a-room) the room to one).

## Joining a server-hosted room

Open the server's website and enter the **room code**
(see [rooms](rooms-and-roles.md#via-a-ytmp-server-web)).
