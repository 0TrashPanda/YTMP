# YTMP — Documentation

**YTMP** ("YouTube Music Player") is a shared, collaborative music queue.
It started as "Spotify Jam, but for YouTube Music and without Premium" and has
grown over time. The goal is for it to support more than just YouTube.

The documentation is split into two parts:

- **`features/`**: *what* YTMP does, from the user's point of view. No technology choices.
- **`implementation/`**: *how* we plan to build it, including architecture, tech stack and modules.

Questions that are still open are collected in [`open-questions.md`](open-questions.md).

## Features

| File | Topic |
|------|-------|
| [features/overview.md](features/overview.md) | Vision, core concepts, glossary |
| [features/rooms-and-roles.md](features/rooms-and-roles.md) | Host / client / solo, rooms, joining, visibility |
| [features/joining.md](features/joining.md) | Room codes, finding hosts on the LAN (auto, QR, IP) |
| [features/platforms.md](features/platforms.md) | What each device type can do, Android app features |
| [features/room-lifecycle.md](features/room-lifecycle.md) | Admin leaving, changing the owner, cloning, migration, transfer, multiple solo rooms |
| [features/queue.md](features/queue.md) | Queue, autoplay queue, history, fair ordering, shuffle, repeat, vote to skip, limits |
| [features/room-management.md](features/room-management.md) | Permissions, kicking, access control, co-admins |
| [features/accounts.md](features/accounts.md) | Optional accounts, room transfer, playlists, linked YouTube account and media servers |
| [features/history-and-recommendations.md](features/history-and-recommendations.md) | Listening history, recap, song graph, radio/mix, find similar |
| [features/ui.md](features/ui.md) | YTM-like UI, gestures, song menu, party screen, themes |
| [features/sources.md](features/sources.md) | Where music comes from (YTM, YT, local files, …) |
| [features/playback-and-outputs.md](features/playback-and-outputs.md) | Where music is played (host outputs, client playback) |
| [features/server-admin.md](features/server-admin.md) | Admin page, automatic yt-dlp updates |
| [features/future-ideas.md](features/future-ideas.md) | Not-now ideas: Snapcast output, mic sync, Discord bot, videos, native Linux app, upvotes, approval mode, chat, YTMP likes, graph database, … |

## Implementation

| File | Topic |
|------|-------|
| [implementation/dev-setup.md](implementation/dev-setup.md) | Android version, repo layout, tooling, conventions, milestones |
| [implementation/architecture.md](implementation/architecture.md) | Overall architecture, tech stack, deployment shapes |
| [implementation/modules.md](implementation/modules.md) | Source and output module system |
| [implementation/ytm-module.md](implementation/ytm-module.md) | YouTube Music source module (service and on-device) |
| [implementation/playback-sync.md](implementation/playback-sync.md) | Client playback, stream URLs, clock sync |
| [implementation/auth.md](implementation/auth.md) | Auth servers, verifying accounts across servers |
| [implementation/protocol.md](implementation/protocol.md) | Client ⇄ host protocol design, TS type generation |
| [implementation/protocol-messages.md](implementation/protocol-messages.md) | Commands, events and room snapshot (draft) |
| [implementation/module-api.md](implementation/module-api.md) | HTTP API for remote source modules (draft) |
| [implementation/storage.md](implementation/storage.md) | Database (SQLite/PostgreSQL), song graph storage, radio generation |
| [implementation/android-app.md](implementation/android-app.md) | Android app structure, native audio playback, app updates |
| [implementation/downloads.md](implementation/downloads.md) | How downloads are made (tagging, formats) |
| [implementation/chromecast.md](implementation/chromecast.md) | Chromecast output module |
| [implementation/networking.md](implementation/networking.md) | LAN discovery, QR, room code generation, transport security, default timeouts |
