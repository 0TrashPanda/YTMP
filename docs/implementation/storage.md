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
- To support both, the code uses a Kotlin SQL library that works with both, such as
  **Exposed** or **jOOQ**, plus migrations that run on both (for example **Flyway**).
  Queries stay in portable SQL, with no database-specific features.

On **Android**, the app has its own local **SQLite** database for its rooms (solo rooms
survive restarts), the local copy of joined rooms (for cloning), downloads and settings.
Things that belong to an account are synced to the account's server.

Stored on the server: accounts, role templates, room defaults, playlists, rooms (for
server-hosted and synced solo rooms), listening history, and the song graph.

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
  with `ON CONFLICT`). The table is created with `CREATE TABLE IF NOT EXISTS`; real
  migrations (Flyway) come with the first schema that needs them (accounts). Phone:
  `PhoneRoomStore` (Android SQLite).

## Song graph

### Why not a graph database?

Graph databases (Neo4j, …) are a separate service, have no SQLite-like embedded mode
that fits here, and are overkill for this size. A group of friends produces maybe
tens of thousands of songs and a few hundred thousand edges. A normal table with the
right index handles that easily.

### Tables

```
songs              -- one row per *merged* song (the graph node)
  id, title, artist, album, duration_ms

song_sources       -- the same song on different sources (merging)
  song_id → songs.id
  source            -- 'ytm', 'yt', 'local', 'plex', …
  source_ref        -- video ID, file hash, Plex key, …
  UNIQUE (source, source_ref)

song_edges         -- the graph: "B was played after A"
  from_song → songs.id
  to_song   → songs.id
  scope             -- 'personal' | 'server' | 'ytm'
  account_id        -- set for scope = 'personal', NULL otherwise
  solo_plays        -- counters, kept raw (see below)
  shared_plays
  skips
  ytm_rank          -- for scope = 'ytm': position in YTM's radio/related list
  updated_at
  PRIMARY KEY (from_song, to_song, scope, account_id)
  INDEX (scope, account_id, from_song)
```

- **Personal graph** = rows with `scope = 'personal'` for one account.
- **Server graph** = rows with `scope = 'server'`, updated together with the personal rows
  whenever someone with tracking on plays a song.
- **YTM edges** = rows with `scope = 'ytm'`, written whenever YTMP fetches a YTM radio or a
  related list.
- **Solo vs. shared** listening are separate counters, so radio can use either or both.

### Raw counters, weights computed at query time

Edges store **counts** (plays, skips, YTM rank), not a final weight. The weight is
computed when the graph is read, for example:

```
weight = (solo_plays · a + shared_plays · b − skips · c) · time_decay + ytm_score(ytm_rank) · d
```

This means the formula can be tuned later without migrating data. And because the
full listening history is stored too, the whole graph can be **rebuilt from history**
at any time.

### Song matching

"Is this local file the same song as that YTM song?" This is needed for merging songs
in the graph and for importing playlists.

Matching is done by the **host** (one implementation for all sources). Modules only
provide candidates through their normal `search`:

1. If the song has an **ISRC** and the target module supports `isrc_lookup`, use that.
   It's an exact match.
2. Otherwise, search the target module for `artist + title` and score each result:
   - title similarity, after normalising (lowercase, strip "(Remastered 2011)",
     "feat. …", "- Official Video", …)
   - artist similarity
   - duration difference (within ±3 s scores high)
   - album, if known
3. Above a threshold → match, stored in `song_sources`. Just below it → stored as
   "uncertain", so it can be checked or corrected later. Otherwise no match.

Audio fingerprinting (AcoustID) can be added later as an extra step for local files.

### Generating a radio

1. Load the neighbours of the start song: one indexed query on `from_song`.
2. Pick the next song **weighted-randomly** among them, skipping songs already in the
   radio, the queue or recent history.
3. Repeat from the picked song (a random walk), and now and then jump back to the start
   song so the radio doesn't drift too far.
4. If a song has too few neighbours, fall back to YTM radio or metadata (same artist,
   album).

The walk happens in **Kotlin**, so no recursive SQL is needed and the queries stay
portable between SQLite and PostgreSQL. If it ever gets slow, the adjacency lists can be
cached in memory. That's unlikely to be needed at this scale.

### Maybe later: a graph database

If the graph ever grows a lot, or we want more advanced graph queries (communities,
"songs between A and B", …), a real graph database could be added as an optional
backend. See [future ideas](../features/future-ideas.md#graph-database-maybe).
