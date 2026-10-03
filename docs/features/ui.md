# User Interface

The UI looks and feels **very similar to YouTube Music**, so it is familiar from the start.
Because it is one web frontend, it is the same in the browser and in the Android app.

## Main flow

The most common use of YTMP:

1. **Search** for a song or artist (mostly through the YTM module).
2. **Play** it (tap = play next, swipe = play now), add it to the queue, or
3. **Start a radio** from it ([radio / mix](history-and-recommendations.md#radio--mix)), or
   open **find similar** to pick similar songs by hand
   ([find similar](history-and-recommendations.md#find-similar)).

## Artist, album and playlist pages

Besides songs, **artists, albums and playlists** can also be the start of a radio: their
pages have **Start radio**, **Autoplay from here** and **Find similar**.

## Home & Explore

Like YTM:

- **Home**: your mixes and recommendations (personalised with a linked YouTube account).
- **Explore**: new releases, charts, moods & genres.

## Search

The search bar searches all enabled sources, and shows **suggestions while you type**. It also accepts **links**:

- **YTMP** links
- **YouTube Music** links
- **YouTube** links

Pasting a link shows that song (or playlist/album/artist) directly.

Like YouTube Music, **chips** above the results pick what to search for ✅: **All**,
**Songs**, **Videos**, **Albums**, **Artists**, **Community playlists** and **Featured
playlists** (YouTube Music's own). *All* shows the **top result** as a big card, then a few
of each kind, each with **More** (which picks that chip). The chip stays as picked while
you type something else. Songs and videos play like songs (tap = play next); albums,
artists and playlists open their page. Podcasts, episodes and profiles are left out.

## Search history & recently played

- **Search history**: recent searches are shown when you open the search bar, and can
  be cleared.
- **Recently played**: a list of songs, albums, playlists and rooms you played recently.

## Gestures

| Gesture | Action | Needs |
|---------|--------|-------|
| **Tap** a song (search, pages, playlists, …) | **Play next** (add to the start of the queue) | *Add songs* |
| **Tap** a song **in the queue** | **Jump to it**, like YTM: it plays now, and the songs before it go to the queue history as skipped | *Play now* |
| **Tap** a song **in the queue history** | **Jump back to it**, like YTM: it plays again, and the queue continues after it | *Play now* |
| **Swipe** a song | **Play now** | *Play now* |
| **Drag** a song in the queue | Move it | *Reorder* |
| Long-press / ⋮ | Open the [song menu](#song-menu) | |

With **fair ordering** on, *play next* puts the song in your next fair slot, unless you
have the *Reorder* permission.

## Song menu

**Every song** has the same menu, wherever it appears: search results, the queue
(upcoming), the queue history (played or skipped), playlists, albums, find similar, …
Open it with the **⋮** button or by **right-clicking** the song.

> Implemented so far: Play next, Add to queue, Play now (for the current song: play from
> the start), Start radio, Autoplay from here, Find similar, Go to artist / album (their
> pages; a search when the source gives no ID), Open in YouTube Music, and Remove (from the
> queue, the history or the autoplay queue). The current song has the menu too: right-click
> the album art or the player bar, or its ⋮.
>
> **Artist pages** show the artist's top songs (20), albums and singles, with *Radio* and
> *Add top songs*. **Album pages** list the songs in order, with *Play*, *Play next* and
> *Add to queue*. Artist and album names under the current song open them.

| Action | Notes |
|--------|-------|
| Play next | Plays next |
| Add to queue | |
| Remove | From the queue, the queue history or the autoplay queue. Needs *Remove own/others' songs*. |
| Start radio | Replace the queue with a [radio](history-and-recommendations.md#radio--mix) from this song |
| Autoplay from here | Replace the [autoplay queue](queue.md#autoplay-queue) with a radio from this song, without touching the queue |
| Find similar | Open [find similar](history-and-recommendations.md#find-similar) from this song |
| Like | Only if a YouTube account is linked |
| Add to playlist | YTMP playlist, saved to your account |
| Add to YTM playlist | Only if a YouTube account is linked |
| Go to artist | |
| Go to album | |
| Download | Save the song as a plain file on the device. YTMP can play it as a local file. |
| Share | A YTM link or a YTMP link (you choose) |
| Open in YouTube Music | For YTM songs |

- There is no *Move* action: songs are moved by **dragging** them in *Up next*. Played
  songs, upcoming songs and autoplay songs are one list: any of them can be dragged
  anywhere in it. A played song dragged below the current one plays again; an autoplay
  song dragged into the queue becomes yours.
- **Tapping** a song in *Up next* jumps to it; tapping an **autoplay** song makes it play
  next instead.
- The **current song** works like the others: dragging it elsewhere (or removing it) lets
  the next song play.
- The song in the player bar is centered. **Leave Room** is at the bottom of the room
  settings sidebar (people icon), like Discord's *Leave Server*.
- Browsing is cached (in the page and on the host): going back to an artist or album is
  instant, and album pages start loading when you hover them.
- Search results end with a link to search the same words **on YouTube Music**.
- Search, find similar, artist, album and playlist pages stack up (a **Back** button goes to the
  previous one). The **YTMP logo** switches between the current song's album art and the
  last of those, kept as it was.
- Actions that need a room permission (for example adding songs) are **disabled** when you
  don't have that permission.
- The menu is **customisable**: you can change the **order** of the actions and
  **show or hide** each one. Your layout is saved to your **account**, and can be
  overridden **per device**.

### Share links

- **YTM link**: opens the song on YouTube Music.
- **YTMP link**: points to a **page on a YTMP server** for the song. When you are in a
  locally hosted room (phone), the link uses a **fallback server address** from your
  settings.
  - Opened in a **browser**: the song opens in a **new private (solo) room**.
  - Opened on a **phone with the app**: the app opens and shows the song as a
    **search** in your **current room**.

### Download rules

- Songs from online sources (YTM, YT, …) can be downloaded by **anyone**.
- Songs from someone's **local files** or **media server** can only be downloaded if
  the owner has **enabled downloads** for that source.

## YTMP-specific additions on top of a YTM-like UI

- **Room bar**: room name, room code, participants, your role, sharing (QR code)
- **Queue** showing who added each song
- **Room management** screens for admins (permissions, roles, kicking, settings)
- **Room switcher** for multiple solo rooms (like YTM tabs)
- **Output picker** (host outputs) and a "play on this device" toggle
- **Recap** pages from your listening history
- **Song menu** settings (order, show/hide)
- **Party screen** for a TV or laptop

## Lyrics

- Lyrics for the current song, **synced** (the current line is highlighted and scrolls
  along) when available, otherwise plain text.
- Works for **every source**, including local files and media servers.
- Sources: **[LRCLIB](https://lrclib.net)** (free, synced lyrics, looked up by title,
  artist, album and duration) first, then the source module's own lyrics (for example
  YTM) as a fallback.
- Optionally shown on the party screen.

## Party screen

A full-screen view for a **TV, laptop or tablet** during a party:

- the current song with **large album art**, title and artist, and progress
- the next few songs in the queue, and who added them
- a large **QR code** and the **room code** to join
- a **background colour taken from the album art** (see below)

Any participant can open it, for example in a browser on a TV.

## Album art colours

Like YTM, the background around the now-playing album art takes its **colour from the
album art** and changes with every song. This is used in the now-playing view and
especially on the **party screen**.

## Themes

- **Light** and **dark** theme
- Several **built-in themes**
- **Custom colours**: pick your own accent and background colours
- Theme settings are saved to your account (and can be overridden per device, like the
  song menu)

## Notifications

- **"Your song is next"**: a notification when a song you added is about to play.
  **Off by default.**
- In the Android app this is a normal phone notification. In a browser it's a system
  notification where the browser allows it (it needs HTTPS, so on a server), and
  otherwise a message inside the page.

## Language

The UI is **English only** for now. More languages are a [future idea](future-ideas.md#more-languages).
