# Module System

> Status: **decided: option C (hybrid)**. The other options are kept below for reference.

YTMP has two kinds of modules:

| Kind | Responsibility | Examples |
|------|----------------|----------|
| **Source** (input) | Search, metadata, resolving a playable stream | YTM, YT, local files, media servers (Plex, Jellyfin, Subsonic, file servers), Spotify, SoundCloud |
| **Output** | Playing a stream somewhere | Local audio, Sonos (HTTP), Chromecast, Discord bot (future) |

## Modules and source instances

A **module** is code (one per kind of service). A **source** is a configured instance of a
module (see [features/sources](../features/sources.md#modules-vs-sources)):

```
source = { id, module: "plex", owner: host | account, name,
           addresses: ["http://192.168.1.10:32400", "https://plex.example.com"],
           credentials (encrypted), sharedWithRooms: [...], downloadsEnabled }
```

- **Host sources** are defined in the server config (or the phone's settings).
- **Account sources** are stored with the account on the auth server. When the user joins a
  room, the host loads them and creates a source instance for that room.
- **Addresses** are tried in order. The first one that answers a health check is used,
  re-checked on errors and every few minutes.
- **Songs sent from a client device** go into a temporary per-room upload store on the host,
  exposed as a hidden "uploads" source for that room.

## Requirements

- A host can have **multiple sources** active, so a queue can mix sources.
- Stream URLs are resolved **on demand** (they can expire).
- The YTM source is written in **Python**. On a server it runs as a separate service, and
  on Android it runs embedded through Chaquopy.
- The same source can have multiple implementations (YTM: server service vs. on-device).
- Must run on both the **Linux server** and **Android**.

## Options

### A. Compiled-in Kotlin modules

Each module is a Kotlin class implementing `SourceModule` / `OutputModule`, built into the
app and turned on or off in the config.

- ➕ Simplest. Type-safe, one build, easy to debug.
- ➖ Adding a module means rebuilding the app. Python modules need a special-case wrapper.

### B. Every module is a separate service

Every module is its own process (or container) that speaks a common HTTP/WebSocket API.

- ➕ Any language. Modules can be restarted and updated on their own, and the server can
  run them in Docker.
- ➖ Doesn't fit Android, where you can't run arbitrary services. More moving parts for
  simple modules like local files.

### C. Hybrid: one Kotlin interface, local or remote implementations ⭐ chosen

One Kotlin interface (`SourceModule`, `OutputModule`). An implementation is either:

- **native**: Kotlin code in the app (local files, Sonos output, Chromecast output, …)
- **remote**: a generic adapter that speaks a small, documented **module HTTP API** to an
  external service (the Python YTM service on a server, which can also serve the YT
  source since yt-dlp handles both)
- **embedded Python**: an adapter that calls Python through Chaquopy (YTM on Android)

The host core only sees the interface, so it doesn't care where a module runs.

- ➕ Fits both server and Android. Simple modules stay simple. New external modules can be
  written in any language without changing the app.
- ➖ Two or three adapter types to maintain.

### D. Runtime plugin loading (JARs/PF4J)

Modules are JAR files that are loaded at runtime from a plugins folder.

- ➕ Add modules without rebuilding.
- ➖ Doesn't work well on Android (class loading). Overkill for a project for friends.
