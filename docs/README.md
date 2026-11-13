# YTMP — Documentation

**YTMP** ("YouTube Music Player") is a shared, collaborative music queue.
It started as "Spotify Jam, but for YouTube Music and without Premium" and has
grown over time. The goal is for it to support more than just YouTube.

The documentation is split into two parts:

- **`features/`**: *what* YTMP does, from the user's point of view. No technology choices.
  Grouped in `rooms/`, `music/`, `accounts/` and `ui/`.
- **`implementation/`**: *how* it's built (and how planned parts will be), including architecture, tech stack and modules.
  Grouped in `protocol/`, `apps/`, `sources/` and `outputs/`.

Questions that are still open are collected in [`open-questions.md`](open-questions.md).

## Features

| Page | Topic |
|------|-------|
| [Overview](features/overview.md) | Vision, core concepts, glossary |
| [Platforms](features/platforms.md) | What each device type can do, the Android app, the desktop app |
| [Playback and outputs](features/playback-and-outputs.md) | Where music plays (speakers, your devices, in sync), equalizer, sleep timer |
| [Future ideas](features/future-ideas.md) | Not-now ideas: Snapcast output, mic sync, Discord bot, videos, upvotes, approval mode, chat, … |

**Rooms** (`features/rooms/`)

| Page | Topic |
|------|-------|
| [Rooms and roles](features/rooms/rooms-and-roles.md) | Host / client / solo, rooms, joining, visibility |
| [Joining](features/rooms/joining.md) | Room codes, finding hosts on the LAN (auto, QR, IP) |
| [Sharing](features/rooms/sharing.md) | Sharing rooms and songs: links, holding phones together (NFC), the song screen |
| [Queue](features/rooms/queue.md) | Queue, autoplay queue, history, fair ordering, shuffle, repeat, vote to skip, limits |
| [Room management](features/rooms/room-management.md) | Permissions, kicking, access control, co-admins |
| [Room lifecycle](features/rooms/room-lifecycle.md) | Admin leaving, changing the owner, cloning, migration, transfer, multiple solo rooms |
| [Room sync](features/rooms/room-sync.md) | Your rooms on all your devices: one home, moving between phone and server |

**Music** (`features/music/`)

| Page | Topic |
|------|-------|
| [Sources](features/music/sources.md) | Where music comes from: YouTube Music, local music, the song cache, downloads |
| [History and recommendations](features/music/history-and-recommendations.md) | Listening history, recap, song graph, radio/mix, find similar |
| [Importing playlists](features/music/importing-playlists.md) | Importing playlists from a Spotify export into YouTube Music |

**Accounts** (`features/accounts/`)

| Page | Topic |
|------|-------|
| [Accounts](features/accounts/accounts.md) | Optional accounts, playlists, linked YouTube account and media servers |
| [Friends](features/accounts/friends.md) | Friends, groups of friends, sharing songs; *From your friends* on Home |
| [Server admin](features/accounts/server-admin.md) | Admin page, backups, updates, logs |

**The app's screens** (`features/ui/`)

| Page | Topic |
|------|-------|
| [User interface](features/ui/README.md) | YTM-like UI: Home, navigation, settings, podcasts, gestures, keyboard, themes |
| [Player](features/ui/player.md) | Mini player, full player, Up next, lyrics |
| [Search](features/ui/search.md) | Search, chips, search history |
| [Song and card menus](features/ui/menus.md) | The ⋮ menus, holding for choices, share links, downloads |
| [Party screen](features/ui/party-screen.md) | The big screen for a TV, laptop or tablet |

## Implementation

| Page | Topic |
|------|-------|
| [Architecture](implementation/architecture.md) | Overall architecture, tech stack, deployment shapes, where things are in the code |
| [Dev setup](implementation/dev-setup.md) | Running it locally, repo layout, tooling, conventions, releases |
| [Auth](implementation/auth.md) | Auth servers, verifying accounts across servers, friends, settings |
| [Storage](implementation/storage.md) | Database (SQLite/PostgreSQL), song graph storage, radio generation |
| [Room sync](implementation/room-sync.md) | Room sync: hub on the server, copies, epochs, moving |
| [Networking](implementation/networking.md) | LAN discovery, QR, room code generation, transport security, default timeouts |

**Protocol** (`implementation/protocol/`)

| Page | Topic |
|------|-------|
| [Protocol](implementation/protocol/protocol.md) | Client ⇄ host protocol design, TS type generation |
| [Protocol messages](implementation/protocol/protocol-messages.md) | Commands, events and room snapshot |

**Apps** (`implementation/apps/`)

| Page | Topic |
|------|-------|
| [Android app](implementation/apps/android-app.md) | Android app structure, native audio playback, app updates |
| [Desktop app](implementation/apps/desktop-app.md) | Desktop app for Windows and Linux: Tauri, the module and host on the computer, packaging |
| [Home layout](implementation/apps/home-layout.md) | Home on a computer: sections that fill the width, speed dial pages on a phone |
| [Tap to share](implementation/apps/tap-to-share.md) | Holding phones together: the NFC tag (host card emulation), the bridge, the song screen |

**Sources** (`implementation/sources/`)

| Page | Topic |
|------|-------|
| [Modules](implementation/sources/modules.md) | Source and output module system |
| [Module API](implementation/sources/module-api.md) | HTTP API for remote source modules |
| [YTM module](implementation/sources/ytm-module.md) | YouTube Music source module (service and on-device) |
| [Local music](implementation/sources/local-music.md) | A Raspberry Pi's music folder (MPD), the music on the phone, the Local tab's API |
| [Song cache](implementation/sources/song-cache.md) | The song cache on server and phone: storing, serving, removing |
| [Downloads](implementation/sources/downloads.md) | How downloads are made (tagging, formats) |
| [Playlist converter](implementation/sources/playlist-converter.md) | Readers, matching, stored jobs, writers |
| [Song recognition research](implementation/sources/song-recognition-research.md) | Why YTMP can't reuse YouTube Music's own recognizer (keep Shazam) |

**Outputs** (`implementation/outputs/`)

| Page | Topic |
|------|-------|
| [Playback sync](implementation/outputs/playback-sync.md) | Client playback, stream URLs, clock sync |
| [Chromecast](implementation/outputs/chromecast.md) | Chromecast output module |
| [Sonos](implementation/outputs/sonos.md) | Sonos output module |
| [YouTube TV](implementation/outputs/youtube-tv.md) | TVs with only the YouTube app (DIAL + Lounge API) |
| [MPD](implementation/outputs/mpd.md) | MPD output module (e.g. a Raspberry Pi on a speaker) |
| [Zones](implementation/outputs/zones.md) | Amplifier zones: the zones file and its building blocks, setting up the Pi |
