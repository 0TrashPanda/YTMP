# Room Lifecycle: Leaving, Cloning, Migrating, Transfer

A room does not live forever on one device. This page covers what happens when people
leave, and how a room (or its queue) can move to another place.

## Changing the owner

Before leaving, the **owner** can **hand the room over to another participant**. That
participant becomes the owner and the room continues as normal. The room code and
host stay the same.

If the owner **leaves without passing the room on**, ownership goes automatically to the
**admin who has been in the room the longest**. If there are no other admins, see
*When the admin leaves* below.

On a phone-hosted room, the phone stays the host. If the old owner's phone leaves, the
room still ends (see below).

## When the admin leaves

What happens depends on where the room is hosted.

### Server-hosted room

The room's data lives on the server, so the room **keeps existing** when the admin
disconnects. Participants can stay connected.

The room **keeps playing** while it has no admin. If the room has **no admin** for a
set amount of time:

1. Everyone still in the room is **prompted that they can clone the room**.
2. When there is no admin **and** everyone has left, the room is **deleted after a
   timeout**.
3. A room can be configured to **never be deleted** ("always on"), for example a
   permanent room on a home server.

Defaults: prompt after **5 minutes** without an admin, delete after **1 hour** without
an admin and without participants. Both are configurable (see
[networking](../implementation/networking.md#default-timeouts)).

### Phone-hosted room

The phone *is* the host. When it disconnects, the room ends.

A restart of the app (an update, Android stopping it, a crash) is not the end: the phone
keeps its rooms, public and solo, and brings them back with the same code, queue, history
and participants. Playback comes back **paused** where it was. Friends' devices reconnect on
their own. A public room is still deleted after the usual timeout without anyone connected.

To avoid losing the queue, **clients using the app** keep a local copy of the room's
queue (they receive it anyway to display it). When the host disappears, they can
still **clone the room** from that local copy. Browser clients do not keep a copy.

> Later idea: a **phone → server migration**, where a phone-hosted room is moved to an
> online server so it survives the phone leaving.

## Cloning a room

Any participant (if permitted) can **clone a room** to their own device, either a
browser or an Android app. The clone is a **new, independent room** with a **new room
code**, owned by the person who cloned it.

The clone can be:

- **Public**: others can join it.
- **Private / solo**: for example to keep listening to the rest of the party's queue
  at home after the party.

Use cases:

- The original room is about to disappear, for example because the phone host is leaving.
- You want to **keep the queue but change it** without affecting the party, even
  while the original room is still running. If you only want to keep listening,
  you can just stay connected.

## Migration

After a room is cloned, the person who made the clone can choose to show a
**migration message** in the old room, inviting participants to move to the new one.
Whether this is allowed depends on permissions in the old room.

Variants:

| Variant | Behaviour |
|---------|-----------|
| No migration | The old room is not notified. |
| Invite | Participants of the old room get a message and can choose to follow. |
| Forced | All participants are moved to the new room. Anyone who **refuses leaves** the room. |

Participants **keep their role** when they migrate to the new room.

## Transfer

**Transfer** moves a room to another device of the **same user** (for example
phone → PC), taking **everything** with it: queue, history, playback position and
settings. It requires a [YTMP account](accounts.md).

It can be started from either side, or from the account:

| Start from | Action |
|------------|--------|
| The device that has the room | **Transfer to…** → pick one of your devices |
| The device you want it on | **Transfer here** → pick a room from one of your other devices |
| Your account | Pick any of your rooms and the device it should go to |

- Mainly intended for **solo** rooms.
- *Transfer here* from a phone that is **offline** still works for synced solo rooms: the
  room is taken from the copy synced to your account.
- For a **public** room, a transfer works like **clone + migration**: a new room is
  created on the target device and participants are invited, or forced, to migrate.

## Multiple solo rooms

On a device, a user can have **several solo rooms** side by side, for example
one for calm music and one for metal, and switch between them to go back to an
earlier queue. This works like having multiple YouTube Music tabs open in a browser.

- Solo rooms **survive app restarts**.
- With a YTMP account they are **synced** to the account, so they are available on your
  other devices too.
