# Authentication

> Status: **first slice implemented** (accounts, login, signed host tokens, trusted
> servers, phone hosts linked to a server). Not yet: guest bans by account, "accounts only"
> rooms, role templates and the other account data.

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
  fresh server can always be created and becomes the **server admin**. The admin makes
  invite links (once, 7 days) and accounts, and sets passwords, on `/account`.
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
