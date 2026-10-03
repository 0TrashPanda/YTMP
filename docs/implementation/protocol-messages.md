# Protocol Messages

> Status: **partly implemented.** Everything marked ✅ exists in milestone 1 (see
> `protocol/src/main/kotlin`, which is the source of truth). The rest is still a draft.

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
| → | `hello` ✅ | `protocolVersion`, `roomCode`, `guestName`, `guestToken` (to reconnect as the same guest), `ownerToken`, `accountToken` ✅ (a host token, see [auth](auth.md)), `hideFromHistory` ✅ (keep me out of others' listening history) |
| → | `attach` ✅ | Instead of `hello`: `protocolVersion`, `roomCode`, `guestToken`. A **second connection for a participant who is already in the room**: it gets the same `welcome` and events and may send `command`s and `ping`s as them, but doesn't replace their connection or change whether they're online. Kicks, bans and cleanup end it (`rejected`: `kicked`/`banned`); an unknown token gets `kicked`. Used by the Android app's player, see [android-app](android-app.md) |
| ← | `welcome` ✅ | `participantId`, `guestToken`, `seq`, `state` (the snapshot), `accountId` ✅ (null when joined as a guest, also when the token wasn't accepted) |
| ← | `rejected` ✅ | `reason`: `room_not_found`, `version_mismatch`, `invalid_name`, `replaced` ✅. Later: `banned`, `accounts_only`, `untrusted_auth_server` |
| ⇄ | `ping` / `pong` ✅ | `clientTime` / `clientTime, hostTime`, for clock sync |

## Snapshot (`RoomState`)

Milestone 1 has `room`, `participants`, `queue`, `history`, `nowPlaying` (`{ item, streamUrl }`)
and `playback`. The other fields come with permissions, roles, outputs and vote to skip.

```jsonc
{
  "room":        { "id", "code", "name", "visibility", "ownerId", "settings": { ... } },
  "me":          { "participantId", "roleId", "permissions": [...] },
  "participants":[ { "id", "name", "accountId?", "roleId", "listening": true } ],   // accountId ✅
  "roles":       [ { "id", "name", "permissions": [...] } ],
  "queue":       [ QueueItem ],
  "autoplay":    { "seed": Song | Artist | Album | Playlist, "items": [ QueueItem ] },
  "history":     [ QueueItem ],          // the last N items; older ones via LoadHistory
  "nowPlaying":  QueueItem | null,
  "playback":    { "playing", "positionMs", "hostTimeMs", "repeat" },
  "outputs":     [ { "id", "name", "active", "volume" } ],
  "skipVotes":   { "votes", "needed" } | null
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
| `ShuffleQueue` | – | Reorder |
| `StartRadio` ✅ (song only so far) | `song`; later `seedId` (song, artist, album or playlist) | Start radio |
| `AutoplayFromHere` ✅ (song only so far) | `song`; later `seedId` (song, artist, album or playlist) | Autoplay from here |
| `LoadHistory` | `beforeItemId`, `limit` | – |

### Playback

| Command | Fields | Permission |
|---------|--------|------------|
| `Play` / `Pause` ✅ | – | Play/pause |
| `Skip` / `Previous` ✅ | – | Skip |
| `Seek` ✅ | `positionMs` | Seek |
| `SetRepeat` | `mode: "off" \| "queue" \| "one"` | Repeat |
| `VoteSkip` / `UnvoteSkip` | – | Vote to skip |
| `SetOutput` ✅ | `outputId`, `active` | Change host outputs |
| `SetOutputVolume` ✅ | `outputId`, `volume` (0–1) | Host output volume |

### Client playback

| Command | Fields | Permission |
|---------|--------|------------|
| `SetListening` ✅ | `on` | Play audio locally |

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
| `UpdateSettings` ✅ (name, default roles) | partial `settings` (name, visibility, ordering, access, limits, vote to skip, always on, …) | Change settings |
| `ChangeOwner` | `participantId` | Owner only |
| `CloneRoom` | `visibility: "public" \| "solo"` | Clone room. The result has the new room's code. |
| `SendMigration` | `targetRoom`, `mode: "invite" \| "forced"` | Send migration message |
| `CloseRoom` | – | Owner only |
| `request_snapshot` ✅ | – (a message type, not a command) | – |

## Events (host → client)

| Event | Fields | Sent to |
|-------|--------|---------|
| `ParticipantJoined` ✅ / `ParticipantLeft` ✅ | participant / `participantId` | everyone |
| `ParticipantUpdated` ✅ | participant (role, permissions, name, online, listening) | everyone |
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
| `PlaybackChanged` ✅ | `playback: { playing, positionMs, hostTimeMs }` (on every change) | everyone |
| `Notice` ✅ | `message` (for example a song that can't be played) | everyone |
| `RepeatChanged` | `mode` | everyone |
| `SkipVotesChanged` | `votes`, `needed` | everyone |
| `OutputsChanged` ✅ | `outputs` (id, name, kind, active, volume) | everyone |
| `SettingsChanged` ✅ | `settings` (default roles so far) | everyone |
| `RolesChanged` ✅ | `roles` (all, ranked) | everyone |
| `BansChanged` ✅ | `bans` | everyone |
| `OwnerChanged` | `participantId` | everyone |
| `MigrationOffered` | `targetRoom`, `mode`, `from` | everyone |
| `AdminlessPrompt` | – (no admin for 5 min: "you can clone this room") | everyone |
| `RoomClosing` | `reason` | everyone |
| `YourSongIsNext` | `item` | the person who added it (if they turned it on) |
