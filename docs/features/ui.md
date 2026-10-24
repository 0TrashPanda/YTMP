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
- **Your own Home** ✅ (*Settings → Home*): **pin** a playlist (Liked
  music too), album, artist or podcast with *Pin to Home* on its page. A pin is a **tile** in
  the **Pinned** section (a **speed dial** like YTM's: covers in a 3×3 grid, the next nine a
  swipe to the side), or a **section of its own with its songs** (the first 20, a
  podcast's newest episodes; *More* opens its page). Pins show at once, before the
  suggestions load. Every section, Pinned and pins included, can be **hidden**, **moved**
  and shown as a **carousel** (like YTM; Pinned: tiles), a **list** (songs one under the
  other that tap and swipe like any song list, albums and playlists as rows) or a **speed
  dial** (YTM's own *Speed dial* section is one by default). *Podcast
  sections* hides YTM's sections of podcasts and episodes. A section's **⋮** on Home does the
  same in place. YTM picks and names its sections, so they're known by name; a new one shows
  where YTM puts it.
- **Library** ✅ (with a linked YouTube Music account): your playlists and podcasts, with a
  search field at the top that finds them by name or author as you type. It's forgiving:
  typos ("chlil"), letters in order or initials ("rdtrip", "rt" for *Road trip*), no
  accents needed; best matches first. The same search is above your playlists in the
  desktop left side.
- **Explore** (planned): new releases, charts, moods & genres.

## Player

Like YTM:

- **Everything responds right away** ✅: play, pause, next, previous, seeking, playing a
  song, starting a radio and changing the queue show at once, without waiting for the room.
  When the room says no after all (not allowed, a song that's too long), it goes back and a
  message says why. A new song shows as loading while it's looked up, not as paused.

- **Mini player** ✅ (phones): at the bottom of every page. The song, play/pause and
  next, and a thin progress line. **Tap it or swipe it up** to open the full player.
- **Full player** ✅: the album art (with its blurred colours behind it), the song (the
  artist, album and podcast open their page), the position (click or drag to seek), previous,
  play/pause, next (and back 10 s / ahead 30 s for episodes), with **shuffle** and
  **repeat** on either side for music, like YTM. At the top: close (˅),
  **Play on** (cast icon) and the song menu (⋮). Below the controls it says where the
  music plays ("Playing on this device and Kitchen", or *Not playing here. Tap to
  listen*). **Swipe down**, the ˅ or the phone's **Back** closes it.
- **Where the song comes from** ✅: while it plays on this device, a small label next to
  *Added by* (desktop: next to the time in the player bar): **YouTube** (straight from
  YouTube), **Server** or **Host phone** (the host sends it: saved there, its music files, or
  YouTube's link through it when the direct one doesn't work here), or **This phone** (saved
  on the phone, or its own music when it hosts the room). It shows once the song has loaded
  here (a new song's YouTube link can take a few seconds). In the app it can change during a
  song: *YouTube* becomes *This phone* when the whole song is saved on the phone, which it then
  plays from.
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
  **clicking anywhere on the bar** (not on a button) opens or closes the full player,
  the position line along its top seeks where you click it (or drag it; it takes clicks a few
  pixels above and below the line too), and the song shows **Artist • Album • Year**; the
  artist and album open their page. A **like** button sits next to the song.
  With the full player open, the song is only in the bar (the
  full player shows the art and Up next / Related), the top bar (search) and the left side
  stay, and **clicking the
  art plays or pauses**, like YTM (a play or pause icon pops up on the art for a moment). The art is always square and never cut off at the top or
  bottom, as big as the space allows.
- **Image quality** ✅ (Settings → Look): **High** (default) loads sharp pictures, on
  big screens bigger than YTM lists them (like YTM, Google's pictures are asked for at the
  size shown, e.g. 1200 px album art in the desktop player); **Low** loads at most 300 px
  pictures, to save data.
- **Dislike button in the player bar** ✅ (Settings → Player): Normal, Dimmed
  (darker until you point at it) or Hidden.
- **Swiping up on the player's tabs** ✅ (Settings → Player): opens **that tab**
  (default) or **always Up next**, wherever on the tabs you swipe.
- **Order of the player's tabs** ✅ (Settings → Player): **Up next first** (default,
  like YTM: Up next · Lyrics · Related) or **Related first** (Related · Lyrics · Up next).
- **Up next opens at the current song** ✅, right away (no scrolling down to it); when the
  song changes while it's open, it glides along.
- **Video pictures** ✅ (Settings → Look): songs from a music video only have a
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

On a **Pixel** (in the app) the search box has a small music-note icon at its right end for
**songs Now Playing recognized** ✅: tapping it lists the last 20 songs the phone heard
(newest first), and tapping one searches for it. Until YTMP may read Now Playing's
notifications the icon opens Android's notification access settings instead; coming back
from there, it works. Nothing is listened to or sent anywhere by YTMP: the phone's own Now
Playing does the recognizing, and YTMP only reads the notification it posts (and no other
app's). Other phones don't show the icon.

It also accepts **links** ✅: pasting a YouTube Music or YouTube link opens what it points
at right away, without Enter: an album, playlist, artist or podcast opens its page; a song
(or video, or episode) shows as the result (*From the link*, tap = play next), with *Open
the playlist it's in* when the link plays it in one (not YouTube's endless mixes). A YTMP
room link opens that room. A link that's typed rather than pasted shows one row instead of
suggestions (*Open this album*, …) that Enter or a tap opens. Links don't go into the recent
searches.

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
- **Resume**: an episode left halfway (paused, skipped, or another song played) goes on from
  there the next time it plays. With a YTMP account that's **in any room, on any device**:
  your server keeps how far you got (for whoever added the episode); without one, in that
  room. Under 30 s in, or under a minute left, starts over.
- **Played marks** (with a YTMP account): a podcast page shows how far you got in each
  episode (a bar, and "40 min left") and which you heard to the end ("Played"). The song
  menu has *Mark as played* / *Mark as unplayed*. Kept on your server, also with your history
  paused.
- **Back 10 s / ahead 30 s** buttons while an episode plays (player bar and the
  now-playing screen).
- **No autoplay radio after an episode**: the room stops, like a podcast app.
- **Subscribe** (with a YouTube Music sign-in): the button on a podcast page adds it to your
  YouTube Music library (*Library → Podcasts*), or takes it out.
- **New episodes** on Home (with a YouTube Music sign-in): the first row is the newest episodes
  of the podcasts you're subscribed to, from YouTube Music's own New episodes list.
- **Playback speed**: the speed button next to the controls while an episode plays (0.5× to
  2×: the usual speeds, or one typed in steps of 0.05), for **everyone in the room**; music always plays at 1×.
  Each **podcast keeps its own speed**: its next episodes start at the speed it was last
  played at, and a podcast not played before starts at the last speed used.
  Chromecasts play along at that speed; Sonos speakers, MPDs and amplifier zones can't, so
  they pause during an episode played faster or slower (the room says so).

## Search history & recently played

- **Search history** ✅: recent searches are shown when you open the search bar, and can
  be removed one by one.
- **Listening history** ✅: *Library → History* (with a YTMP account), see
  [history in the room](history-and-recommendations.md#history-in-the-room).
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
| **Swipe left** | **Play now** | **Remove**, like YTM (the current song too: the next one plays) |
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
> history or the autoplay queue; in *Library → History*: from your listening history). Download ✅ (YouTube Music songs and episodes). Planned: YTMP playlists and Share. The
> menu is customisable ✅ (below). The current song has the menu too: right-click
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
| Sleep timer ✅ | Only for the song that's playing: see [sleep timer](playback-and-outputs.md#sleep-timer) |
| Download ✅ | Save the song as a plain music file (.m4a with its title, artist, album and cover). In a browser: a normal download. In the app: in the phone's *Music/YTMP* folder, so it shows in Local → *Music on this phone* and in other music apps. Not for local music. |
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
- The **current song** keeps playing when it's dragged elsewhere, like YTM's one list: the
  songs it's dragged past change sides. Down past upcoming songs, they count as played
  (skipped); up past played songs, they play again after it. Removing it lets the next song
  play.
- The song in the desktop player bar is centered. **Leave Room** is at the bottom of the room
  settings sidebar (people icon), like Discord's *Leave Server*.
- Browsing is cached (in the page and on the host): going back to an artist or album is
  instant, and album pages start loading when you hover them.
- Search results end with a link to search the same words **on YouTube Music**.
- Pages stack up in the order you open them: search, find similar, artist, album, playlist,
  podcast, genre and folder pages. Going back shows the page before, **scrolled as you left
  it**. Pages opened from other pages have a **Back** button.
- The bottom bar starts over: **Home** is the root (the pages opened before are gone), and
  **Library** and **Local** sit right on Home, so Back from them goes Home. The **YTMP logo**
  goes Home too (on Home: new suggestions).
- The phone's **Back** button (app) closes what's open, then steps back through those pages
  in order, down to Home. On Home it either stops (the app goes to the background, like YTM) or goes on to the
  room list: a setting, separately for solo rooms (default: stop) and party rooms (default:
  room list).
- The **avatar** (top right, in rooms and on the room list) opens a short menu, like YTM's:
  who you are (your YTMP account and YouTube Music), then in a room its actions (**Share
  room**, **Play on…**, **Clone room**, **Room settings** (Settings at *This room*) for those who may change them,
  **Leave room**), and **Settings**. The people icon is hidden in solo rooms.
- **Settings** ✅ is one full screen, the same in rooms, on the room list and at your server's
  `/account`, in YouTube Music's style: a list of pages under a few headings (desktop: the
  list on the left; phones: the list, then a page). No deeper menus: a page groups its
  settings under small headings.
  - **This room** (in a room): **Overview** (name, code, roles when joining, autoplay,
    karaoke, starting a song, password, always on, opening it on your other device),
    **Queue** (fair order, vote to skip, limits) and **People** (members, roles and bans),
    then *Clone room* and *Leave room*. A room's changes wait for **Save**.
  - **You**: **Account** (logging in; on your server: listening history, your rooms on other
    devices, your roles, password, log out; on other hosts: who you're logged in as there) and
    **YouTube Music** (sign-in, history, search and radio with your account; *Sign out* at the
    bottom).
  - **Playing**: **Playback** (next while paused, start radio, asking before interrupting a
    party, pause only stops it here, your other devices, skip silence in podcasts) and **Sound** (equalizer, headphones).
  - **How it looks**: **Look** (image quality, video pictures, songs per page), **Player**
    (the tabs, the dislike button), **Home**, **Song menu**, **Taps and swipes** and **Solo
    rooms** (what a solo room leaves out, below).
  - **Music and storage**: **Local music**, **Song cache and downloads** (the app) and
    **Your own module** (browsers).
  - **App** (version, updates, logs, the Back button) and **Server admin** (invites, accounts).
- **Your settings on all your devices** ✅: logged in with your YTMP account, almost every
  setting is kept on your account, so all your devices have it, and a change on one shows up
  on the others (when you come back to the app). A phone icon next to a setting keeps it
  **only on this device**: it turns blue (*This phone*), and under the setting is what your
  account has, with *Use that* to follow the account again. Pages that are one setting as a
  whole (Home, Song menu, Solo rooms) have the icon next to their title. What belongs to one
  device stays on it and has no icon: the equalizer, headphones, the Bluetooth sync
  adjustment, the song cache and downloads, your own module and the Back button. Without an
  account, settings are kept on the device. Settings from before were moved once: onto your
  account when it had none yet, else kept on that device when they were different.
- **Solo rooms** ✅ (Settings → Solo rooms): switch off the party and sharing bits
  you don't need when you listen alone: the room code and share button at the top, the room
  name in the player, the Play on buttons (Play on stays in the avatar menu), "Playing on"
  under the controls, Karaoke, and Party screen, Show on a TV and Clone room in the avatar
  menu. Everything shows by default, and parties always show everything.
- **Numbers you can type** ✅: every setting that is a number has a box to type it in, next to
  its slider or its choices: the volumes and the sync adjustment in *Play on*, the room's
  waiting and limits and the votes needed to skip (*This room*), songs per page, the song
  cache's size and the playback speed. Enter (or leaving the box) takes it, kept within what
  the setting allows.
- **Pull Home down** to load new suggestions (only a pull down: swiping a row sideways never
  refreshes). Rows that scroll sideways have **page dots**.
- **Long lists** come in pages: Liked music and your own playlists load more as you scroll
  down; songs in Local have **Load more**. How many songs a page has (100, 300 or 1000, or a
  number from 10 to 5000 typed in) is a setting in *Settings → Look*.
- Phones: the mini player's progress line is at its **bottom**, between it and the bottom bar.
- Actions that need a room permission (for example adding songs) are **disabled** when you
  don't have that permission.
### On a phone ✅

On a phone (a narrow screen) the menu slides up from the bottom, like YouTube Music's: a
handle, the song's picture, title and artist, a row of **three big buttons** (by default
*Play next*, *Add to queue* and *Save to playlist*), then the other actions. Swipe it down,
tap outside it or press Back to close it. Wider screens show the menu where you opened it.

### Customisable ✅

*Settings → Song menu*: move each action **up or down**, **show or hide** it, and add,
move or remove **dividers**; pick the phone's **big buttons** (or none in a place). A big
button isn't repeated in the list under it. An action still only shows where it can be used
(*Like* needs YouTube Music, *Remove from this playlist* your own playlist, and so on).
*Defaults* puts it all back. Saved to your **YTMP account** (logged in), so it's the same on
all your devices, wherever your history can be shown too; otherwise on this device. The first
time, the layout of the device you set it up on goes to your account.

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
- **Song menu** settings (order, show/hide, dividers, big buttons) ✅
- **Party screen** ✅ for a TV, laptop or tablet (see [below](#party-screen))

## Keyboard shortcuts ✅

In a browser (not in the app): **Space** or **K** play/pause, **N** next, **P** previous,
**←** / **→** 10 seconds back or ahead, **↑** / **↓** volume, **/** jumps to the search box.
They do what the buttons do (so they need the same permissions), and aren't active while
typing, in a dialog, or with Ctrl, Alt or Cmd held. Listed under *Settings → Playback*.

## Lyrics

> Built.

- The **Lyrics** tab of the full player (between *Up next* and *Related*, like YTM): the
  current song's lyrics, **synced** when they're timed (they open at the line being sung,
  which is white, and the list scrolls along; tap a line to go there, if you may seek),
  otherwise plain text. It
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
