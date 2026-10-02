# Listening History, Recap & Radio

All of this is **optional**. Tracking needs a [YTMP account](accounts.md) and is **off
by default**. A server can be configured to turn it **on by default** for new accounts.
Users can always turn it off. Guests have no history. History is stored on the
account's **auth server**.

## Listening history

> Implemented: tracking on/off (pause), "keep me out of others' history" (account setting,
> or a checkbox for guests), recording every song heard with the fields below, and a
> history page with search, delete (one entry, a range of days, everything) and export
> (JSON). Recap, the song graph and radio are next.

When tracking is on, YTMP keeps a **full history of every song played** for the account,
**in order**.

For each play it records:

- the song (source + ID, title, artist, album, duration)
- when it was played, and in which room
- how much of it was heard, and whether it was **skipped**
- who added it (yourself or someone else in the room)
- **who you were listening with**: the other participants in the room at that moment
  (account holders by account, guests by their temporary name)

### Opting out of "listened with"

Anyone can **opt out of being recorded** in other people's histories. This is
a setting on your account, or a toggle when joining as a guest.

- When you opt out, other people's history does **not** store who you are. You never
  show up in their "listened with" recap.
- This is separate from your own tracking: you can track your own history and still
  opt out of appearing in other people's.

### Solo vs. shared listening

History is split into two kinds:

- **Solo listening**: songs played in your own solo rooms.
- **Shared listening**: songs played in rooms with other people.

Shared plays are recorded for **every account holder in the room** who has tracking
on. They are kept apart from solo plays, so a party does not mess up your personal
taste, and you can choose which kind(s) the recap, song graph and radio use.

### Managing your history

The user can:

- view and search their history
- **pause** tracking at any time
- **export** their history
- **delete** single entries, a time range, or everything

## Recap

A personal overview built from the history, in the style of Spotify Wrapped:

- top songs, artists and albums
- total listening time
- per period: week, month, year, or a custom range
- per room, for example "what did we listen to at that party"
- **listening together**: "you listened **x hours** with **X**", top listening buddies,
  and the songs you played most with someone

## Song graph

Because the history keeps the **order** in which songs were played, YTMP can build a
**graph of songs**:

- **Nodes** are songs. The **same song from different sources** (for example YTM and
  a local file) is **merged into one node**. Matching starts with title, artist and
  duration. Audio fingerprinting (for example AcoustID) can be added later.
- An **edge A → B** means B was played right after A. The more often that happens,
  the stronger the edge.
- Skips weaken edges. Songs that were played to the end strengthen them.

### Graph scope

There are two graphs:

- **Personal graph** per account, built from your own history.
- **Server graph** per server, combined from the listening of everyone on that server
  who has tracking on, plus all edges learned from YouTube Music.

Radio and find similar can use either one, or a mix of both. A shared graph learns much
faster in a small group of friends.

### Learning from YouTube Music

Most use of YTMP is: search for a song or artist with the YTM module, play it, or start
a radio from it. YTM's own radios and "related" lists are therefore a large source
of **song connections**:

- When YTMP fetches a YTM radio or a related list, those connections are added to the graph
  as **external edges**, kept apart from edges learned from your own listening.
- This gives the graph useful data from day one, also for local files that can be
  matched to YTM songs.

## Radio / Mix

> Implemented: *Start radio* and *Autoplay from here* with **YouTube Music's radio** (and the
> autoplay room setting, see [queue](queue.md#autoplay-queue)). The song graph comes next and
> will be mixed in.

A radio is an endless list of songs similar to a starting point. There are two ways to use
it from the [song menu](ui.md#song-menu):

- **Start radio**: **replaces the queue** with a radio from this song.
- **Autoplay from here**: **replaces the autoplay queue**, the backup that plays when the
  queue is empty, without touching the queue (see [autoplay queue](queue.md#autoplay-queue)).

Using the song graph, YTMP can generate a radio starting from a song, an
artist or the current queue: it walks the graph to find songs that often go together.

- Works for **any source**, including **local files**, which have no recommendation
  service of their own.
- Falls back to metadata (same artist, album, genre) for songs with little history.
- For YTM songs, YTM's own radio can be used directly or mixed with the song graph.

## Find similar

Like a radio, but **it does not touch the queue**. Starting from a song, an artist or an
album, YTMP shows a **list of similar songs** that you can browse, and you pick the ones
you want to add to the queue (or play next, save to a playlist, …).

- Uses the same data as Radio / Mix (YTM radio, song graph, metadata).
- Handy in a shared room: find songs that fit the vibe without flooding the queue.
- Respects room permissions: adding songs from the list needs the *add songs* permission.
