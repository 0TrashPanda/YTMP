# Protocol Messages

> Status: **draft for review**. Names follow the final feature names
> (Play next, Start radio, Autoplay from here, …).

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
// client → host
{ "type": "command", "id": "c42", "command": { "kind": "AddSongs", ... } }

// host → client
{ "type": "result",   "id": "c42", "ok": true }                        // or "error": { code, message }
{ "type": "event",    "seq": 1017, "event": { "kind": "QueueItemsAdded", ... } }
{ "type": "snapshot", "seq": 1016, "state": { ... } }
```

- Every command gets a `result` (with `error: permission_denied`, `not_found`, …).
- Events have an increasing **`seq`**. When a client sees a gap, it asks for a new snapshot
  (`RequestSnapshot`).

## Connecting

| Direction | Message | Content |
|-----------|---------|---------|
| → | `Hello` | `protocolVersion`, `roomCode`, auth: `{ token }` (account) or `{ guestName, guestCookie }`, `listenedWithOptOut` |
| ← | `Welcome` | `participantId`, `guestCookie` (new guests), then a `snapshot` |
| ← | `Rejected` | `reason`: `room_not_found`, `banned`, `accounts_only`, `untrusted_auth_server`, `version_mismatch` |
| ⇄ | `Ping` / `Pong` | `clientTime` / `clientTime, hostTime`, for clock sync |

## Snapshot (`RoomState`)

```jsonc
{
  "room":        { "id", "code", "name", "visibility", "ownerId", "settings": { ... } },
  "me":          { "participantId", "roleId", "permissions": [...] },
  "participants":[ { "id", "name", "accountId?", "roleId", "listening": true } ],
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
| `AddSongs` | `songIds`, `position: "next" \| "end"` | Add songs |
| `PlayNow` | `songId` | Play now |
| `JumpTo` | `itemId` (a queue or history item) | Play now |
| `RemoveQueueItem` | `itemId` | Remove own / others' songs |
| `MoveQueueItem` | `itemId`, `toIndex` | Reorder |
| `ShuffleQueue` | – | Reorder |
| `StartRadio` | `seedId` (song, artist, album or playlist) | Start radio |
| `AutoplayFromHere` | `seedId` (song, artist, album or playlist) | Autoplay from here |
| `LoadHistory` | `beforeItemId`, `limit` | – |

### Playback

| Command | Fields | Permission |
|---------|--------|------------|
| `Play` / `Pause` | – | Play/pause |
| `Skip` / `Previous` | – | Skip |
| `Seek` | `positionMs` | Seek |
| `SetRepeat` | `mode: "off" \| "queue" \| "one"` | Repeat |
| `VoteSkip` / `UnvoteSkip` | – | Vote to skip |
| `SetOutputs` | `outputIds` | Change host outputs |
| `SetOutputVolume` | `outputId`, `volume` | Host output volume |

### Client playback

| Command | Fields | Permission |
|---------|--------|------------|
| `SetListening` | `on` | Play audio locally |
| `RequestProxyStream` | `itemId` | Play audio locally. Sent when the direct URL fails (hybrid). The result contains the proxied URL. |

### People & roles

| Command | Fields | Permission |
|---------|--------|------------|
| `Kick` | `participantId` | Kick |
| `Ban` / `Unban` | `participantId` / `banId` | Ban / unban |
| `AssignRole` | `participantId`, `roleId` | Assign roles |
| `SetParticipantPermissions` | `participantId`, `permissions` | Assign roles |
| `CreateRole` / `UpdateRole` / `DeleteRole` | role | Edit roles |
| `SetDisplayName` | `name` | – (own name) |

### Room

| Command | Fields | Permission |
|---------|--------|------------|
| `UpdateSettings` | partial `settings` (name, visibility, ordering, access, limits, vote to skip, always on, …) | Change settings |
| `ChangeOwner` | `participantId` | Owner only |
| `CloneRoom` | `visibility: "public" \| "solo"` | Clone room. The result has the new room's code. |
| `SendMigration` | `targetRoom`, `mode: "invite" \| "forced"` | Send migration message |
| `CloseRoom` | – | Owner only |
| `RequestSnapshot` | – | – |

## Events (host → client)

| Event | Fields | Sent to |
|-------|--------|---------|
| `ParticipantJoined` / `ParticipantLeft` | participant | everyone |
| `ParticipantUpdated` | participant (role, permissions, name, listening) | everyone |
| `YouWereKicked` / `YouWereBanned` | – | that participant |
| `QueueItemsAdded` | `items`, `positions` | everyone |
| `QueueItemRemoved` | `itemId` | everyone |
| `QueueItemMoved` | `itemId`, `toIndex` | everyone |
| `QueueReplaced` | `items` (Start radio, Shuffle, fair-ordering re-sort) | everyone |
| `AutoplayReplaced` / `AutoplayExtended` | `seed?`, `items` | everyone |
| `NowPlayingChanged` | `item`, `streamUrl` (direct, see hybrid) | everyone |
| `HistoryAppended` | `item` (with `result: played/skipped`) | everyone |
| `PlaybackState` | `playing`, `positionMs`, `hostTimeMs` (on change, and periodically) | everyone |
| `RepeatChanged` | `mode` | everyone |
| `SkipVotesChanged` | `votes`, `needed` | everyone |
| `OutputsChanged` | `outputs` | everyone |
| `RoomSettingsChanged` | `settings` | everyone |
| `RolesChanged` | `roles` | everyone |
| `OwnerChanged` | `participantId` | everyone |
| `MigrationOffered` | `targetRoom`, `mode`, `from` | everyone |
| `AdminlessPrompt` | – (no admin for 5 min: "you can clone this room") | everyone |
| `RoomClosing` | `reason` | everyone |
| `YourSongIsNext` | `item` | the person who added it (if they turned it on) |
