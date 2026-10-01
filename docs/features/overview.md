# Overview

## Vision

YTMP lets a group of people control **one shared music queue** together, the way
Spotify Jam does, but:

- built around **YouTube Music**, with no Premium subscription required
- **not tied to a single music service**: YouTube is the first source, others can follow
- usable from **almost any device**: an Android phone, or any browser connected to a
  YTMP server
- usable **without a central server**: an Android phone can do everything on its own

## Core concepts

| Term | Meaning |
|------|---------|
| **Host** | The device that owns a room. It keeps the queue and controls playback. |
| **Client** | A device connected to a room. It can view and (if allowed) change the queue. |
| **Room** | One shared queue plus its participants. Clients join it with a **room code**. |
| **Owner** | The creator of a room. Always an admin, and the only one who can change the owner or close the room. |
| **Room admin** | A participant who manages the room: permissions, kicking, settings. A room can have several admins. |
| **Guest** | A participant without a YTMP account. Joins with a temporary display name. |
| **Account** | An optional YTMP account. Used for room transfer, access control, playlists, listening history, and linking YouTube or media servers. |
| **Solo room** | A private room that only you are in (on a phone, or on a server through the browser). Nobody else can join until it is made public. |
| **Source** | Where music comes from: YouTube Music, YouTube, local files, and later Spotify, SoundCloud, … |
| **Output** | Where the host sends audio: device speakers, Sonos, Chromecast, … Clients can also play the audio themselves. |
| **Autoplay queue** | A backup queue per room that plays when the queue is empty. |

## Feature map

- [Rooms & roles](rooms-and-roles.md)
- [Joining](joining.md)
- [Room lifecycle](room-lifecycle.md)
- [Platforms](platforms.md)
- [Queue](queue.md)
- [Room management & permissions](room-management.md)
- [Accounts](accounts.md)
- [History, recap & radio](history-and-recommendations.md)
- [User interface](ui.md)
- [Sources](sources.md)
- [Playback & outputs](playback-and-outputs.md)
- [Server administration](server-admin.md)
- [Future ideas](future-ideas.md)
