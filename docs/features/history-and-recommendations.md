# Listening History, Recap & Radio

All of this is **optional**. Tracking needs a [YTMP account](accounts.md) and is **off
by default**. A server can be configured to turn it **on by default** for new accounts.
Users can always turn it off. Guests have no history. History is stored on the
account's **auth server**.

## Listening history

> Implemented: tracking on/off (pause), "keep me out of others' history" (account setting,
> or a checkbox for guests), recording every song heard with the fields below, **History in
> the room's Library** (below), and a page on your server's account page with search, delete
> (one entry, a range of days, everything) and export (JSON). The song graph is built and mixed
> into radio and find similar (below). Recap is not built yet.

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
- **play** from it: its songs work like songs anywhere else (see below)
- **pause** tracking at any time
- **export** their history
- **delete** single entries, a time range, or everything

### History in the room

Like YouTube Music's, your history is in the **Library**: the *History* button at the top
opens it, newest first and grouped by day (*Today*, *Yesterday*, then the date). Under each
song: when, in which room, who you listened with, who added it and whether you skipped it.

- It's there wherever you're logged in with your YTMP account: on your server's pages, in the
  app (also in rooms on the phone itself), and in rooms on other hosts when you let them use
  your YouTube Music and history (the question when you log in there). The Library shows
  then, also without a YouTube Music sign-in.
- Its songs work like any other list: **tap, swipe and hold** do what *Settings → Taps and
  swipes* says (play next, add to queue, play now, start radio, the song menu), and the song
  menu has everything it has elsewhere, plus **Remove from your history**.
- **Search** your history at the top. Older days load as you scroll.
- On your server's own pages, *Export or delete days* goes to the full history page.

## Recap

> Not built yet.

A personal overview built from the history, in the style of Spotify Wrapped:

- top songs, artists and albums
- total listening time
- per period: week, month, year, or a custom range
- per room, for example "what did we listen to at that party"
- **listening together**: "you listened **x hours** with **X**", top listening buddies,
  and the songs you played most with someone

## Song graph

> Built: the personal and server graphs from listening, YouTube Music's radios as edges, merging
> songs by title, artist and length, and mixing it into radio, autoplay, find similar and
> *Related*. How it's stored and weighed: [storage](../implementation/storage.md#song-graph).
> Not yet: audio fingerprinting, and YouTube Music's radios fetched by a phone (only a server's
> are learned).

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
  who has tracking on, plus all edges learned from YouTube Music. A song a whole party heard
  counts once.

Radio and find similar use a mix of both; your own listening counts most. A shared graph
learns much faster in a small group of friends.

- What of your own listening counts is an account setting (*Settings → Account → Radio learns
  from*): **solo listening and parties** (the default; parties count a bit less), **only solo
  listening**, or **not my listening** (then only everyone's and YouTube Music's).
- Your own listening only goes into radios made for you: in your rooms, on your server and
  on your phone, and in *Find similar* when you ask. Another host you join (a friend's phone)
  only gets what everyone on your server listens to.
- Deleting from your history takes it out of the graph too (it's rebuilt from what's left).

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
> autoplay room setting, see [queue](queue.md#autoplay-queue)), with the **song graph** mixed
> in: one song from the graph after every two of YouTube Music's. Where YouTube Music has no
> radio (local music) or doesn't answer, the graph's songs are the radio. The room owner's
> graph is used.

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

> Built with YouTube Music's radio and the song graph (yours, as whoever asks): *Find similar*
> in the song menu, and the *Related* tab of the full player.

Like a radio, but **it does not touch the queue**. Starting from a song, an artist or an
album, YTMP shows a **list of similar songs** that you can browse, and you pick the ones
you want to add to the queue (or play next, save to a playlist, …).

- Uses the same data as Radio / Mix (YTM radio, song graph, metadata).
- Handy in a shared room: find songs that fit the vibe without flooding the queue.
- Respects room permissions: adding songs from the list needs the *add songs* permission.
