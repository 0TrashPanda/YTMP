# Networking: Discovery, Room Codes, Transport

Feature description: [features/joining.md](../features/joining.md).

## LAN discovery

- The phone host advertises itself with **mDNS / DNS-SD** as service type `_ytmp._tcp`.
  The TXT record contains the room name, host name, whether the room is public, and the
  protocol version.
- Android: `NsdManager` for both advertising and discovery.
- Solo/private rooms are **not advertised**.

## QR code

The QR code encodes a URL such as `http://192.168.1.23:8080/join?room=KQFM`.

- Scanned with the YTMP app → the app opens the room directly.
- Scanned with a camera app → the browser opens the web UI served by the phone host.

Ideally the app registers as the handler for these URLs, so a camera scan on a phone
with YTMP installed opens the app instead of the browser.

## Room code generation

- Random selection from the configured alphabet, then retry if the code is already in use.
- Unambiguous letter alphabet: `ACDEFGHJKMNPQRTUVWXY` (20 letters, without `B I L O S Z`).
- Unambiguous digits: `3 4 6 7 9`, or all digits when the style is digits-only (no letters
  to confuse them with).
- Normalise input: uppercase, and map look-alikes (`0→O`, `1→I`, …) before lookup.
- Server config:

  ```toml
  [room_code]
  style  = "letters"   # letters | digits | alnum
  length = 4
  ```

## Transport security on the LAN

Servers use a **secure WebSocket** (`wss://`). On a LAN with a phone host there is no
domain and so no trusted certificate. Browsers will warn about a self-signed certificate,
or refuse `wss://` to it.

Options:

| Option | Notes |
|--------|-------|
| Plain `ws://` / `http://` on the LAN, `wss://` on servers | Simple; LAN traffic is unencrypted |
| Self-signed cert, fingerprint in the QR code | The app can pin the fingerprint; browsers still warn |
| Application-level encryption on top of `ws://` | More work; the key could be shared through the QR code |

**Decision:**

- LAN: **plain `ws://` / `http://` by default**.
- LAN: optional **secure mode** with a **self-signed certificate**. Users accept the
  browser warning once. The app can pin the certificate fingerprint from the QR code.
- Servers: `wss://` with a real certificate.

## Default timeouts

| Setting | Default |
|---------|---------|
| No admin connected → prompt participants to clone the room | **5 minutes** |
| No admin **and** nobody connected → delete the room | **1 hour** |
| Solo room | Never deleted automatically, only when closed |
| "Always on" room | Never deleted |
| Client disconnects → their guest identity (name, role, songs) is kept for | **15 minutes** (recognised by cookie) |

All are configurable per server.
