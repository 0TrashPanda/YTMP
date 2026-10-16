# User Interface

The UI looks and feels **very similar to YouTube Music**, so it is familiar from the start.
Because it is one web frontend, it is the same in the browser and in the Android app.

## Main flow

The most common use of YTMP:

1. **Search** for a song or artist (mostly through the YTM module).
2. **Play** it (tap = play next, swipe left = play now, swipe right = add to queue; all
   [settable](#gestures)), or
3. **Start a radio** from it ([radio / mix](history-and-recommendations.md#radio--mix)), or
   open **find similar** to pick similar songs by hand
   ([find similar](history-and-recommendations.md#find-similar)).

## Artist, album and playlist pages

Besides songs, **artists, albums and playlists** can also be the start of a radio: their
pages have **Start radio**, **Autoplay from here** and **Find similar**.

Built so far: artist pages have **Radio** (from their top songs) and *Add top songs*; album
pages have *Play*, *Play next*, *Add to queue*; playlist pages have *Play*, *Play next* and
*Add to autoplay*. Radio from an album or playlist itself is planned.

## Home & Explore

Like YTM:

- **Home** ✅: the room opens on it. Rows of suggestions from the sources: quick picks
  (songs in a grid of four rows that scrolls sideways; tap = play next), new releases,
  mixes and playlists (cards that scroll sideways and open their page). Without a linked
  YouTube account these are YTM's general suggestions for the host's country; with one,
  your own (see [accounts](accounts.md#linked-youtube-account)).
- **Library** ✅ (with a linked YouTube Music account): your playlists and podcasts, with a
  search field at the top that finds them by name or author as you type. It's forgiving:
  typos ("chlil"), letters in order or initials ("rdtrip", "rt" for *Road trip*), no
  accents needed; best matches first. The same search is above your playlists in the
  desktop left side.
- **Explore** (planned): new releases, charts, moods & genres.

## Player

Like YTM:

- **Mini player** ✅ (phones): at the bottom of every page. The song, play/pause and
  next, and a thin progress line. **Tap it or swipe it up** to open the full player.
- **Full player** ✅: the album art (with its blurred colours behind it), the song (the
  artist, album and podcast open their page), the position (drag to seek), previous,
  play/pause, next (and back 10 s / ahead 30 s for episodes), with **shuffle** and
  **repeat** on either side for music, like YTM. At the top: close (˅),
  **Play on** (cast icon) and the song menu (⋮). Below the controls it says where the
  music plays ("Playing on this device and Kitchen", or *Not playing here. Tap to
  listen*). **Swipe down**, the ˅ or the phone's **Back** closes it.
- **Up next / Related** ✅ tabs: at the bottom of the full player on phones (tapping one
  slides it up over the player, and so does **swiping a tab up**; swipe it down, from the tabs or from the list once it is
  scrolled to the top, or tap the handle to close it), next to the album art on desktop. *Up next* is the queue
  (see below); *Related* is [find similar](history-and-recommendations.md#find-similar)
  for the current song. *Lyrics* ✅ sits between them (see [lyrics](#lyrics)).
- **Next when the room votes to skip** ✅: for people who may only vote, Next votes (red
  while you've voted, pressed again takes it back) and shows the votes so far, like *1/2*.
  See [vote to skip](queue.md#vote-to-skip).
- **Desktop** ✅: the player bar stays at the bottom (controls, the song, volume, repeat,
  shuffle, Play on, Play here, ˄ to open the full player); the full player opens above it. Like YTM,
  **clicking anywhere on the bar** (not on a button) opens or closes the full player, and
  the song shows **Artist • Album • Year**; the artist and album open their page. A **like**
  button sits next to the song. With the full player open, the song is only in the bar (the
  full player shows the art and Up next / Related), the top bar (search) and the left side
  stay, and **clicking the
  art plays or pauses**, like YTM (a play or pause icon pops up on the art for a moment). The art is always square and never cut off at the top or
  bottom, as big as the space allows.
- **Image quality** ✅ (Settings → Look, per device): **High** (default) loads sharp pictures, on
  big screens bigger than YTM lists them (like YTM, Google's pictures are asked for at the
  size shown, e.g. 1200 px album art in the desktop player); **Low** loads at most 300 px
  pictures, to save data.
- **Dislike button in the player bar** ✅ (Settings → Look, per device): Normal, Dimmed
  (darker until you point at it) or Hidden.
- **Swiping up on the player's tabs** ✅ (Settings → Look, per device): opens **that tab**
  (default) or **always Up next**, wherever on the tabs you swipe.
- **Order of the player's tabs** ✅ (Settings → Look, per device): **Up next first** (default,
  like YTM: Up next · Lyrics · Related) or **Related first** (Related · Lyrics · Up next).
- **Up next opens at the current song** ✅, right away (no scrolling down to it); when the
  song changes while it's open, it glides along.
- **Video pictures** ✅ (Settings → Look, per device): songs from a music video only have a
  wide picture. **Crop** (default, like YTM) fills the square; **Fit** shows all of it, with
  bars above and below.
- **The logo** ✅ always goes to Home (closing the full player); on Home already, it loads new
  suggestions, like pulling down.
- **Bottom bar** ✅ on phones: Home, Library (with YouTube Music sign-in) and Local (when
  there's [local music](sources.md#local-music-)).
- **Desktop layout** ✅, like YTM: a **left side** with the logo, Home, Library, Local and (wide
  screens) your playlists, with a search field above them; narrower screens get YTM's mini version (icons with small
  labels). No bottom bar on desktop, and the search box starts where the page does.
- **Space plays and pauses** ✅ the room, like YTM (not while typing). A button you click
  doesn't stay focused, so Space never presses it again; one reached with **Tab** does, and
  Space presses it.
- **Later: smoother swipe-down** of the full player. Now it only follows the finger and
  jumps closed (or back) on release; it should feel like YTM's: the player shrinks into
  the mini player as you drag, with momentum (a quick flick closes it), and an animated
  spring back when you let go too early.
- **Volume sliders** ✅ (player bar, *Play on*, speakers): **scrolling** over one moves it
  2% per wheel notch, finer than YTM's jumps.
- **Play on** ✅ lists **this device** first (on/off, its volume and its sync
  adjustment), then the speakers and TVs the host found.

## Search

The search bar searches all enabled sources, and shows **suggestions while you type** ✅
(YTM's: the typed part plain, the rest bold; they update in place while typing). Like YTM,
typing only suggests: **Enter** or a suggestion searches and shows the results. ↑/↓ pick a
suggestion, Esc closes them. With an empty box it shows your **recent searches** (kept in
the browser, ✕ removes one). On **phones** the header has a search button that opens a
full-screen search page (back arrow, the box, suggestions as rows with ↖ to put one in the
box and keep typing); the phone's back button closes it.

Planned: it also accepts **links** (YTMP, YouTube Music and YouTube links); pasting one
shows that song (or playlist/album/artist) directly.

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

- **Search history** ✅: recent searches are shown when you open the search bar, and can
  be removed one by one.
- **Recently played** (planned): a list of songs, albums, playlists and rooms you played
  recently.

## Gestures

✅ **Tap, swipe right, swipe left and hold** a song do what *Settings → Taps and swipes* says,
separately for songs in lists (search, albums, artists, playlists, Local) and in *Up next*.
Each can be *Play now*, *Play next*, *Add to queue*, *Start radio*, *Song menu* or *Nothing*;
in *Up next* also *Remove*. The defaults:

| Gesture | Songs in lists | Songs in *Up next* |
|---------|----------------|--------------------|
| **Tap** | **Play next** (the start of the queue) | **Play now**: jump to it, like YTM (the songs before it go to the history as skipped; a played song plays again; an autoplay song plays next, so the queue stays) |
| **Swipe right** | **Add to queue** (the end) | **Play next** (moved to the start of the queue) |
| **Swipe left** | **Play now** | **Remove**, like YTM |
| **Hold** | Open the [song menu](#song-menu) | Open the song menu |
| **Drag** (the handle) | Move it in your own playlists | Move it |

A swiped song shows what letting go does underneath, in green once it's far enough (red to
remove); a short swipe does nothing. An action someone may not do (no permission) doesn't
swipe. A **right-click** or **⋮** always opens the song menu, also when *Hold* does something
else (in *Up next* the ⋮ then shows on touch screens too). Swipes are for touch screens. Saved
on the device.

With **fair ordering** on, *play next* puts the song in your next fair slot (the message
says "Added … in your next turn"), unless you have the *Reorder* permission.

## Song menu

**Every song** has the same menu, wherever it appears: search results, the queue
(upcoming), the queue history (played or skipped), playlists, albums, find similar, …
Open it with the **⋮** button or by **right-clicking** the song.

> Implemented so far: Play next, Add to queue, Play now (for the current song: play from
> the start), Start radio, Autoplay from here, Find similar, Go to artist / album / podcast
> (their pages; a search when the source gives no ID), Like and Save to playlist (with a
> linked YouTube Music account), Open in YouTube Music, and Remove (from the queue, the
> history or the autoplay queue). Planned: YTMP playlists, Download, Share, and a
> customisable menu. The current song has the menu too: right-click
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
| Like ✅ | Only if a YouTube account is linked |
| Dislike ✅ | Thumbs down; in a solo room the playing song is skipped (if you may) |
| Remove from this playlist ✅ | On your own playlist's page (Liked music: *Remove from Liked music*) |
| Add to playlist | YTMP playlist, saved to your account (planned) |
| Save to playlist ✅ | YouTube Music playlist; only if a YouTube account is linked |
| Go to artist | |
| Go to album | |
| Download (planned) | Save the song as a plain file on the device. YTMP can play it as a local file. |
| Share (planned) | A YTM link or a YTMP link (you choose) |
| Open in YouTube Music | For YTM songs |

- In *Up next*, **hold** a song (or right-click it) for its menu; with a mouse a ⋮ also shows
  on hover. **Swipe a song left** to remove it, like YTM (the menu has *Remove* too,
  also for the current song: the next one plays). Both [settable](#gestures).
- There is no *Move* action: songs are moved by **dragging** them in *Up next*. The song
  stays under the pointer while the list scrolls (the mouse wheel, or holding it near the
  top or bottom edge, which scrolls the list). Played
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
- The **avatar** (top right, in rooms and on the room list) opens a short menu, like YTM's:
  who you are (your YTMP account and YouTube Music), then in a room its actions (**Share
  room**, **Play on…**, **Clone room**, **Room settings** for those who may change them,
  **Leave room**), and **Settings**. The people icon is hidden in solo rooms.
- **Settings** ✅ is one full screen, the same in rooms, on the room list and at your server's
  `/account`: **Account** (logging in; on your server: listening history, your rooms on other
  devices, your roles, password, log out; on other hosts: who you're logged in as there),
  **YouTube Music** (sign-in, history, search and radio with your account; *Sign out* at the
  bottom), **Playback** (app: headphones, Back button), **Look** (image quality, video
  pictures), **App** (version, updates, logs) and **Server** (the admin: invites, accounts).
  Desktop: the sections on the left; phones: the list, then a section.
- **Pull Home down** to load new suggestions. Rows that scroll sideways have **page dots**.
- Phones: the mini player's progress line is at its **bottom**, between it and the bottom bar.
- Actions that need a room permission (for example adding songs) are **disabled** when you
  don't have that permission.
- Planned: the menu is **customisable**: you can change the **order** of the actions and
  **show or hide** each one. Your layout is saved to your **account**, and can be
  overridden **per device**.

### Share links

> Planned. Built now: sharing a **room** (link and QR code in the room's share sheet).

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

- **Room bar** ✅: room name, room code, participants, sharing (QR code)
- **Queue** ✅ showing who added each song
- **Room management** screens ✅ for admins (permissions, roles, kicking, settings)
- **Room switcher** for multiple solo rooms ✅ (the room list)
- **Output picker** ✅ (host outputs) and a "play on this device" toggle
- **Recap** pages from your listening history (planned)
- **Song menu** settings (order, show/hide) (planned)
- **Party screen** ✅ for a TV, laptop or tablet (see [below](#party-screen))

## Lyrics

> Built.

- The **Lyrics** tab of the full player (between *Up next* and *Related*, like YTM): the
  current song's lyrics, **synced** when they're timed (the line being sung is white and the
  list scrolls along; tap a line to go there, if you may seek), otherwise plain text. It
  says where they're from. Not for podcast episodes.
- Works for **every source**, including local files and media servers later.
- Sources: **[LRCLIB](https://lrclib.net)** (free, synced lyrics, looked up by title,
  artist, album and duration) first, then the source module's own lyrics (YouTube Music's,
  often timed too). Timed lyrics win over plain ones.
- **Karaoke mode** (a room switch: *Karaoke* in the Lyrics tab, or in the room settings, for
  people who may change them): the **party screen** and everyone's **full player** show the
  lyrics large instead of the album art: big lines that glide up as the song goes, the line
  being sung bright in the middle and the others dimmer, fading out at the top and bottom.
  Plain lyrics scroll along with the song. The party screen keeps the song, its progress
  and a smaller code to join, and leaves out what's next. Sizes are set in pixels from the
  space there is (a Chromecast's old Chrome has no container units), and only transform and
  opacity animate, so it stays smooth on a TV.

## Party screen

> Built.

A full-screen view for a **TV, laptop or tablet** during a party:

- the current song with **large album art**, title and artist, who added it, and progress
- the next four songs (queue, then autoplay), and who added them
- a large **QR code** and the **room code** to join, and the address
- a **background colour taken from the album art** (see below), changing with every song
- the skip votes so far, and messages like "Skipped …: enough votes"

It only watches: it can't change anything. It keeps the screen on while it shows, and the
mouse pointer and its buttons (full screen, close) hide when the mouse doesn't move.
Landscape (a TV) has the art left and the song right; portrait (a tablet) stacks them.

Two ways to open it, from the avatar menu in a room:

- **Party screen**: on this device (a laptop next to the speakers, a tablet). *Esc* or ✕
  closes it.
- **Show on a TV**, like YouTube's *Link with TV code*: open **`<server>/tv`** in the TV's
  browser. It shows a 6-digit code; enter it on your phone and the TV becomes the room's
  party screen. Any participant can do this. The TV needs no account (also on servers that
  are for accounts only), remembers the room (switched off and on, it comes back), and shows
  under *Members* as *Party screen*, where it can be removed. Removed, or when the room is
  gone, it shows a new code. A room can have 5 screens.

- **On a TV with Chromecast** (or a display like a Nest Hub): *Play on*, then **Show the
  party screen** under the device. The TV shows the party screen and plays the music (it
  replaces Google's player there), with no browser or code needed. See
  [Chromecast](../implementation/chromecast.md#the-party-screen-on-a-tv-).
- **On your own TV, in a room on a server** (with the app): the server can't reach the TVs
  at your place, but the app can. *Play on* lists the Chromecasts the phone finds (the ones
  the host doesn't offer itself), each with a switch that shows the party screen there,
  which plays the music too. It needs *play on your own device*. Switched off, cast over by
  someone else, or the TV turned off, the screen leaves the room.

## Album art colours

> Partly built: the full player shows the album art blurred behind it; the party screen
> takes a colour from the art. Planned: the colour in the full player too.

Like YTM, the background around the now-playing album art takes its **colour from the
album art** and changes with every song. This is used in the now-playing view and
especially on the **party screen**.

## Themes

> Planned. The UI is dark only for now.

- **Light** and **dark** theme
- Several **built-in themes**
- **Custom colours**: pick your own accent and background colours
- Theme settings are saved to your account (and can be overridden per device, like the
  song menu)

## Notifications

> Planned.

- **"Your song is next"**: a notification when a song you added is about to play.
  **Off by default.**
- In the Android app this is a normal phone notification. In a browser it's a system
  notification where the browser allows it (it needs HTTPS, so on a server), and
  otherwise a message inside the page.

## Language

The UI is **English only** for now. More languages are a [future idea](future-ideas.md#more-languages).
