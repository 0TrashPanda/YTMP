# Future Ideas

Ideas that are **not a priority now**, kept here so they are not forgotten.

## Use in a Discord call

Use YTMP while hanging out in a Discord voice call: a **shared queue/playlist** that
everyone in the call can edit and listen to together.

This already works: everyone in the call **joins the same YTMP room** and listens with
client playback. Nothing Discord-specific is needed.

## Discord bot output

A future **output module**: a Discord bot joins the voice channel and plays the room's
audio into it, so people in the call can hear it without joining the room themselves
(see [outputs](playback-and-outputs.md#host-outputs)).

## Watching videos together

With the [YouTube source](sources.md#youtube-source), a room could also show the
**video** in sync for everyone, for example in a Discord call. Not the primary focus.

## Stronger guest bans

Guests are banned by cookie, which is easy to get around (clear cookies, other browser).
A future feature could enforce guest bans more strongly (for example by device or IP).

## Native Linux app

A native desktop app for Linux, next to the browser and Android app.

## Phone → server migration

Move a phone-hosted room to an online server so it survives the phone leaving
(see [room lifecycle](room-lifecycle.md)). For your **own** rooms this is built as
[room sync](room-sync.md) (with an account); still open: doing it for a room you don't own,
or without an account.

## Upvotes in the queue (maybe)

Participants upvote songs in the queue, and popular songs move up. This would be a new
ordering mode next to fair ordering.

## Approval mode

Songs added by (some) participants only enter the queue after an admin approves them.
This could be a permission: *Add songs (needs approval)*.

## Reactions and room chat

Emoji reactions on the current song, and/or a small text chat in the room.

## YTMP likes

Liking songs without a linked YouTube account. These likes would be saved to the YTMP
account and could also feed the [song graph](history-and-recommendations.md#song-graph).

## More languages

The UI is English only for now. Translations (for example Dutch) could be added later.

## Graph database (maybe)

Store the [song graph](history-and-recommendations.md#song-graph) in a real graph
database (for example Neo4j or Memgraph) instead of normal tables, for bigger graphs or
more advanced recommendations. Not needed at the current scale (see
[storage](../implementation/storage.md#song-graph-planned-not-built-yet)).

## Spotify module

A full **Spotify source module**: search, albums, artists, playlists, radio, a linked
Spotify account (library, likes), and **importing Spotify playlists**.

- To research: playing Spotify audio needs a **Spotify Premium** account and goes through
  DRM / unofficial clients, which may be hard or against Spotify's terms. A fallback is to
  use Spotify for **search and metadata only**, and [match](../implementation/storage.md#song-matching)
  each song to a playable source (mostly YTM).

## Automatic sync with the microphone

Devices in the same room can drift apart audibly, mostly because of speaker and Bluetooth
delay, which the app can't see. A phone could fix this itself:

1. It records a few seconds of what is playing in the room with its microphone.
2. It compares the recording with the song it is playing (it has the same audio), and
   finds how far apart they are (cross-correlation).
3. It sets its own **sync adjustment** to that difference.

This covers the delay of every speaker in the room, including other people's Bluetooth
speakers. Needs microphone permission, and in a browser only works over HTTPS.

## Snapcast output

For perfectly synced multi-room audio, add **[Snapcast](https://github.com/badaix/snapcast)** as an
[output module](playback-and-outputs.md#host-outputs) instead of building that kind of sync
into YTMP:

- The host sends the room's audio to a **Snapcast server** (snapserver).
- Speakers and devices run a **Snapcast client** (snapclient), and Snapcast keeps them in sync
  to within about a millisecond, including each device's own output delay.
- YTMP keeps doing what it's good at (the shared queue); Snapcast does the precise playback.

## Settings look

The settings were reorganised (a quick menu behind the avatar, one Settings screen, see
[UI](ui.md)), but they still need a visual update: how the Settings screen and the room
settings look, and making them feel like one app (the room settings still look like Discord's).
