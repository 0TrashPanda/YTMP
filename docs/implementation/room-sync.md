# Room sync

Feature: [features/room-sync.md](../features/room-sync.md).

- Every room with an owner account has a **sync ID** and an **epoch** (`Room.syncId`,
  `Room.epoch`, also in `SavedRoom`). The epoch goes up by one each time the room moves.
- The account's **server is the hub** (`server/.../RoomHub.kt`): it knows its own rooms
  (`RoomManager`) and keeps a **copy** of rooms that live on a phone (table `room_copies`:
  home, epoch, the room as JSON, last seen).
- The **phone** (`android/.../host/RoomSync.kt`) sends a room to the hub whenever it's saved
  (`RoomManager.onRoomSaved`: on changes, and every 15 s while playing):
  `PUT /api/auth/rooms/{syncId}` with `RoomCopyRequest`, except when only time moved on since
  the last copy the server took (no seek, pause or song change): the server works out a
  playing room's position from `savedAt`, so that saves battery and data. Every 30 s it checks in
  (pages show a host as seen for a minute):
  `POST /api/auth/rooms/heartbeat` with its rooms' epochs. Both answer with rooms that **moved
  away** (`409` / `moved`): the phone closes them (`RoomManager.movedAway`), sending everyone
  `Rejected(room_moved, movedTo)`; later joins with the old code get the same.
- **Moving**: `POST /api/auth/rooms/{syncId}/take` (a phone) or `POST /api/rooms/elsewhere/{syncId}/move`
  (the server's page) returns the room's state with epoch + 1; the new home adopts it
  (`RoomManager.adopt`: same code if free, position moved on if it was playing, paused). A
  room taken from the server is closed there right away; one taken from a phone is closed at
  the phone's next check-in or save (an older epoch than the hub's is refused).
- Listing: `GET /api/auth/rooms` (hosts) and `GET /api/rooms/elsewhere` (pages; the phone
  forwards it to the hub with the page's token).
- Auth: the `/api/auth/rooms*` endpoints take a host token of one of the server's own
  accounts, for **any** host (like play reports): the phone keeps the newest token it saw for
  each account (`HostOptions.onAccountToken`), so it can sync in the background. Tokens last
  30 days; the phone's page refreshes it whenever it lists your rooms.
- Not yet: phone ↔ phone directly (it goes through the server), moving a room that's playing
  without a pause.
