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
Link a file server, Plex server or other music server to search and add songs from it.
A server can have several addresses (local first, internet as fallback), and can be
shared with a room (see [sources](sources.md#where-sources-are-added)).

### Listening history
Optional history of every played song, used for recaps and radio/mix
(see [history](history-and-recommendations.md)).

### Linked YouTube account
A YTMP account can be linked to a **YouTube account**. This lets the user:

- see their YouTube Music **playlists** and **likes**
- **edit** playlists and likes from within YTMP

**On your own phone** ✅ (first step): in the app, the avatar in the room's header (or
*Library*) signs in to YouTube Music on **Google's own sign-in page**; YTMP never sees the
password. The sign-in stays **on that phone** (not in backups) and is only used for the
phone's owner:

- **Home** shows your own suggestions (your quick picks, mixes, listen again).
- **Library** (bottom bar, like YTM): your playlists (Liked music first) and podcasts
  (including New episodes); they open like other playlist and podcast pages.
- **Guests** in a room on your phone never see any of it: they get the general Home, and
  your library isn't reachable from other devices.
- **Like** ✅ (👍 next to the song in the full player; filled when you like it) and **Save to
  playlist** ✅ (also in the song menu): your own playlists, or a new private one.
- Playing songs doesn't use the sign-in (YouTube may flag accounts used for downloading).
- **YouTube Music history**: songs played on the phone (after 30 s, or to the end) are added
  to your YTM history, like YTM's own player does, so its suggestions learn from them. A
  setting in the profile menu: **Off**, **Solo rooms** (default), or **All rooms** on this
  phone (also what friends add in a party).
- **Use your account for search and radio** ✅: your searches, the suggestions while typing
  and *Related* go through your YouTube Music account, and so do the radio and autoplay of
  rooms that are yours (every room on your phone; on a server, rooms your account owns), so
  they fit your taste like in YTM. **On** by default; a switch in the profile menu. Friends'
  searches in your rooms never use it. If the sign-in stops working, search and radio carry
  on without it.
- **Like** ✅ is also in the song menu (right-click or ⋮).
- Signing out also forgets the sign-in page's session, so you can pick another account.

### YouTube Music on a server account ✅

On a YTMP server you can connect YouTube Music to **your YTMP account**: then your Home,
Library, likes, saving to playlists, the history setting and search and radio with your account work in rooms on that server, in
every browser where you're logged in. Only you see them.

- **Sign in with the YTMP app**: the app opens Google's sign-in page (after asking whether to
  send it to that server) and gives the sign-in to the server.
- **Paste from a browser**: copy the `Cookie` header of a music.youtube.com request (or the
  whole request, or *Copy as cURL*) from the developer tools and paste it.
- The server keeps the sign-in **encrypted** (with a key from its module key, not stored in
  its database) and only sends it to its YouTube Music module. Changing the module key
  means signing in again.
- History on a server: **Off**, **Solo rooms** (your solo rooms), or **All rooms** you're in.
- **One YouTube Music sign-in for both** ✅: in the app, a server page offers **"Use <name>
  from this phone"** when the phone is signed in (one confirm, no Google page). The other way,
  on the phone **"Use the one from your account on <server>"** takes the sign-in your server
  account has (one confirm). The server only gives the sign-in to you, logged in on its own
  pages; room hosts never get it.

Later: removing songs from your playlists, dislike; using your sign-in in rooms on someone
else's phone or server without giving that host your sign-in.

## Log in once ✅

- On a server, one login covers everything there: logging in on the account page also logs
  you in for its rooms (and your name is filled in when you join a room).
- In the app, logging in on your server's pages also logs in the phone's own pages, and the
  other way round. A browser on another device logs in once itself.
- Rooms on other people's hosts still ask first ("Join rooms on <host> as <name>?").

## Accounts-only servers ✅

A server setting (`accounts.guests = false`): without an account, people only see the login
page. Joining or creating rooms, search and everything else needs an account (of this
server, or a server it trusts). Speakers and TVs still get the audio.

## Creating an account

Who can create an account on a server is set in the **server config**: open sign-up,
invite only (the default), or only the server admin creates accounts. The **first account**
made on a new server becomes the **server admin**, who makes invite links and accounts and
can set someone's password (there is no e-mail).

Accounts are a **username and password**, shown as `username@server`. You always log in on
your own server's page; a room on another host only gets proof of who you are, never your
password.

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
