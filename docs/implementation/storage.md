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
  scope             -- 'personal' | 'server' | 'ytm'
  account_id        -- the account for scope = 'personal', '' otherwise
  solo_plays        -- counters, kept raw (see below)
  shared_plays
  skips
  ytm_rank          -- for scope = 'ytm': its best place in YTM's radio of from_song
  updated_at        -- the last play (or YTM radio) that counted
  PRIMARY KEY (from_song, to_song, scope, account_id)
  INDEX (from_song, scope, account_id), INDEX (to_song, scope, account_id)
```

- **Personal graph** = rows with `scope = 'personal'` for one account.
- **Server graph** = rows with `scope = 'server'`, updated together with the personal rows
  whenever someone with tracking on plays a song. A play several accounts heard (same room,
  same start, same song) counts once: the first report of it does (`plays_room_time` index).
- **YTM edges** = rows with `scope = 'ytm'`: the seed → its first 25 songs whenever the
  server fetches a YTM radio (also an account's own), at most every 6 hours per seed. A
  phone's YTM radios aren't learned yet.
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
personal = 2 · (solo + 0.6 · shared − skips) · decay     (shared left out for "only solo")
server   =     (solo + 0.6 · shared − skips) · decay
ytm      = 1.5 · max(0.2, 1 − rank / 25) · decay
decay    = 0.5 ^ (days since updated_at / 180)
```

The formula can be tuned without migrating data (the numbers are a first guess, see
[open questions](../open-questions.md)). And because the full listening history is stored
too, everything but the YTM edges is **rebuilt from history**: on the first start with the
graph (so an existing history counts right away), when its version changes, and after
deleting from a history (so deleted plays are gone from the graph too).

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
