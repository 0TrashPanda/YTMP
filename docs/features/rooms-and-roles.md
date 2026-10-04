# Rooms & Roles

Every YTMP instance runs in one of two modes: **host** or **client**.

## Host

- Owns a room: it keeps the **queue** and controls **playback**.
- Chooses which [outputs](playback-and-outputs.md) audio is played on.
- The person who creates the room is the **room admin** (see
  [room management](room-management.md)).

## Client

- Connects to a host's room.
- Can view the queue and, depending on permissions, add or remove songs.
- Can optionally **play the audio locally** on the client device as well
  (see [playback](playback-and-outputs.md#client-playback)).

## Starting or joining a room

### Via a YTMP server (web)

When YTMP runs on a server, it serves a website. A visitor picks one of these options:

1. **Join a room**
   - Enter a **room code**.
   - Joining as a guest requires a **temporary display name**.
   - A guest who reconnects (within the grace period) is recognised by a **cookie** and
     gets back their name, role and songs.
   - The visitor is now a client in that room.
2. **Host a room**
   - The visitor creates a new room and becomes its **admin**.
   - Who may create rooms is set in the **server config** (for example anyone, account
     holders only, or specific accounts).
   - They manage the queue and set permissions for everyone who joins.
3. **Solo room** ✅
   - A private room on the server that only its owner can join, the same as solo
     on Android. It can be made public later.
   - Built: with an account, only your account gets in, from any device, and your rooms
     are listed on the start page. Without one, only the browser that made it (it keeps the
     owner token); such a room is deleted after **30 days** unused. Everyone else, also
     people who know the code, can't see or join it.
   - A browser can keep **multiple solo rooms** and switch between them.
   - Go straight to your solo rooms with the **`/solo`** URL (for example
     `https://ytmp.example.com/solo`).
   - The browser **remembers** your solo rooms with a **cookie**, so you get them back
     without an account. With an account they are synced to the account instead.

### Via the Android app

- **Host**: the phone becomes the host. The user is immediately the admin and is also
  joined to their own room as a client. Other people on the **same local network**
  can connect to the phone as clients.
- **Solo** (a variant of host): the phone hosts and joins its own room, but the
  room is **local and private**, so nobody else can join. A solo room can be
  **switched to public** later, and then it behaves like a normal hosted room.
  A user can keep **multiple solo rooms** and switch between them
  (see [room lifecycle](room-lifecycle.md#multiple-solo-rooms)).
- **Join**: the phone acts as a client and joins an existing room.

## Room visibility

| Visibility | Who can join |
|------------|--------------|
| Private (solo) | Only you |
| Public | Anyone with the room code who passes the room's [access rules](room-management.md#access-control) |

## Leaving, cloning and moving a room

Admins leaving, changing the owner, cloning a room, migrating participants and
transfer are described in [room lifecycle](room-lifecycle.md).
