# Accounts

YTMP accounts are **optional**. Everything basic (hosting, joining as a guest)
works without logging in.

## What an account is for

### Room transfer
Move a room to another of your devices, for example from a phone to a PC, taking
everything with it. Can be started from the current device, the target device, or your account. See
[room lifecycle](room-lifecycle.md#transfer).

### Access control
Room admins can restrict a room to **account holders only**
(see [room management](room-management.md#access-control)).

### Custom roles
Edit the default roles and create your own. They are applied to rooms you create
(see [roles](room-management.md#roles)).

### Playlists & preferences
YTMP playlists, your [song menu](ui.md#song-menu) layout, your [theme](ui.md#themes)
and your room defaults are saved to your account.

### YTMP playlists

- A playlist can contain songs from **different sources** (YTM, local files, media
  servers, …).
- Playlists are **private** or **public**.
- They can be **shared** with other people, with a permission per person: **view** or
  **edit**.
- Playlists can be **imported** from YouTube Music. Importing from Spotify is part of
  the future [Spotify module](future-ideas.md#spotify-module).
- With a linked YouTube account you can also **create** YTM playlists.

### Linked media servers
Link a file server, Plex server or other music server to search and add songs from it
(see [sources](sources.md#media-servers)).

### Listening history
Optional history of every played song, used for recaps and radio/mix
(see [history](history-and-recommendations.md)).

### Linked YouTube account
A YTMP account can be linked to a **YouTube account**. This lets the user:

- see their YouTube Music **playlists** and **likes**
- **edit** playlists and likes from within YTMP

## Creating an account

Who can create an account on a server is set in the **server config** (for example
open sign-up, invite only, or only the server admin creates accounts).

## Auth servers

There can be **multiple YTMP servers**, and each can act as an **auth server**. An
account belongs to one auth server.

- To join a room **with your account**, the room's host must use your account's auth
  server, **or** a server that **trusts** it. Servers can be configured to trust other
  servers.
- Otherwise, you can still join as a **guest** if the room allows guests.

## Authentication on phone hosts

A phone host has no account database of its own. It can:

- **use a YTMP server for authentication**, so account holders can join and
  "accounts only" rooms work, or
- **not use a server**, so only **guests** can join.

Implementation: [implementation/auth.md](../implementation/auth.md).
