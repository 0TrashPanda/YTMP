# Protocol Messages

> Status: **partly implemented.** Everything marked ✅ exists (see
> `protocol/src/main/kotlin/.../Messages.kt`, which is the source of truth). The rest is still
> a plan for features that aren't built yet.

The design is in [protocol.md](protocol.md): commands from clients, events from the host,
and a snapshot on connect. Types are Kotlin `@Serializable` classes, and TypeScript types are
generated from them.

## What goes over the WebSocket, and what doesn't

| Over the room WebSocket | Over plain HTTP |
|-------------------------|-----------------|
| Everything about the **room**: queue, playback, participants, roles, settings | **Search, browse, lyrics** on the host (`/api/search`, …). This is request/response, which suits HTTP better. |
| | **Account data** on the auth server: playlists, history, recap, settings, linked accounts |

## Envelope

```jsonc
// client → host  (message types: hello, attach, command, ping, request_snapshot)
{ "type": "command", "id": "c42", "command": { "kind": "AddSongs", ... } }

// host → client  (message types: welcome, rejected, result, event, snapshot, pong)
{ "type": "result",   "id": "c42", "error": null }                     // or "error": { code, message }
{ "type": "event",    "seq": 1017, "event": { "kind": "QueueItemsAdded", ... } }
{ "type": "snapshot", "seq": 1016, "state": { ... } }
```

- Every command gets a `result` (with `error: permission_denied`, `not_found`, …).
- Events have an increasing **`seq`**. When a client sees a gap, it asks for a new snapshot
  (`RequestSnapshot`).

## Connecting

| Direction | Message | Content |
|-----------|---------|---------|
| → | `hello` ✅ | `protocolVersion`, `roomCode`, `guestName`, `guestToken` (to reconnect as the same guest), `ownerToken`, `accountToken` ✅ (a host token, see [auth](auth.md)), `hideFromHistory` ✅ (keep me out of others' listening history), `migrationKey` ✅ (moved here from another room: see *Clones* below), `password` ✅ (the room's password, when `room.hasPassword`), `device` ✅ (which device this is, e.g. "Pixel 8": tells your devices apart) |
| → | `attach` ✅ | Instead of `hello`: `protocolVersion`, `roomCode`, `guestToken`. A **second connection for a participant who is already in the room**: it gets the same `welcome` and events and may send `command`s and `ping`s as them, but doesn't replace their connection or change whether they're online. Kicks, bans and cleanup end it (`rejected`: `kicked`/`banned`); an unknown token gets `kicked`. Used by the Android app's player, see [android-app](android-app.md) |
| ← | `welcome` ✅ | `participantId`, `guestToken`, `seq`, `state` (the snapshot), `accountId` ✅ (null when joined as a guest, also when the token wasn't accepted), `migrationKey` ✅ (only yours) |
| ← | `owner_token` ✅ | `ownerToken`: you're the owner now (keep it, like the one from creating the room) |
| ← | `rejected` ✅ | `reason`: `room_not_found`, `version_mismatch`, `invalid_name`, `replaced` (the same guest connected again elsewhere), `room_moved` (the room moved to another of the owner's hosts, see [room sync](room-sync.md)), `private_room` (a solo room, only for the hosting device), `kicked`, `banned`, `account_required` (the host only lets in accounts), `left` (you left, from another connection), `migrated` (everyone was moved; `movedTo` is the new room), `password_required`, `wrong_password`. Later maybe: `untrusted_auth_server` |
| ⇄ | `ping` / `pong` ✅ | `clientTime` / `clientTime, hostTime`, for clock sync |

## Snapshot (`RoomState`)

The real `RoomState` is in `Messages.kt`; the sketch below also has fields for features that
aren't built yet. Repeat (`repeat`) and the skip vote (`skipVotes`) are built, as top-level fields.

```jsonc
{
  "room":        { "id", "code", "name", "visibility", "ownerId", "settings": { ... } },
  "me":          { "participantId", "roleId", "permissions": [...] },
  "participants":[ { "id", "name", "accountId?", "roleId", "listening": true,
                     "device", "volume", "volumeChanges" } ],   // accountId ✅, device/volume ✅ (your devices)
  "roles":       [ { "id", "name", "permissions": [...] } ],
  "queue":       [ QueueItem ],
  "autoplay":    { "seed": Song | Artist | Album | Playlist, "items": [ QueueItem ] },
  "history":     [ QueueItem ],          // the last N items; older ones via LoadHistory
  "nowPlaying":  QueueItem | null,
  "playback":    { "playing", "positionMs", "hostTimeMs", "starting", "speed", "loading" },
  "repeat":      "off" | "queue" | "one",                       // ✅
  "outputs":     [ { "id", "name", "active", "volume" } ],
  "skipVotes":   { "voters": [participantId], "needed" } | null   // ✅ null: the room doesn't vote
}

// QueueItem
{ "itemId", "song": Song, "addedBy": participantId, "addedAt",
  "origin": "manual" | "radio" | "autoplay", "result?": "played" | "skipped" }
```

## Commands (client → host)

Each command lists the permission it needs (see [room management](../features/room-management.md#permissions)).

### Queue

| Command | Fields | Permission |
|---------|--------|------------|
| `AddSongs` ✅ | `songs` (full song objects from search), `position: "next" \| "end"` | Add songs |
| `PlayNow` ✅ | `song` | Play now |
| `JumpTo` ✅ | `itemId` (a queue or history item) | Play now |
| `RemoveQueueItem` ✅ | `itemId` (queue, history or autoplay) | Remove own / others' songs |
| `MoveQueueItem` ✅ | `itemId`, `toIndex` | Reorder |
| `MoveItem` ✅ | `itemId`, `list: "history" \| "queue" \| "autoplay"`, `toIndex` | Reorder (autoplay → queue next/last: Add songs) |
| `ClearQueue` ✅ | – | Remove own / others' songs |
| `ClearAutoplay` ✅ | – | Autoplay from here |
| `ShuffleQueue` ✅ | – (with fair order it keeps the rounds) | Reorder |
| `StartRadio` ✅ (song only so far) | `song`, `songs?` (a radio the sender brings: their own account's, from their server; null: the room's); later `seedId` | Start radio |
| `AutoplayFromHere` ✅ (song only so far) | `song`; later `seedId` (song, artist, album or playlist) | Autoplay from here |
| `AutoplaySongs` ✅ | `songs` (a playlist's "Add to autoplay": they replace the autoplay queue, radio continues after them) | Autoplay from here |
| `LoadHistory` | `beforeItemId`, `limit` | – |

### Playback

| Command | Fields | Permission |
|---------|--------|------------|
| `Play` / `Pause` ✅ | – | Play/pause |
| `Skip` / `Previous` ✅ | `Skip`: `play` (default false): also start playing when paused (needs Play / pause) | Skip |
| `Seek` ✅ | `positionMs` | Seek |
| `SetSpeed` ✅ | `speed` (0.5, 0.75, 1, 1.25, 1.5, 1.75 or 2) | Play/pause; the speed podcast episodes play at, kept for the next ones (music plays at 1) |
| `SetRepeat` ✅ | `mode: "off" \| "queue" \| "one"` | Repeat |
| `VoteSkip` ✅ | `on` (false takes the vote back). `Skip` from someone who may only vote is a vote too | Vote to skip |
| `SetOutput` ✅ | `outputId`, `active` | Change host outputs |
| `SetOutputVolume` ✅ | `outputId`, `volume` (0–1) | Host output volume |

### Client playback

| Command | Fields | Permission |
|---------|--------|------------|
| `SetListening` ✅ | `on`, `startsTogether` (the player sends `PlayerReady`) | Play audio locally |
| `SetSpeakers` ✅ | `names` (speakers near the phone that play the room, from the app; empty: none), `startsTogether` | Play audio locally |
| `SetDeviceVolume` ✅ | `volume` (0–1), `participantId` (null: this device). A device says its own volume whenever it changes (`Participant.volume`); with the id of another participant **with the same account** it sets theirs, which bumps their `volumeChanges`, and that device turns its volume to it. See [your devices](../features/playback-and-outputs.md#your-devices) | — (only your own devices) |
| `SetRoomCode` ✅ | `code` (3–12 letters and digits; the old codes keep leading to the room) | Change settings |
| `PlayerReady` ✅ | `itemId`, `speakers` (this player, or all the phone's speakers, loaded a starting song; see [starting together](playback-sync.md#starting-a-song-together)) | — |

No command is needed for the proxied stream: when the direct URL fails, the client uses
`/api/audio/{songId}` on the host.

### People & roles

| Command | Fields | Permission |
|---------|--------|------------|
| `Kick` ✅ | `participantId` | Kick |
| `Ban` / `Unban` ✅ | `participantId` / `banId` | Ban / unban |
| `AssignRole` ✅ | `participantId`, `roleId` | Assign roles |
| `SetParticipantPermissions` ✅ | `participantId`, `allow`, `deny` | Assign roles |
| `CreateRole` / `UpdateRole` / `DeleteRole` / `MoveRole` ✅ | role / `roleId`, `toIndex` | Edit roles |
| `SetDisplayName` | `name` | – (own name) |

### Room

| Command | Fields | Permission |
|---------|--------|------------|
| `UpdateSettings` ✅ (name, default roles, autoplay, always on, how long a new song waits for the players (`startWaitSeconds`, 0–10), karaoke mode (`karaoke`), fair order, vote to skip and its share, the three queue limits) | partial `settings`: null leaves a field as it is; a limit of 0 is off (later also access, …) | Change settings |
| `SetVisibility` ✅ | `visibility: "public" \| "solo"` | Owner only |
| `SetOpenElsewhere` ✅ | `mode` (ask / move / move if away) or null for the account's setting, see [room sync](room-sync.md) | Owner only |
| `ChangeOwner` ✅ | `participantId` | Owner only |
| `LeaveRoom` ✅ | `newOwnerId?`, `stayOwner` (the owner of a party room: who takes over; null = the longest-present person with the top role) | – |
| `SetPassword` ✅ | `password` (null: none; stored hashed, PBKDF2) | Manage room |
| `SendMigration` ✅ | `target: { url, code, name }`, `mode: "invite" \| "forced"` | Move everyone |
| `CancelMigration` ✅ | – | Move everyone |
| `CloseRoom` | – | Owner only (now `DELETE /api/rooms/{code}`) |

Cloning isn't a command: the page sends what it has (`RoomClone`: queue, history, current
song and position, autoplay, roles, settings, and `migrants`) to the place the clone goes,
`POST /api/rooms` with `clone`, and gets the new room's code, owner token and `url`. From the
phone's own page to your server: `POST /api/rooms/clone-to-server` (the phone forwards it to
`POST /api/auth/rooms/clone` with your host token). Onto the phone from another host's page in
the app: bridge `cloneRoom` (version 6).

**Clones and roles.** Every participant has a public `tag` (in `Participant`), a hash of their
private `migrationKey` (in their `welcome`; itself a hash of their guest token). A clone lists
the old room's people as `migrants` (tag, old id, name, role, overrides). Joining the clone with
`hello.migrationKey` whose hash matches a tag gives that person's role, overrides and old id
back (so `addedBy` still points at them), once. Links carry the key in the fragment
(`#migrate=…`, also `#owner=…` and `#name=…`), which never reaches a server.

**Party screens** ✅ ([ui](../features/ui.md#party-screen)) aren't commands either. A TV
(`/tv` page, no account) asks `POST /api/screens` for a 6-digit `code` and a `secret`, then
long-polls `GET /api/screens/{secret}` (25 s; 204: ask again, 404: expired after 10 min).
Someone in the room sends `POST /api/screens/link` with the code, the room code and their
guest token; the room adds a participant with `screen: true`, no role (it may do nothing),
named *Party screen*, and the TV gets `{ roomCode, guestToken }` and joins with a normal
`hello` as that participant. It comes in without an account on accounts-only hosts and into
solo rooms, isn't counted for votes or "listened with", can't be made owner, and is saved
with the room (so a TV that's switched off comes back to it). `/api/screens` is open without
an account; codes are in memory only (`ScreenPairings`), at most 200 waiting, 10 new ones per
minute per address. A TV the app shows the party screen on (a Chromecast on the phone's
network) gets its screen from `POST /api/screens/cast` instead, and leaves with
`POST /api/screens/unlink` ([chromecast](chromecast.md#from-the-app-in-a-room-on-another-host-)).
| `request_snapshot` ✅ | – (a message type, not a command) | – |

## Events (host → client)

| Event | Fields | Sent to |
|-------|--------|---------|
| `ParticipantJoined` ✅ / `ParticipantLeft` ✅ | participant / `participantId` | everyone |
| `ParticipantUpdated` ✅ | participant (role, permissions, name, online, listening, `device`, `volume`, `volumeChanges`) | everyone |
| `YouWereKicked` / `YouWereBanned` (done as `rejected` with reason `kicked` / `banned` ✅) | – | that participant |
| `QueueItemsAdded` ✅ | `items`, `index` | everyone |
| `QueueItemRemoved` ✅ | `itemId` | everyone |
| `QueueItemMoved` ✅ | `itemId`, `toIndex` | everyone |
| `QueueReplaced` ✅ | `items` (Start radio; later Shuffle, fair-ordering re-sort) | everyone |
| `AutoplayChanged` ✅ (instead of `AutoplayReplaced` / `AutoplayExtended`) | `seed?`, `items` (the whole autoplay queue) | everyone |
| `NowPlayingChanged` ✅ | `item` (or null) | everyone |
| `StreamReady` ✅ | `itemId`, `streamUrl` (direct URL, once the host has resolved it) | everyone |
| `HistoryAppended` ✅ | `item` (with `result: played/skipped`) | everyone |
| `HistoryItemRemoved` ✅ | `itemId` (when jumping back) | everyone |
| `HistoryReplaced` ✅ | `items` (a song moved in or out of the history) | everyone |
| `PlaybackChanged` ✅ | `playback: { playing, positionMs, hostTimeMs, starting, speed, loading }` (on every change; `starting`: a new song waits for the players; `speed`: an episode played faster or slower, else 1; `loading`: it should play, but its stream is still being looked up) | everyone |
| `Notice` ✅ | `message` (for example a song that can't be played) | everyone |
| `RepeatChanged` ✅ | `mode` | everyone |
| `SkipVotesChanged` ✅ | `skipVotes` (`voters`, `needed`, or null): after a vote, when people come or go, or the settings change | everyone |
| `OutputsChanged` ✅ | `outputs` (id, name, kind, active, volume) | everyone |
| `SettingsChanged` ✅ | `settings` | everyone |
| `RoomUpdated` ✅ | `room` (name, visibility, owner, …) | everyone |
| `RolesChanged` ✅ | `roles` (all, ranked) | everyone |
| `BansChanged` ✅ | `bans` | everyone |
| `OwnerChanged` (done as two `ParticipantUpdated` ✅ and a `Notice`) | – | everyone |
| `MigrationChanged` ✅ | `migration: { target, mode, fromName }` or null (also in the snapshot) | everyone |
| `AdminlessChanged` ✅ | `adminless` (no owner or top-role person here for 5 min, servers; also in the snapshot) | everyone |
| `RoomClosing` | `reason` | everyone |
| `YourSongIsNext` | `item` | the person who added it (if they turned it on) |
