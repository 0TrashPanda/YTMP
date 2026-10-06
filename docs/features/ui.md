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

- **Home** ✅: the room opens on it. Rows of suggestions from the sources: quick picks
  (songs in a grid of four rows that scrolls sideways; tap = play next), new releases,
  mixes and playlists (cards that scroll sideways and open their page). Without a linked
  YouTube account these are YTM's general suggestions for the host's country; later
  personalised with a linked account.
- **Explore**: new releases, charts, moods & genres.

## Player

Like YTM:

- **Mini player** ✅ (phones): at the bottom of every page. The song, play/pause and
  next, and a thin progress line. **Tap it or swipe it up** to open the full player.
- **Full player** ✅: the album art (with its blurred colours behind it), the song (the
  artist, album and podcast open their page), the position (drag to seek), previous,
  play/pause, next (and back 10 s / ahead 30 s for episodes). At the top: close (˅),
  **Play on** (cast icon) and the song menu (⋮). Below the controls it says where the
  music plays ("Playing on this device and Kitchen", or *Not playing here. Tap to
  listen*). **Swipe down**, the ˅ or the phone's **Back** closes it.
- **Up next / Related** ✅ tabs: at the bottom of the full player on phones (tapping one
  slides it up over the player, and so does **swiping a tab up**; swipe it down, from the tabs or from the list once it is
  scrolled to the top, or tap the handle to close it), next to the album art on desktop. *Up next* is the queue
  (see below); *Related* is [find similar](history-and-recommendations.md#find-similar)
  for the current song. *Lyrics* comes later.
- **Desktop** ✅: the player bar stays at the bottom (controls, the song, volume, Play
  on, Play here, ˄ to open the full player); the full player opens above it. Like YTM,
  **clicking anywhere on the bar** (not on a button) opens or closes the full player, and
  the song shows **Artist • Album • Year**; the artist and album open their page. A **like**
  button sits next to the song. With the full player open, the song is only in the bar (the
  full player shows the art and Up next / Related).
- **Image quality** ✅ (profile menu, per device): **High** (default) loads sharp pictures, on
  big screens bigger than YTM lists them (like YTM, Google's pictures are asked for at the
  size shown, e.g. 1200 px album art in the desktop player); **Low** loads at most 300 px
  pictures, to save data.
- **Desktop layout** ✅, like YTM: a **left side** with the logo, Home, Library and (wide
  screens) your playlists; narrower screens get YTM's mini version (icons with small
  labels). No bottom bar on desktop, and the search box starts where the page does.
- **Later: smoother swipe-down** of the full player. Now it only follows the finger and
  jumps closed (or back) on release; it should feel like YTM's: the player shrinks into
  the mini player as you drag, with momentum (a quick flick closes it), and an animated
  spring back when you let go too early.
- **Play on** ✅ lists **this device** first (on/off, its volume and its sync
  adjustment), then the speakers and TVs the host found.

## Search

The search bar searches all enabled sources, and shows **suggestions while you type** ✅
(YTM's: the typed part plain, the rest bold; they update in place while typing). Like YTM,
typing only suggests: **Enter** or a suggestion searches and shows the results. ↑/↓ pick a
suggestion, Esc closes them. With an empty box it shows your **recent searches** (kept in
the browser, ✕ removes one). On **phones** the header has a search button that opens a
full-screen search page (back arrow, the box, suggestions as rows with ↖ to put one in the
box and keep typing); the phone's back button closes it. It also accepts **links**:

- **YTMP** links
- **YouTube Music** links
- **YouTube** links

Pasting a link shows that song (or playlist/album/artist) directly.

Like YouTube Music, **chips** above the results pick what to search for ✅: **All**,
**Songs**, **Videos**, **Albums**, **Artists**, **Community playlists** and **Featured
playlists** (YouTube Music's own). *All* shows the **top result** as a big card, then a few
of each kind, each with **More** (which picks that chip). The chip stays as picked for
the next search. Songs and videos play like songs (tap = play next); albums, artists and
playlists open their page. **Podcasts** and **Episodes** have chips too (see below);
profiles are left out.

## Podcasts ✅

YTMP plays YouTube Music's podcasts like YTM does:

- **Search**: the *Podcasts* and *Episodes* chips, and both in *All*.
- **Podcast page**: cover, author, description, *Latest episode*, and the episodes
  (newest first, with length and description). Tap = play next, ⋮ = the song menu.
- **Episodes are songs** with a podcast: they go in the queue like songs, and show their
  podcast where a song shows its artists (click it to open the podcast). The song menu
  has *Go to (podcast)* instead of radio and find similar.
- **Exact length**: YTM lists rounded lengths ("3 hr 36 min") and none in search; the
  host takes the exact length from the stream when the episode starts.
- **Resume**: an episode left halfway (skipped, or another song played) goes on from there
  the next time it plays **in that room**. Under 30 s in, or under a minute left, starts over.
- **Back 10 s / ahead 30 s** buttons while an episode plays (player bar and the
  now-playing screen).
- **No autoplay radio after an episode**: the room stops, like a podcast app.
- Not yet: playback speed, subscribing to podcasts / new episodes, marking episodes played,
  resume across rooms or devices (it's per room for now).

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
> the album art in the full player or the player bar, or its ⋮.
>
> **Artist pages** show the artist's top songs, albums and singles, with *Radio* and
> *Add top songs*. **Show all** under the songs opens all their songs (YTM's list, up to 200)
> as a page with *Play*, *Play next* and *Add to queue*. **Album pages** list the songs in order, with *Play*, *Play next* and
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

- In *Up next*, **hold** a song (or right-click it) for its menu; with a mouse a ⋮ also shows
  on hover. **Swipe a song left** to remove it, like YTM (the menu has *Remove* too,
  also for the current song: the next one plays).
- There is no *Move* action: songs are moved by **dragging** them in *Up next*. Played
  songs, upcoming songs and autoplay songs are one list: any of them can be dragged
  anywhere in it. A played song dragged below the current one plays again; an autoplay
  song dragged into the queue becomes yours.
- **Tapping** a song in *Up next* jumps to it; tapping an **autoplay** song makes it play
  next instead.
- The **current song** works like the others: dragging it elsewhere (or removing it) lets
  the next song play.
- The song in the desktop player bar is centered. **Leave Room** is at the bottom of the room
  settings sidebar (people icon), like Discord's *Leave Server*.
- Browsing is cached (in the page and on the host): going back to an artist or album is
  instant, and album pages start loading when you hover them.
- Search results end with a link to search the same words **on YouTube Music**.
- Search, find similar, artist, album and playlist pages stack up (a **Back** button goes to the
  previous one). The **YTMP logo** switches between the home page and the last of those,
  kept as it was.
- The phone's **Back** button (app) closes what's open, goes back through those pages, then to
  Home. On Home it either stops (the app goes to the background, like YTM) or goes on to the
  room list: a setting, separately for solo rooms (default: stop) and party rooms (default:
  room list).
- The **avatar** (top right; also on the room list in the app) opens YouTube Music sign-in,
  **Room settings** and the phone's settings. The people icon is hidden in solo rooms.
- **Pull Home down** to load new suggestions. Rows that scroll sideways have **page dots**.
- Phones: the mini player's progress line is at its **bottom**, between it and the bottom bar.
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
