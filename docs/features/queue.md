# Queue

The host keeps **one queue per room**: the list of songs that will be played.

> Status: all built: the queue actions, the autoplay queue, radio, queue history, playlists
> into autoplay, fair order, shuffle, repeat, vote to skip and queue limits.

## Actions

| Action | Who |
|--------|-----|
| View the queue, autoplay queue and current song | Every participant |
| Add songs (to the start or end) | Participants with *add songs* permission |
| Remove songs | *Remove own songs* / *Remove others' songs* permission |
| Play now | *Play now* permission |
| Reorder (drag) | *Reorder* permission |
| Replace the queue / autoplay queue with a radio | *Start radio* / *Autoplay from here* permission |
| Shuffle the queue | *Reorder* permission |
| Change repeat mode | *Repeat* permission |
| Skip right away | *Skip* permission |
| Vote to skip | *Vote to skip* permission (when the room votes; otherwise it skips right away) |

Permissions are set by room admins. See [room management](room-management.md#permissions).

Songs can come from any enabled [source](sources.md), so one queue can mix, for
example, YouTube Music tracks and local files.

## No double songs ✅

Room setting (*Queue*, and in your role template): a song that is in the queue already can't
be added again. The ones left out are named in the message ("Not added: already in the
queue"); the rest of what was added goes in. Admins aren't held to it, like the other queue
limits, and it doesn't look at history or the autoplay queue.

## Undo ✅

Removing a song from the queue, or clearing the queue, shows *Removed "…" · **Undo*** for 8
seconds. Undo puts the songs back where they were (they count as added by whoever undoes
it, and need the same *add songs* permission). It's for the queue; history and autoplay
removals aren't undoable.

## Autoplay queue

Next to the queue, a room has a second **autoplay queue**, a backup for when the
queue runs out.

- When the **last song of the queue** finishes, the next song is taken from the
  **front of the autoplay queue**.
- As soon as someone adds songs to the queue again, those play first.
- The autoplay queue is filled with **Autoplay from here** from the [song menu](ui.md#song-menu). This
  **replaces** the current autoplay queue with songs similar to that song (see [radio](history-and-recommendations.md#radio--mix)).
  It keeps generating, so it never runs empty.
- **Autoplay room setting** (on by default, in the room's Overview and in role templates):
  without an explicit *Autoplay from here*, the room fills the autoplay queue itself with a
  radio from the last song, like YouTube Music, as soon as the queue is down to one song.
  Turned off, the music stops when the queue ends (and the autoplay queue is cleared).
- Autoplay and radio songs skip anything from the last 50 songs, the queue and the autoplay
  queue. If a radio only has such songs, a couple of other recent songs are tried as seed.
- **Start radio** clears the queue and puts the song's radio in the autoplay queue, the song
  itself first; it then plays from there, so the song is heard once and the radio follows.
  On the song that's **already playing**, it keeps playing (like YTM) and only the queue changes.
- **Start radio clears played songs** ✅ (*Settings → Playback*; off by default):
  Start radio also clears the [history](#queue-history) above the current song, like YouTube
  Music. Only when you may remove those songs (your own, or others' with *Remove others'
  songs*); otherwise the history stays. Radio and autoplay still skip the cleared songs for
  a while (they count among the last 50 songs).
- **Autoplay this** (called *Autoplay from here* in older notes) replaces only the autoplay
  queue.
- **Clear queue** and **Clear** (autoplay) empty the queue or the autoplay queue. A cleared
  autoplay queue stays empty until something new is played or started (otherwise it would
  refill right away).
- The autoplay queue is visible below the queue. Tapping a song there plays it; the
  autoplay songs before it are dropped.

## Queue history

The queue keeps its **history**: songs that have played stay in the queue view, above
the current song. **Scroll up** to see what has played.

- Each song shows whether it was **played** or **skipped**, and who added it.
- Songs in the history have the same [song menu](ui.md#song-menu) as any other song.
- The queue history is part of the room, so it is kept when the room is **cloned**,
  **migrated** or **transferred**.

This is separate from the per-account [listening history](history-and-recommendations.md),
which follows a person across rooms.

## Ordering modes

### Default
Songs play in the order they are in the queue.

### Fair / round-robin ✅
The admin can enable an ordering mode that **spreads songs out per person**, so
one participant cannot take over the queue. It's the **Fair order** switch in the room's
settings (*Queue*), and in your role template, so every room you make starts with it.

Example: P1 added 5 songs, P2 added 3, P3 added 2:

```
P1 P2 P3 P1 P2 P3 P1 P2 P1 P1
```

Rules:

- The queue goes **round by round** over every participant who has songs queued, and plays
  **one song per person** per round. People without songs are skipped.
- A newly added song goes into the **next round where that person has no song yet**.
  So even if the queue has 50 songs, someone who has no songs queued yet lands near the top.
- The person whose song is **playing now** has had their turn: their next song comes after
  everyone else's in the first round.
- **Play next** puts the song in your next turn too, unless you have *Reorder*.
- Turning fair order **on** puts the queue in rounds right away (each person's songs keep
  their order).
- When someone **leaves the room, their songs stay** in the queue and keep their place in
  the rounds.
- People with the *Reorder* permission can still **drag songs** to any position. A moved
  song stays where it was put.
- Radio songs (from **Start radio** and **Autoplay from here**) belong to the **person who started
  the radio**. They're in the autoplay queue, so songs others add play first anyway.
- Songs from the **autoplay queue** only play when the queue is empty, so fair ordering
  does not apply to them. They still show who started the radio.

## Shuffle ✅

**Shuffle** (next to the controls in the player, like YTM) is a one-time action that randomises the order of the **upcoming** songs
(like YTM). It does not change the queue history.

- With **fair ordering** on, shuffle keeps the rounds: it randomises each person's
  songs and the order within each round, so nobody gets more turns.

## Repeat ✅

The repeat button next to the controls goes round, like YTM's: off, repeat queue, repeat one.

| Mode | Behaviour |
|------|-----------|
| Off (default) | When the queue ends, the [autoplay queue](#autoplay-queue) takes over |
| Repeat queue | When the queue ends, the songs it played are queued again: the songs since the queue last started playing (not autoplay's), at most 500 |
| Repeat one | The current song starts over when it ends (each time counts as played in the listening history). Skipping still goes to the next song |

With repeat on, speakers and TVs don't move on to the next song by themselves; the room
tells them what plays next.

## Vote to skip ✅

A room setting, **off by default** (in the room's settings under *Queue*, and in role
templates). When on, people with the *Vote to skip* permission (Listeners, by default) vote
instead of skipping: their Next button becomes a vote, shows the votes so far (like 1/2), and
pressed again takes the vote back. The song is skipped once enough people voted: a share of
the people here who may skip or vote (25% to everyone; 50% by default, rounded up). People
with *Skip* (DJs, Admins, the owner) still skip right away. A new song starts a new vote.

- When people leave, fewer votes are needed, which can skip the song right then.
- With voting off, *Vote to skip* skips right away, so Listeners skip as before.
- People who may only vote can't go back to the previous song while the room votes.
- Rooms made before vote to skip: a Listener role nobody changed votes now; any role that
  could skip may vote too.

## Queue limits ✅

Settings → This room → *Queue*, **all off by default**:

- **Max songs per person** in the queue at the same time
- **Max song length** (for example no 1-hour mixes)
- **No recent repeats**: block songs that were played in the last X minutes

They don't apply to people who can change the room's settings (they could turn them off
anyway). Songs over a limit are left out and the person is told why, for example "Not
added: longer than 10 min" or "Left out 2 of 12: 2 longer than 10 min". The length and
repeat limits also apply to *Play now*.

## Playlists into autoplay ✅

On a playlist's page, **Add to autoplay** (instead of *Add to queue*) makes the playlist the
autoplay queue: it plays when the queue is empty, so songs people add still go first, and the
radio goes on after its last song. Albums keep *Add to queue*.

## Long playlists ✅

Nobody listens to 2000 songs in one queue. *Play*, *Play next*, *Add to queue* and *Add to
autoplay* on a playlist or album add **at most 200 songs** at once, and a playlist of **more
than 100** asks first: *Add 100* or *Add all 150* (up to the most). Which songs: **the first**
ones (default), or **picked at random** from all of it (in the playlist's order). Only the
part of a long playlist that's needed is loaded, so the first 200 of Liked music go in about
as fast as a short playlist. All of it is set in *Settings → Adding songs* (the most: 10 to
5000; asking: never, or above any number).
