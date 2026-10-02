# Room Management & Permissions

The creator of a room is its **owner** and first admin. Admins control who can join
and what each participant can do. The owner can be changed
(see [room lifecycle](room-lifecycle.md#changing-the-owner)).

## Access control

An admin chooses who can join the room:

- **Everyone**: guests (with a temporary name) and account holders
- **Accounts only**: guests cannot join, a YTMP account is required
- Private / solo: nobody else (see [rooms](rooms-and-roles.md#room-visibility))

A phone host can use a YTMP server for account authentication. If it does not, only
guests can join (see [accounts](accounts.md#authentication-on-phone-hosts)).

## Participant management

- **Kick** a participant: they are removed but **can rejoin** right away.
- **Ban** a participant: they are removed and **cannot rejoin**. Account holders are
  banned by account, guests by their cookie (so a guest ban is easy to get around).
  Admins can **unban** people. Stronger guest bans are a
  [future idea](future-ideas.md#stronger-guest-bans).
- **Assign roles** or individual permissions to participants.

## Permissions

Permissions are **very granular**. Everyone in a room has **one role**: a named set of
permissions. On top of their role, a person can get **their own overrides** per permission:
allow (✓), deny (✗), or follow the role (/), like Discord's channel overrides. The
**owner** can always do everything.

Roles are **ranked** (top = highest), like Discord: you can only kick, ban, change the role
or permissions of people whose role is **below yours**, only give or edit roles below
yours, and never hand out a permission you don't have yourself. Nobody can manage the owner.

The settings screen (people icon or gear in the room bar) looks like Discord's server
settings: **Overview** (name, default roles), **Roles** (ranked list, drag to reorder; each
role has *Display*, *Permissions* and *Manage Members* tabs, and an unsaved-changes bar),
**Members** (role per person, per-person permissions, kick, ban) and **Bans**.

### Implemented permissions

| Group | Permissions |
|-------|-------------|
| **Queue** | Add songs · Play now (also jumping in the queue/history) · Remove own songs · Remove others' songs · Reorder · Start radio · Autoplay from here |
| **Playback** | Play/pause · Skip (and previous) · Seek |
| **Listening** | Play audio on own device |
| **Speakers** | Change speakers · Speaker volume |
| **People** | Kick · Ban / unban · Manage members (give roles, own permissions) · Manage roles |
| **Room** | Manage room (name, default roles) |

Default roles: **Admin** (everything), **DJ** (Listener + play now, reorder, remove others'
songs, seek, speakers and volume, start radio, autoplay from here) and **Listener** (add songs, remove own songs, play/pause,
skip, play on own device). New guests and account holders get *Listener*.

### All planned permissions

### Permission groups (initial list, to be refined)

Permissions are **grouped**. In the UI, an admin can toggle a whole group or open it to
set individual permissions.

| Group | Permissions |
|-------|-------------|
| **Queue** | Add songs · Send songs from own device · Play now · Remove own songs · Remove others' songs · Reorder · Start radio (replaces queue) · Autoplay from here (replaces autoplay queue) |
| **Playback control** | Play/pause · Skip · Seek · Repeat · Vote to skip · Host output volume |
| **Listening** | Play audio locally on own device |
| **Outputs** | Change host outputs |
| **People** | Kick · Ban / unban · Assign roles · Edit roles |
| **Room** | Change settings (ordering mode, access control) · Clone room · Send migration message in this room |

### Roles

Roles exist on **two levels**:

- **Account role templates**: YTMP ships with **default roles** (*Admin*, *DJ*,
  *Listener*). Users with a **YTMP account** can edit them and create their own, on their
  account page (*Your roles*), in the same Discord-style editor.
  These are saved to the account and applied to every room they create, so nobody is
  stuck with defaults they don't like.
- **Per-room roles**: inside a room, admins can adjust the roles or add new ones for
  that room only, without changing their templates.

## Room defaults

- The **default role for new joiners** is a room setting (it can differ for guests and
  account holders).
- Account holders can save their **preferred room defaults** (default roles, role
  templates, ordering mode, access control, …) to their account. These are applied to
  every room they create.

## Room settings

- **Room name**: shown in the room bar and in LAN discovery. The default is the
  creator's name + "'s room" (for example `Jonah's room`), and it can be edited.
- Visibility: public or private (see [rooms](rooms-and-roles.md#room-visibility))
- Default role for new joiners (guests / account holders)
- Queue ordering mode, for example **fair / round-robin**
  (see [queue](queue.md#ordering-modes))
- Access control (see above)
- Migration behaviour (see [room lifecycle](room-lifecycle.md#migration))
- Vote to skip and its threshold (off by default, see [queue](queue.md#vote-to-skip))
- Queue limits (off by default, see [queue](queue.md#queue-limits))
- "Always on" (never delete the room; server-hosted rooms only, see [room lifecycle](room-lifecycle.md#server-hosted-room))
