# Storage

## Database

The server database is **configurable**: **SQLite** (default) or **PostgreSQL**.

```toml
[database]
type = "sqlite"                    # sqlite | postgres
path = "/var/lib/ytmp/ytmp.db"     # sqlite
# url = "postgresql://ytmp@localhost/ytmp"   # postgres
```

- SQLite is the default, because it's one file with no extra service and backups are easy.
  It's plenty for a group of friends.
- PostgreSQL is for people who already run it, or want it.
- To support both, queries stay in portable SQL, with no database-specific features. So far
  that is plain JDBC through one connection (`server/.../Database.kt`), with a small
  migration list of its own: a `schema_version` table and numbered steps that run in order
  on start. A SQL library (Exposed, jOOQ) or Flyway can still come later if queries grow.

### Tables so far

| Table | What |
|-------|------|
| `rooms` | One row per room, its state as JSON (see *Rooms* below) |
| `accounts` | `id`, `username` (unique, lowercase), `display_name`, `password_hash` (PBKDF2-SHA256), `is_admin` |
| `sessions` | Logins on the server's own pages: SHA-256 of the token, account, created / last used |
| `invites` | SHA-256 of the invite code, who made it, expiry, who used it |
| `settings` | Server values: the token signing key, the issuer name |
| `account_data` | Per account, as JSON: role template, settings (tracking, hide from others, opening rooms elsewhere, what radio learns from), the linked YouTube Music sign-in (encrypted, see `YoutubeLinks.kt`) |
| `plays` | Listening history: one row per song heard (song as JSON plus title/artists for search, when, heard ms, skipped, room, added by me, listened with as JSON, shared), indexed by account + time |
| `room_copies` | Copies of accounts' rooms that live on another host (a phone): sync ID, account, where the room lives now (`home`), epoch, room JSON, last seen. See [room sync](room-sync.md) |
| `songs`, `song_sources`, `song_edges` | The song graph, see [below](#song-graph) |
| `episode_progress` | Per account and episode: where they stopped, the length, played; for resuming in any room and the played marks (`Episodes.kt`) |

On **Android**, the app has its own local **SQLite** database (`PhoneRoomStore`) with one
table, `rooms`, so its rooms survive restarts; other settings are in SharedPreferences.
Planned there: the local copy of joined rooms (for cloning) and downloads. Rooms that
belong to an account are synced to the account's server (see [room sync](room-sync.md)).

Stored on the server now: accounts, role templates, rooms (server-hosted, plus copies of
accounts' phone rooms), listening history, the song graph and linked YouTube Music sign-ins.
Planned: room defaults and YTMP playlists.

## Rooms (implemented)

Rooms are kept across restarts on both hosts, through one interface in core (`RoomStore`):

```
rooms
  code        PRIMARY KEY
  data        -- JSON: name, owner token, visibility, participants (id, token, name),
              -- queue, last 500 history items, current song, position, last activity
  updated_at
```

- `RoomManager` loads all rooms on start and saves a room about a second after it changes
  (a burst of events is one write), every 15 s while it plays, and all rooms on shutdown.
  Store calls run one at a time, off the main thread.
- A restored room is **paused**, everyone in it is offline (they reconnect with their guest
  token), and the stream URL is resolved again on *play*. Active speakers are not restored.
- One JSON column keeps the room format free to change: new fields get a default. The room's
  history here is only for the room itself; the listening history below gets its own tables.
- Server: `JdbcRoomStore` (plain JDBC; the same SQL works on SQLite and PostgreSQL, upsert
  with `ON CONFLICT`). Phone:
  `PhoneRoomStore` (Android SQLite).

## Song graph

Built in `server/.../SongGraph.kt` (see [features](../features/history-and-recommendations.md#song-graph)).

### Why not a graph database?

Graph databases (Neo4j, …) are a separate service, have no SQLite-like embedded mode
that fits here, and are overkill for this size. A group of friends produces maybe
tens of thousands of songs and a few hundred thousand edges. A normal table with the
right index handles that easily.

### Tables

```
songs              -- one row per *merged* song (the graph node)
  id, match_key, duration_ms
  INDEX (match_key)

song_sources       -- the same song on different sources (merging)
  source_id         -- the song's ID: 'ytm:…', 'local:<library>:song:…', …
  song_id → songs.id
  data              -- the latest Song (JSON), to put it in a radio
  updated_at
  PRIMARY KEY (source_id)

song_edges         -- the graph: "B was played after A"
  from_song → songs.id
  to_song   → songs.id
  scope             -- 'personal' | 'imported' | 'server' | 'server_imported' | 'ytm' | 'ytm_account' | 'radio_near'
  account_id        -- the account for 'personal', 'imported' and 'ytm_account' ('radio_near': whose radio, '' none), '' otherwise
  solo_plays        -- counters, kept raw (see below)
  shared_plays
  skips
  ytm_rank          -- YTM edges: its best place in YTM's radio of from_song
  seen              -- YTM edges: in how many kept fetches (0: learned before fetches were kept)
  rank_sum          -- YTM edges: its places added up (for the average); radio_near: the distances
  confirmed         -- imported edges: a radio also puts the two songs together (migration 10)
  updated_at        -- the last play (or YTM radio) that counted

radio_fetches      -- every YouTube Music radio fetched, as it came (migration 9)
  id, seed, fetched_at
  way               -- 'room' | 'crawl' | 'similar'
  account_id        -- whose YouTube Music account made it; '' without an account
  reported_by       -- the account whose phone sent it; '' when the server fetched it
  songs             -- JSON list of the radio's song IDs, in order (their data is in song_sources)
  INDEX (seed, fetched_at)

history_imports    -- Google Takeout imports (plays.import_id points here; '' for plays in YTMP)
  id, account_id, at, plays, duplicates, first_at, last_at
  PRIMARY KEY (from_song, to_song, scope, account_id)
  INDEX (from_song, scope, account_id), INDEX (to_song, scope, account_id)
```

- **Personal graph** = rows with `scope = 'personal'` for one account.
- **Server graph** = rows with `scope = 'server'`, updated together with the personal rows
  whenever someone with tracking on plays a song. A play several accounts heard (same room,
  same start, same song) counts once: the first report of it does (`plays_room_time` index).
- **YTM edges** = `scope = 'ytm'` (made without an account, everyone's) or `'ytm_account'`
  (made with an account's YouTube Music: only that account's radios use it). The seed → its
  50 songs, whenever the server fetches a radio, a phone sends one it fetched
  (`POST /api/auth/radio-fetch`), or the radio crawl fetches one; each fetch is kept in
  `radio_fetches` first (a room's at most every 6 hours per seed and account).
- **Imported edges** = `'imported'` / `'server_imported'`: like personal and server, from
  plays imported from Takeout (`plays.import_id`), so they can be weighed apart. One that
  happened once counts `oneOff` of it until `confirmed`: a `ytm`, `ytm_account` or
  `radio_near` edge between the same two songs, either way round (`SongGraph.confirm`, after a
  rebuild and after each radio).
- **Close together in a radio** = `'radio_near'`: each of a radio's songs → the next 3
  (`NEAR_WINDOW`), place = the later one's (for `radioDepth`), counted both ways round.
- **Solo vs. shared** listening are separate counters, so radio can use either or both.

### What counts as "played after"

From one account's history: song B follows song A when B started in the **same room**, at
most **30 minutes** after A ended, and A wasn't skipped within 30 s (that says nothing about
what comes next). Podcast episodes are left out. B skipped before half of it (or 30 s) was
heard is a **skip**; otherwise a solo or shared **play**.

### Raw counters, weights computed at query time

Edges store **counts** (plays, skips, YTM rank), not a final weight. The weight is
computed when the graph is read:

```
personal        = P · (solo + party · shared − skip · skips) · decay   (shared left out for "only solo")
imported        = P · I · (solo − skip · skips) · decay
server          = S · (solo + party · shared − skip · skips) · decay
server_imported = S · I · (solo − skip · skips) · decay
ytm(_account)   = Y(A) · max(0.2, 1 − average rank / 50) · min(2, √seen) · decay   (0 past radioDepth)
radio_near      = N · (1 − (average distance − 1) / 3) · min(2, √seen) · decay     (0 past radioDepth)
one-off imported: × oneOff unless confirmed
decay           = 0.5 ^ (days since updated_at / halfLifeDays)
```

The numbers are the account's `GraphTuning` (account data `graph_tuning`; defaults P 2, I 1,
S 1, Y 1.5, A 0.5, N 0.5, oneOff 0.3, party 0.6, skip 1, reverse 0.5, second hop 0.35, 180 days, depth 50),
set with the sliders of the graph view (`GET/PUT /api/account/graph/tuning`). The page
weighs the raw edges of `GET /api/account/graph` with the same formula
(`frontend/src/lib/graph.ts`), so the sliders redraw at once. Because what the graph learns
from is all kept (the plays and `radio_fetches`), **all of it is rebuilt**: on the first
start with the graph or when its version changes, after deleting from a history, and after
a Takeout import or removing one. YTM edges from before radios were kept (`seen = 0`) stay
as they are.

### Radio hints

A room's radio asks the owner's server `POST /api/auth/radio-hints` (`RadioHintsRequest`:
the seed, YouTube Music's songs, the room's `RadioOptions`): the graph's songs for the seed,
and of all of them which the account heard (for *songs you know / new to you*) and which to
leave out (skipped more than heard, or played in the last hours). An older server without
it gets `GET /api/auth/radio` (only the songs).

### Takeout import

`POST /api/account/imports` (the zip, or the watch history in it, read by `host/Takeout`):
the YouTube Music plays go into `plays` with an `import_id`, room `takeout` (so they only
follow each other), no length, heard = the time to the next play (at most 15 min), skipped
when that's under 30 s. A play already in the history is left out: the same song started
here up to 15 minutes before (YTMP adds a play to YouTube Music's history when it ends) or 2
after. `DELETE /api/account/imports/{id}` takes it out again.

### The radio crawl

`host/RadioCrawl.kt`: one anonymous radio every `paceSeconds` (default 90, at least 30) of a
song not learned in `refreshDays` (default 90), oldest first; after an error it waits 30
minutes, after a bot check 6 hours. The server's crawls its local music with a YouTube Music
ID and its song cache, kept in its settings (`radio_crawl`, `GET/PUT /api/admin/radio-crawl`).
A phone's crawls its matched music files and saved songs, sends each radio to its owner's
server and keeps when in `radio-crawl-log.json` (`GET/PUT /api/radio-crawl`, only on the
phone; it waits for the matcher's Wi-Fi and charging rules).

### Song matching

"Is this local file the same song as that YTM song?" This is needed for merging songs
in the graph and for importing playlists.

Built for the graph (`SongMatch` in core): a song new to `song_sources` joins the node with
the same **match key** (title and main artist, lowercase, without accents, brackets like
"(Remastered 2011)" or "[Official Video]", and "feat. …") whose length is within **5 s**
(or unknown); otherwise it's a new node. Radio mixing uses the same key so one song isn't
in a radio twice.

Planned, for importing playlists (done by the **host**, one implementation for all sources;
modules only provide candidates through their normal `search`):

1. If the song has an **ISRC** and the target module supports `isrc_lookup`, use that.
   It's an exact match.
2. Otherwise, search the target module for `artist + title` and score each result:
   - title similarity, after normalising (as above)
   - artist similarity
   - duration difference (within ±3 s scores high)
   - album, if known
3. Above a threshold → match, stored in `song_sources`. Just below it → stored as
   "uncertain", so it can be checked or corrected later. Otherwise no match.

Audio fingerprinting (AcoustID) can be added later as an extra step for local files.

### Generating a radio

`SongGraph.similar(seed, account)`:

1. Load the edges from and to the start song (one indexed query; the ones *to* it count
   half), for the server, YTM and (as the account's setting allows) the account's own graph,
   and add up their weights per neighbour.
2. The best 8 neighbours' own neighbours get a share too (0.35 × how strong that neighbour is).
3. The best 50 come back as songs, each from the start song's source when the graph knows it
   there (a local file's radio stays local), else from YTM. Hosts drop local songs from
   libraries they don't have.

That list is **mixed** into the radio (`mixRadio` in core): one graph song after every two
of YTM's radio, each song once. Without a YTM radio (local music, or YTM failing) the graph's
list is the radio. The room keeps an endless autoplay by asking again from its last song, so
it walks the graph from there. Who gets which graph:

- **Rooms** (Start radio, Autoplay this, autoplay): the room owner's. A server reads its own
  graph; a phone asks its linked server (`GET /api/auth/radio?id=` with the owner's host
  token, the one made for the app's pages when it has one).
- **Find similar / Related** (`/api/similar`, `/api/me/similar`): the asker's, with the token
  of their request.
- The auth server only includes the account's own listening for host tokens that reach only
  the account's own devices (made for the phone app's pages or the server's own); any other
  host gets the server graph and YTM edges.

The walk happens in **Kotlin**, so no recursive SQL is needed and the queries stay
portable between SQLite and PostgreSQL. If it ever gets slow, the adjacency lists can be
cached in memory. That's unlikely to be needed at this scale.

### Maybe later: a graph database

If the graph ever grows a lot, or we want more advanced graph queries (communities,
"songs between A and B", …), a real graph database could be added as an optional
backend. See [future ideas](../features/future-ideas.md#graph-database-maybe).

## Backup and restore

`Backups` (server): `VACUUM INTO` a temporary file for the download (`GET /api/admin/backup`);
an upload (`POST /api/admin/restore`) is opened as a database, checked (`schema_version` no
newer than the migrations here, an `accounts` table), and kept as `<db>.restore`.
`Database.open` swaps it in before connecting: the old database (and its journal) is renamed
`<db>.before-restore`. Migration 8 added `accounts.disabled_until` and `audit_log` (the
admin page's Log, `AuditLog`: the newest 1000 entries).
