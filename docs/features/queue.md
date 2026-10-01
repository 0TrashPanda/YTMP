# Queue

The host keeps **one queue per room**: the list of songs that will be played.

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
| Vote to skip | *Vote to skip* permission (when enabled) |

Permissions are set by room admins. See [room management](room-management.md#permissions).

Songs can come from any enabled [source](sources.md), so one queue can mix, for
example, YouTube Music tracks and local files.

## Autoplay queue

Next to the queue, a room has a second **autoplay queue**, a backup for when the
queue runs out.

- When the **last song of the queue** finishes, the next song is taken from the
  **front of the autoplay queue**.
- As soon as someone adds songs to the queue again, those play first.
- The autoplay queue is filled with **Autoplay from here** from the [song menu](ui.md#song-menu). This
  **replaces** the current autoplay queue with songs similar to that song (see [radio](history-and-recommendations.md#radio--mix)).
  It keeps generating, so it never runs empty.
- The autoplay queue is visible below the queue.

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

### Fair / round-robin
The admin can enable an ordering mode that **spreads songs out per person**, so
one participant cannot take over the queue.

Example: P1 added 5 songs, P2 added 3, P3 added 2:

```
P1 P2 P3 P1 P2 P3 P1 P2 P1 P1
```

Rules:

- The queue goes **round by round** over every participant who has songs queued, and plays
  **one song per person** per round. People without songs are skipped.
- A newly added song goes into the **next round where that person has no song yet**.
  So even if the queue has 50 songs, someone who has no songs queued yet lands near the top.
- When someone **leaves the room, their songs stay** in the queue and keep their place in
  the rounds.
- People with the *Reorder* permission can still **drag songs** to any position. A moved
  song stays where it was put.
- Radio songs (from **Start radio** and **Autoplay from here**) belong to the **person who started
  the radio**. So when others add songs, those are spread in between the radio songs
  instead of waiting behind all of them.
- Songs from the **autoplay queue** only play when the queue is empty, so fair ordering
  does not apply to them. They still show who started the radio.

## Shuffle

**Shuffle** is a one-time action that randomises the order of the **upcoming** songs
(like YTM). It does not change the queue history.

- With **fair ordering** on, shuffle keeps the rounds: it randomises each person's
  songs and the order within each round, so nobody gets more turns.

## Repeat

| Mode | Behaviour |
|------|-----------|
| Off (default) | When the queue ends, the [autoplay queue](#autoplay-queue) takes over |
| Repeat queue | When the queue ends, the songs from the queue history are queued again |
| Repeat one | The current song repeats |

## Vote to skip

A room setting, **off by default**. When on, participants can vote to skip the current
song. It is skipped once enough people have voted (configurable, for example 50% of
the participants in the room).

## Queue limits

Room settings, **all off by default**:

- **Max songs per person** in the queue at the same time
- **Max song length** (for example no 1-hour mixes)
- **No recent repeats**: block songs that were played in the last X minutes
