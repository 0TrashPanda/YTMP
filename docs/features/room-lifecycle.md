# Room Lifecycle: Leaving, Cloning, Migrating, Transfer

A room does not live forever on one device. This page covers what happens when people
leave, and how a room (or its queue) can move to another place.

> Status: ✅ built: changing the owner, leaving, the no-admin prompt and "always on" (servers),
> cloning (here, onto your phone, or onto your server) and moving people to a clone (invite or
> forced), restarts, and moving your own rooms between phone and server
> ([room sync](room-sync.md)). Not built: transfer of someone else's room, a local copy that
> survives closing the page.

## Changing the owner

The **owner** can **hand the room over to another participant** at any time (*Make Owner* in
the person's menu under *Members*). That participant becomes the owner and the room
continues as normal. The room code and host stay the same. The old owner keeps the **top
role** (Admin), but the new owner outranks them. The new owner's device gets proof of
ownership, like the device that created the room.

**Leave Room** (room settings) really leaves: you're gone from the list and your songs stay.
Coming back with the code makes you someone new. When the **owner** of a party room leaves,
they pick who takes over (the dialog suggests the person with the top role who has been here
longest), or **Nobody**: they stay the owner and can come back. Only Leave Room hands a room
over: an owner whose phone sleeps or loses Wi-Fi keeps the room. In a solo room, leaving
never hands it over.

A room with an owner account that gets an owner with another account (or a guest) stops
being the old owner's on their other devices ([room sync](room-sync.md)), and starts being the
new owner's. Its radio then uses the new owner's YouTube Music sign-in where there is one.

On a phone-hosted room, the phone stays the host. If the phone leaves, the room still ends
(see below).

## When the admin leaves

What happens depends on where the room is hosted.

### Server-hosted room

The room's data lives on the server, so the room **keeps existing** when the admin
disconnects. Participants can stay connected.

The room **keeps playing** while it has no admin (the owner, or someone with the top role).
If a party room has **no admin** connected for **5 minutes**:

1. Everyone still in the room sees *Nobody who runs this room is here*, with **Clone it to
   keep the queue going**. It goes away when an admin is back.
2. When everyone has left, the room is **deleted after 1 hour**.
3. A room can be set to **never be deleted** (**Always on** in the room's Overview, servers
   only), for example a permanent room on a home server.

See [networking](../implementation/networking.md#default-timeouts) for all timeouts.

### Phone-hosted room

The phone *is* the host. When it disconnects, the room ends.

A restart of the app (an update, Android stopping it, a crash) is not the end: the phone
keeps its rooms, public and solo, and brings them back with the same code, queue, history
and participants. Playback comes back **paused** where it was. Friends' devices reconnect on
their own. A public room is still deleted after the usual timeout without anyone connected.

To avoid losing the queue, every page in the room has a copy of it (it gets it anyway to
show it). When the host disappears, the page shows *Connection lost* with **Keep the queue:
clone it**, which works from that copy, as long as the page stays open. (Planned: keeping
the copy after the page closes.)

> Later idea: a **phone → server migration**, where a phone-hosted room is moved to an
> online server so it survives the phone leaving.

## Cloning a room

Anyone with the *Clone Room* permission (everyone, in the default roles) can **clone a
room**: **Clone Room** in the room settings, *Clone this room somewhere else* in the share
sheet, or the banners above. The clone is a **new, independent room** with a **new room
code**, owned by the person who cloned it. It starts with this room's queue, what played
(the last 200 songs), the autoplay queue, the roles and settings, and the current song
**paused** where the room was. The old room stays as it is.

Where it goes is picked each time, from what this page can reach:

| Where | When |
|-------|------|
| **This server** | On a server's pages (anyone who may create rooms there) |
| **This phone** | In the app, on any other host's page (a server, or a friend's phone) |
| **Your server** | On the phone's own pages, logged in, with the phone linked to your server |
| (the phone itself) | On the phone's own pages |

And whether it's a **party** or a **solo** room, for example to keep listening to the rest of
the party's queue at home afterwards. A friend's browser on someone's phone can't make rooms
there; it's told to open YTMP on their own phone or a server.

Use cases:

- The original room is about to disappear, for example because the phone host is leaving.
- You want to **keep the queue but change it** without affecting the party, even
  while the original room is still running. If you only want to keep listening,
  you can just stay connected.

## Migration

While cloning, someone with the *Move Everyone* permission (Admin, in the default roles) can
bring the others along:

| Variant | Behaviour |
|---------|-----------|
| Don't tell them | The old room is not notified. |
| Invite | Everyone in the old room sees *<name> moved the party to <room>* with **Go there** and **Stay here**. People with the permission can **take it back**. Who joins later sees it too. |
| Move everyone | Everyone is sent to the new room right away (their page opens it), and the music stops in the old one. The old room stays, empty, until it times out. |

Participants **keep their role**, their own permissions and their place in the new room:
each person's page has a private key from the old room that the new room checks against a
fingerprint in the copy, once. Songs they added stay theirs (so *Remove own songs* works).
Someone who only knows another person's name can't take their role.

## Transfer

**Transfer** moves a room to another device of the **same user** (for example
phone → PC), taking **everything** with it: queue, history, playback position and
settings. It requires a [YTMP account](accounts.md).

> ✅ Built between a phone and your server as [room sync](room-sync.md): your rooms are listed
> on both, and opening one on the other device moves it there (or opens it where it is).
> The table below is the fuller plan.

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
