# Networking: Discovery, Room Codes, Transport

Feature description: [features/joining.md](../features/joining.md).

## LAN discovery

- The phone host advertises each **public** room with **mDNS / DNS-SD** as service type
  `_ytmp._tcp` (`android/.../host/Nearby.kt`). The TXT record has `code`, `name` and `v` (the
  protocol version).
- Android: `NsdManager` for both advertising and discovery; the app's room list shows
  *Nearby rooms*.
- Solo rooms are **not advertised**.
- Not verified yet between two real phones (the dev PC's firewall blocks mDNS).

## QR code

The share sheet's QR code (and link) is the room's URL: `http://192.168.1.23:8765/room/KQFM`
for a phone host, `https://ytmp.example.com/room/KQFM` on a server (`ShareSheet.svelte`).
Scanned with a camera app, the browser opens the web UI served by the host.

**Opening in the app:** Android only opens an app for an `http(s)` link when the app is
verified for that domain (Android 12+), which can't be done for phone hosts (IP addresses)
or other people's servers. So the link opens in the browser first, and the page hands it
over (`frontend/src/lib/openInApp.ts`, `+layout.svelte`): in an Android Chrome-based browser
(not a WebView), a `/room/CODE` page goes to
`intent://open?url=<link>#Intent;scheme=ytmp;package=dev.trashpanda.ytmp;S.browser_fallback_url=<link?web=1>;end`.
With the app, `MainActivity` gets `ytmp://open?url=<link>` and shows the link if it's one of
its pages (a private address, your server, the account server), else sends it back to the
browser with `web=1`. Without the app, the browser loads the fallback, and `web=1` keeps it
there. Meanwhile the page shows *Opening in the YTMP app…* with buttons to open the app (in
case the browser wants a tap) or continue in the browser; it doesn't join the room.

## Room code generation

- Random selection from the configured alphabet, then retry if the code is already in use.
- Unambiguous letter alphabet: `ACDEFGHJKMNPQRTUVWXY` (20 letters, without `B I L O S Z`).
- Unambiguous digits: `3 4 6 7 9`, or all digits when the style is digits-only (no letters
  to confuse them with).
- Normalise input: uppercase, and map look-alikes (`0→O`, `1→I`, …) before lookup.
- Server config:

  ```toml
  [rooms]
  code_style  = "letters"   # letters | digits | alnum
  code_length = 4
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

- LAN: **plain `ws://` / `http://` by default** (built).
- LAN: optional **secure mode** with a **self-signed certificate**. Users accept the
  browser warning once. The app can pin the certificate fingerprint from the QR code. (Not
  built yet.)
- Servers: `wss://` with a real certificate (Caddy or your own reverse proxy, see
  `deploy/`).

## Default timeouts

Built (`RoomTimeouts` in `core/.../RoomManager.kt`):

| Setting | Default |
|---------|---------|
| Public room with nobody connected → deleted | **1 hour** |
| Solo room on a phone, or owned by an account | Never deleted automatically, only when closed |
| Solo room of a guest on a server, nobody connected → deleted | **30 days** |
| Party room on a server without its owner or a top-role person connected → everyone is told they can clone it | **5 minutes** |
| "Always on" room (a setting, servers) | Never deleted |
| Client disconnects → their guest identity (name, role, songs) is kept for | **15 minutes** (recognised by their guest token) |

Not configurable in `ytmp.toml` yet.
