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

In a room from more than one device (your phone and your laptop, two tabs), a song goes into
your history once: the same song in the same room starting at the same moment is one play.

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

> Built: the personal and server graphs from listening (also imported from Google Takeout),
> YouTube Music's radios as edges (the server's, a phone's, and the radio crawl's; each kept as
> it came), merging songs by title, artist and length, mixing it into radio, autoplay, find
> similar and *Related*, and the graph view with its sliders. How it's stored and weighed:
> [storage](../implementation/storage.md#song-graph). Not yet: audio fingerprinting.

Because the history keeps the **order** in which songs were played, YTMP can build a
**graph of songs**:

- **Nodes** are songs. The **same song from different sources** (for example YTM and
  a local file) is **merged into one node**. Matching starts with title, artist and
  duration. Audio fingerprinting (for example AcoustID) can be added later.
- An **edge A → B** means B was played right after A. The more often that happens,
  the stronger the edge.
- Songs played **soon after**, 2 or 3 songs later in the same session, go together too:
  weaker (3 later counts half of 2 later), with their own slider. Also from Takeout.
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

### On the phone, without your server

The graph lives on your YTMP server, with your account. The app keeps a **copy of it** (your
links, everyone's on your server, YouTube Music's), made again once a day when your server can
be reached (about 5 MB for 10,000 songs). When it can't (no internet, the server is down),
radios use the copy: *Song graph* and *On this device* still bring the songs you play together.

- Your music files and saved songs are found in it by their ID, or else by title, artist and
  length, so a file you never played still gets the songs that go with it.
- *Settings → Radio → The song graph on this phone*: switch it off (the copy is deleted), see
  how big it is and when it was made, *Refresh now*.

### Importing from YouTube Music (Google Takeout)

Your YouTube Music history from before YTMP can join yours: on your server's history page,
*Import…* takes a Google Takeout export (pick only "YouTube and YouTube Music" with its
history; the zip, or the watch history in it).

- Its YouTube Music plays go into your history, marked **imported**. Plays already there
  (YTMP adds yours to YouTube Music's history) are left out, so importing twice adds nothing.
- Takeout only says when a song started: how much was heard is the time until the next
  one, and one left within 30 s counts as skipped.
- The graph learns them apart from your plays in YTMP, so a slider can weigh them
  differently. While it's learning them, a bar shows how far it got (on the history page,
  in the graph view, and on the admin page when a new version of the server builds the
  graph again); the graph view loads again by itself when it's done. Importing the same
  export again adds nothing and changes nothing.
- Takeout has no genre, and most of it is one song after another once: a switch from one
  style to another looks the same as a good match. So a link that happened **once** counts
  for little (30% by default, a slider) until it's **confirmed**: a YouTube Music radio also
  puts the two songs together (the seed and a song of its radio, or two songs close together
  in one), or you played one 2 or 3 songs after the other at least twice. A jump from metal to lo-fi rarely is; songs that really go together soon are,
  especially once radios are being learned. A link that happened twice or more counts fully. *Remove* takes an import out of the history (and the graph) again.

### Learning from YouTube Music

Most use of YTMP is: search for a song or artist with the YTM module, play it, or start
a radio from it. YTM's own radios and "related" lists are therefore a large source
of **song connections**:

- Every YouTube Music radio YTMP fetches (50 songs) is **kept as it came**: when, how (a
  room, learning, Find similar) and as whom (without an account, or which account). The graph
  is built from what's kept, so it can be weighed again any time without fetching anything.
- The seed goes with each of the radio's songs, and the songs **close together** in it go
  with each other too (each with the next three; weak, its own slider).
- A radio made **without an account** counts for everyone; one made **with your account**
  only in your own radios (it's your taste, as YouTube sees it), and less by default.
- A phone sends the radios it fetches to its owner's server.
- **Learning radios**: the server (its admin page) and the phone (*Settings → Radio*) can
  slowly fetch the radio of every song they have: music files that are a YouTube Music song
  and saved songs. Without an account, one every 1½ minutes by default, each song again after
  3 months; it stops for hours when YouTube asks whether it's a bot. On the phone it learns
  while the app is open or playing, and with **Also overnight** in the background too while
  the phone charges on Wi-Fi (Android wakes the app for 9 of every 15 minutes or so: about
  20–25 radios an hour, a few hundred a night).

### The graph view

*Song graph* in the song menu (around that song), or on your history page and in *Settings
→ Radio* (your most played songs). With your YTMP account.

- Songs are their covers in circles (bigger: played more): a music file's or a saved song's
  cover from the device itself when it has one, else YouTube Music's. Lines are what YTMP
  learned goes together: as thick as it counts, coloured by what counts most (you, imported,
  everyone, YouTube Music's radios without or with your account, close together in a radio).
  Tap a song to light up its lines and see them listed in a card over the graph, with *Show
  around this song*.
- Scroll or pinch to zoom, drag to move, *All* to see it whole. Names show for the seed, the
  songs its radio would bring first and the one under the pointer, where they fit.
- *Its radio* lists what a radio from the song would bring, best first.
- **Sliders** set how much each part counts: your listening, imported, everyone's, the two
  kinds of radios, a party play, a skip, songs played before it, neighbours of neighbours
  (and further), the extra for songs in the song's group, how fast old plays fade, how far
  down a radio counts. The graph changes at once; *Use for my radios* makes your radios,
  Find similar and Related use them.
- *All your music* opens [the whole graph](#the-whole-graph).

### The whole graph

*All your music* in the graph view: every song the graph knows, at once, laid out so songs
that go together are close.

- **Filters**: *Songs from* (which songs show) and *Placed and grouped by* (which links pull
  songs together and make the groups, and are drawn), each any of *Mine* (your listening, in
  YTMP and imported), *Everyone else* (everyone else on your server, not you) and *YouTube
  Music radios* (the radios it learned, the radio crawl's too): for example your songs, placed
  by the radios. *Lines*: none, the strongest, or all. *You played it at least*: any, 1× (your
  history), 2×, 5×, 10×, 25×. Which songs show changes at once, every song in its place;
  other links lay the map out again (a few seconds the first time), and the songs slide to
  their new places. Songs without any of those links stay where they were, grey. Radios
  always use all links.
- **Colour by**: *Groups* (songs often heard together; they come out much like genres or
  moods, often finer: hardstyle as frenchcore, happy hardcore and uptempo; named after their
  most played artists. Bigger areas, such as all hardstyle, have a colour each, and the groups
  in them shades of it), *Artist* (your most played ones), *Played*
  (how often), *First heard*. A legend says which colour is what. Bigger: you played it more.
- Scroll or pinch to zoom, drag to move; names show as you zoom in. Tap a song for its card
  (artist, plays, group, the songs it goes with most) and *Show around this song*.
- Songs Takeout only gives as a link (removed or private ones, mostly) show as *Song
  without a name*.
- The map counts all your music alike, however long ago you played it (radios still favour
  what you play now).
- The first time, the server takes a few seconds to lay it out; then it's kept a while.

## Radio / Mix

> Implemented: *Start radio* and *Autoplay from here* with **YouTube Music's radio** (and the
> autoplay room setting, see [queue](queue.md#autoplay-queue)), with the **song graph** mixed
> in: one song from the graph after every two of YouTube Music's (*Settings → Radio*). The
> room owner's graph is used. *Radio from* (Mix, YouTube Music, Song graph, On this device),
> songs you know or new ones, with or without your account and whose taste: the defaults in
> *Settings → Radio*, for one radio by holding Start radio, and for the radio playing with
> the chips above *Up next*.

### Radio from

| Choice | The radio is |
|---|---|
| **Mix** (default) | YouTube Music's radio, a song from the song graph after every few |
| **YouTube Music** | only YouTube Music's radio |
| **Song graph** | only what YTMP learned: your listening, everyone's, the radios it kept |
| **On this device** | only songs that play without internet here: music files and saved songs |

- A **music file** that is a YouTube Music song (from its tags or name, or matched on the
  phone) gets that song's radio. Other files get the song graph, then songs like them: the
  same artist, album, genre. Never just any song: a radio isn't a shuffle.
- Without internet, a YouTube Music or Mix radio becomes *Song graph* or *On this device*
  (your choice).
- The chips above *Up next* can be hidden one by one (*Settings → Radio → Chips above Up
  next*); the one playing always shows, and with only one left the row goes.
- *Settings → Radio* also has: songs per artist, leave out songs you skip more than you
  hear and songs played in the last hours, and **never in radios** (artists and songs, from
  holding Dislike).

A radio is an endless list of songs similar to a starting point. There are two ways to use
it from the [song menu](ui.md#song-menu):

- **Start radio**: **replaces the queue** with a radio from this song. The song starts right
  away; the radio is loaded while it plays. With *Settings → Playback → Start radio* on
  *Clear played songs*, the songs that played before go too, like YouTube Music.
- **Autoplay from here**: **replaces the autoplay queue**, the backup that plays when the
  queue is empty, without touching the queue (see [autoplay queue](queue.md#autoplay-queue)).

Using the song graph, YTMP can generate a radio starting from a song, an
artist or the current queue: it walks the graph to find songs that often go together.
Songs reached along many ways count more than one behind a single jump (you once went
from Cavetown to S3RL), and songs in the starting song's **group** (see [the whole
graph](#the-whole-graph)) count extra, so a radio keeps to its vibe.

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

- Uses the same data as Radio / Mix (YTM radio, song graph, metadata). Holding it picks
  where from (Mix, only YouTube Music, only the song graph, only what's on this device).
- Handy in a shared room: find songs that fit the vibe without flooding the queue.
- Respects room permissions: adding songs from the list needs the *add songs* permission.
