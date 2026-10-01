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
(see [room lifecycle](room-lifecycle.md)).

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
[storage](../implementation/storage.md#song-graph)).

## Spotify module

A full **Spotify source module**: search, albums, artists, playlists, radio, a linked
Spotify account (library, likes), and **importing Spotify playlists**.

- To research: playing Spotify audio needs a **Spotify Premium** account and goes through
  DRM / unofficial clients, which may be hard or against Spotify's terms. A fallback is to
  use Spotify for **search and metadata only**, and [match](../implementation/storage.md#song-matching)
  each song to a playable source (mostly YTM).

## Party screen on a TV via Chromecast

A custom Cast receiver that shows the [party screen](ui.md#party-screen) on a TV.
