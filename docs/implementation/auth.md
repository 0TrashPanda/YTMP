# Authentication

> Status: **implemented**: accounts, login, signed host tokens, trusted servers, phone hosts
> linked to a server, role templates, listening history, log in once, accounts only, and the
> YouTube Music sign-in. Not yet: YTMP playlists, linked media servers, and room transfer
> data on the account.

Feature description: [features/accounts.md](../features/accounts.md#auth-servers).

## Baseline

- Every YTMP server can be an **auth server** with its own accounts.
- A host (server room or phone) is configured with an auth server.
- A user can join with their account if the host uses the **same auth server**, or one
  that **trusts** the user's auth server.
- The host and the auth server should verify each other, for example with a
  **pre-shared key** set up when the host is linked to the auth server.

## Chosen: signed tokens (federation)

Instead of the host asking the auth server about every user:

1. Accounts are identified as `user@auth-server` (like e-mail, Matrix or Mastodon).
2. The user logs in at **their own** auth server and gets a **signed token** (for
   example a JWT) scoped to the room they want to join.
3. The host verifies the signature with the auth server's **public key**, fetched
   once and cached. It doesn't need a shared secret or a live connection per join.
4. Each host keeps a list of **trusted auth servers** (by default just its own),
   so accounts from other servers can join if the host allows them.

Benefits: works across multiple servers, phone hosts only need the public key (they
can even verify offline once it is cached), and the user's password never reaches
the host.

The pre-shared key is then only needed for privileged host ↔ auth server actions
(for example storing role templates or transfer data), not for verifying users.

## How it works now

### On the auth server (a YTMP server)

- Accounts: username (2–32 of `a-z 0-9 . _ -`, case-insensitive) + password (8+ characters,
  PBKDF2-HMAC-SHA256, 600 000 iterations, JDK only). Account ID: `username@issuer`.
- **Issuer**: `accounts.name` in `ytmp.toml`, or else the host name of `accounts.url`, or of
  the machine, remembered in the database on first start so it doesn't change.
- **Sign-up** (`accounts.signup`): `open`, `invite` (default) or `admin`. The first account on a
  fresh server can always be created and becomes the **server admin** (`accounts.is_admin`;
  there can be several, never none). Admins manage accounts and invite links on the admin
  page (`/api/admin/*`, `AdminApi.kt`). Sign-up mode, guests, the tracking default and the
  trusted servers can also be set there: `ServerSettings.kt` keeps what the admin set in the
  `settings` table (`server_settings`, JSON), over `ytmp.toml`, and applies changes live.
- Logging in on the server's own pages gives a **session** token (random, stored hashed),
  sent as `Authorization: Bearer`. Five wrong passwords for a username → 15 minutes wait.
- The token signing key (ES256 / P-256) is made on first start and kept in the database.
  `GET /api/auth/info` publishes the public key.

### Joining a room with an account: the `/connect` page

The password is only ever typed on the auth server, never on a host's page (a phone host
serves its own JavaScript, so it could read anything typed there):

1. A host's page (`HostInfo.authServers` lists who it trusts) sends you to
   `<auth server>/connect?return=<this page>`.
2. There you log in (once per browser), and confirm *"Join rooms on <host> as <name>?"*. The
   server's own pages skip that question.
3. The auth server signs a **host token** (JWT, ES256): `iss`, `sub` (username), `name`,
   `aud` = the **origin** of the returning page, 30 days. It goes back in the URL fragment
   (`#ytmp-connect=…`), which never reaches a server; the page keeps it and removes the fragment.
4. The page sends it in `hello.accountToken` (and as `Bearer` when creating a room, so the
   account owns the room on every device it logs in on).
5. The host checks the signature with the issuer's public key, the expiry, and that `aud` is
   **one of its own origins**. Not the request's Host header, which anyone can set: a
   rogue host could otherwise pass on a token made for its own page. A server's origins are
   `accounts.url` plus its own IP addresses; a phone's are its IP addresses and localhost.

A token that isn't accepted is ignored: you join as a guest, and `welcome.accountId` is null.

### Trust

- A server trusts itself and the servers in `accounts.trusted` (URLs). It fetches their
  `/api/auth/info` on start (and retries until it works).
- A phone has no accounts. On its home screen it can be linked to one YTMP server
  (`PUT /api/host/auth-server`, phone only), which it then trusts. The link and the public
  key are kept, so tokens are checked without reaching the server.

### Role templates

An account's role template (roles + default roles) is stored on its auth server
(`account_data`), edited on `/account` (`GET`/`PUT /api/account/role-template`). When a
room is created with a host token, the host asks the account's auth server for it
(`GET /api/auth/role-template`, `Authorization: Bearer <host token>`; a server reads its own
accounts' templates directly). If that fails, the room gets the default roles.

### Listening history

When a song finishes (played to the end, or skipped after it started), the room tells the
host's `PlayReporter`, which sends a `PlayReport` to the auth server of **every account
holder online in the room** (`POST /api/auth/plays` with that person's host token; a server
stores its own accounts' plays directly). The auth server keeps it only if that account has
tracking on (`accounts.tracking_default` in `ytmp.toml` sets it for new accounts; default
off). "Listened with" leaves out people who opted out: accounts by their setting (carried
in their host token as `hide`, so it applies from their next login on a host), guests by a
checkbox (`hello.hideFromHistory`).

Known limits: a host holding your token could add made-up plays to your history; and if the
auth server can't be reached when a song ends (a phone without internet), that play is lost
(no retry queue yet).

### Your history on other pages

The room's *Library → History* reads the history from the account's auth server, with CORS
(`GET /api/account/history`, `DELETE /api/account/history/{id}`; export and deleting days need
a login there). A host token alone isn't enough: every host you join has one. What works
(`historyAccount` in `AccountApi.kt`, `frontend/src/lib/listening.svelte.ts`):

- **The server's own pages**: the login session.
- **The app's own pages** (`http://127.0.0.1:8765`, the same on every phone): the host token
  made for that origin, which the app got from your server's pages (log in once). A token for
  that origin only ever reaches your own phone, so it may read your history. Tokens made for
  the server's own origins likewise.
- **Other hosts' pages**: a token with `scope = "history"`, from `/connect` together with the
  YouTube Music one (the *Also use my YouTube Music and history there* checkbox), kept in the
  page (`ytmp.historyFrom`) and sent only to your server, never to the host.

Episode progress (`/api/account/episodes`, the podcast pages' played marks) works the same.
Hosts report it as episodes are paused, skipped or end (`POST /api/auth/episodes`, any host
token of the account, like plays), but reading it back to resume an episode
(`GET /api/auth/episodes/{id}`) takes a token that only reaches the account's own devices.

Your preferences work the same too, so they're the same in rooms everywhere
(`GET`/`PUT /api/account/preferences`, kept as `preferences` account data): one JSON object in
which each feature of the web app has its own keys (`songMenu`, …). A PUT sets only the keys it
has (null removes one), so features don't overwrite each other; at most 64 KB.
`frontend/src/lib/preferences.svelte.ts` keeps a copy in the browser (also without an account),
takes the account's when it answers, and puts up what only the browser has. Coming back to
the page (`visibilitychange`, at most every 10 s) asks the account again, so a change made on
another device shows up; what came in is announced with a `ytmp:settings` window event (the
app's player and Home follow it).

Settings (`frontend/src/lib/settings.svelte.ts`) are preferences with a device override: each
`Setting` has its key in the preferences (the account's value), and a device that keeps its own
has it in `localStorage['ytmp.deviceSettings']` instead (`keepHere`). Its value is this device's,
else the account's, else the default. Settings that used to be kept in the browser only (their
old `ytmp.*` keys) were collected once into `ytmp.settingsToMove`; once the account answers
they go up when it has none for that key, or become this device's own when it has a different
one (without an account they just stay in the browser).

The same rule decides how much of the [song graph](storage.md#generating-a-radio) a host
gets: `GET /api/auth/radio?id=` takes any host token of the account, but only those for the
app's pages or the server's own include the account's own listening.

### Log in once

- **Server pages** (`logInOnce` in `frontend/src/lib/account.ts`): with a login session on the
  server's own pages but no identity (or one for another account, or close to expiring), the
  page asks `/api/account/host-token` for its own origin, without `/connect`.
- **In the app**: that page also gets a host token for the phone's own pages
  (`http://127.0.0.1:8765`) and hands it to the app (bridge v4 `shareIdentity`, at most every
  12 h per session). The app only takes it from the linked auth server's pages, keeps it in
  memory, and the phone's pages pick it up once (`takeIdentity`, only on the phone's own
  origin; `identity.takeFromApp` in the layout). The other way needs nothing new: logging in on
  the phone's page goes through the server's `/connect` in the same WebView, which keeps the
  session there.

### Default room password

`PUT /api/account/room-password` keeps the account's default room password, hashed
(`account_data` `room_password`, `RoomPasswords` in core); `AccountSettings.hasRoomPassword`
says whether there is one. The role template a host gets (`/api/auth/role-template`) carries
the hash as `roomPassword`, and new rooms of that account start with it.

### Logging in on another host's pages

- `connectQuietly` (`frontend/src/lib/account.ts`, run by the layout before the page shows): on
  a host's page without an identity, whose auth server has a `url` on another origin, once per
  browser session (and not for a week after *Not now*, and not on the app's own pages), it
  checks the server answers (`no-cors` fetch, 2.5 s) and goes to `<server>/connect?quiet=1`.
- `/connect?quiet=1`: logged in there and the host's origin is one you said yes to before
  (`ytmp.approvedHosts` in that browser, per account) → straight back with a host token.
  Logged in but not yet approved → the usual question, with *Not now, join as a guest*. Not
  logged in → back at once. It answers in the fragment: `#ytmp-connect=<token JSON>`, `none`
  or `declined`.
- The join form and the solo-room refusal have **Log in** buttons (the normal `/connect`).

### Your YouTube Music on other hosts

`POST /api/account/youtube-token` (login session) makes a token like a host token, with
`scope: "youtube"` (and no audience). `/connect` gets one when *Also use my YouTube Music
there* is ticked and returns it with the host token (`youtube: { server, token, expiresAt }`
in the fragment); the page keeps it as `ytmp.youtubeFrom`. When the host itself has no YouTube
Music for you, `youtube.load()` switches every `/api/me/...` request (and Home, as
`/api/me/home`) to your server with that token (`useYoutubeFrom` in `api.ts`).

- Your server answers those from other origins: `HostOptions.crossOriginPersonal` installs
  CORS on `/api/me`, and accounts-only lets preflights and such tokens through there.
- `AccountService.verifyYoutubeToken` only accepts them for: `GET /api/me/...`,
  `PUT /api/me/likes/...`, `POST /api/me/playlists` and `.../songs`. Everywhere else (host
  logins, `/api/auth/*`, room sync) tokens with a scope are refused.

### The phone's owner

The account the phone's own page proves (a bearer token on a loopback request: `/api/host`,
`/api/me/*`) is kept as the phone's owner (`AuthLink.ownerAccount`, `HostOptions.onLocalAccount`
/ `deviceOwner`). A request from another device with a host token of that account may use
the phone's YouTube Music sign-in like the phone itself (`personal()` in `HostContext.kt`).
Linking another auth server forgets the owner.

### Accounts only

`accounts.guests = false` (`YTMP_GUESTS`) → `HostOptions.accountsOnly`: a route-scoped plugin
answers 401 for every `/api` call without a valid host token, except `/api/host`,
`/api/auth/*`, `/api/account*`, `/api/admin/*` and `/api/audio/*` (Cast and Sonos fetch audio
without logging in; `[audio] proxy` still applies). `hello` without an accepted account token,
and `attach` for a participant without an account, get `Rejected(account_required)`.
`HostInfo.accountsOnly` makes the pages show only a login.

### YouTube Music sign-in on phone and server

- Phone → server: bridge `youtubeCookie` (only on your server's pages) offers the phone's own
  sign-in (`PhoneYoutubeAccount.shareable`) before Google's page; `phoneYoutubeAccount` lets the
  page say whose.
- Server → phone: `GET /api/account/youtube/cookie` returns the stored sign-in for a **login
  session only** (`AccountService.sessionAccount`), never for a host token, so hosts can't get
  it. The page `/account/youtube-app` (opened from the phone's Settings → YouTube Music or Library) hands it
  to the app with bridge `useYoutubeCookie`, which only accepts it from the linked auth server's
  pages and asks before signing in.
