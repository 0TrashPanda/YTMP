# User Interface

The UI looks and feels **very similar to YouTube Music**, so it is familiar from the start.
Because it is one web frontend, it is the same in the browser and in the Android app.

## On their own pages

- [Player](player.md): the mini player, the full player, Up next (moving and removing songs), lyrics
- [Search](search.md): search, chips, search history
- [Song and card menus](menus.md): the ⋮ menus, holding for choices, share links, downloads
- [Party screen](party-screen.md): the big screen for a TV, laptop or tablet

## Main flow

The most common use of YTMP:

1. **Search** for a song or artist (mostly through the YTM module).
2. **Play** it (tap = add to queue, swipe left = play now, swipe right = play next; all
   [settable](#gestures)), or
3. **Start a radio** from it ([radio / mix](../music/history-and-recommendations.md#radio--mix)), or
   open **find similar** to pick similar songs by hand
   ([find similar](../music/history-and-recommendations.md#find-similar)).

## Artist, album and playlist pages

Besides songs, **artists, albums and playlists** can also be the start of a radio: their
pages have **Start radio**, **Autoplay from here** and **Find similar**.

Built so far: artist pages have **Radio** (from their top songs) and *Add top songs*; album
pages have *Play*, *Play next*, *Add to queue*; playlist pages have *Play*, *Play next* and
*Add to autoplay*. Radio from an album or playlist itself is planned.

## Feeling fast ✅

- **Something that takes a moment says so**: adding a long playlist, saving to a playlist,
  subscribing, finding songs for *Autoplay this*. Nothing shows when it's done quickly; when it
  isn't done after **0.15 s** a message with a small spinner says what's going on ("Adding
  Liked music…"), and when it's done the result takes its place ("Added 200 songs from Liked
  music to the queue"). The wait is a setting (*Settings → Adding songs*: right away, or up to
  5 s).
- **Placeholders**: pages show grey placeholders the shape of what's coming (song rows, cards)
  instead of "Loading…", so they don't jump. Search keeps the old results dimmed while the new
  ones load.
- **The last Home and Library show right away** when the app starts, while the new ones load.
- **Ahead of time**: the next song's stream is looked up while the current one plays (also
  when you add a song to play next, or autoplay finds one; also by your own module, in the
  apps and the desktop app), and the lyrics while the full player is open. Pages you
  touch or hover start loading before they open.

## Home & Explore

Like YTM:

- **Home** ✅: the room opens on it. Rows of suggestions from the sources: quick picks
  (songs in a grid of four rows that scrolls sideways; tap = add to queue), new releases,
  mixes and playlists (cards that scroll sideways and open their page). Without a linked
  YouTube account these are YTM's general suggestions for the host's country; with one,
  your own (see [accounts](../accounts/accounts.md#linked-youtube-account)).
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
- **Home fits what each section has** ✅ ([how](../../implementation/apps/home-layout.md)). On a
  **phone**, a speed dial of suggestions shows only full 3×3 pages: 18 tiles (two pages), or 9
  when there are fewer than 18 (*Settings → Home → Speed dials on a phone*: *Full pages* or
  *Everything*); your own pins in *Pinned* all show. On a **computer**, a speed dial shows all
  its tiles at once in even rows that fill the width (20 at 1440 px: three rows of 7). A gap
  in the last row or page is filled with **Liked music** (signed in) and **Random song** (a
  song from Home that isn't in the dial, played the way a tap on a song does),
  **quick picks** sit across the whole width without scrolling (20 songs: 4 columns of 5, or 5
  of 4 on a wider screen), and a row of **playlists or albums** shows whole cards, never one
  cut off at the edge; a row with few gets bigger cards that fill it. The ‹ › buttons only
  show when a row scrolls.
- **Library** ✅ (with a linked YouTube Music account): your playlists and podcasts. The
  search bar at the top searches only your library here ([search](search.md); *Settings →
  Search*). With that setting off, a search field on the page finds them by name or author as
  you type. It's forgiving: typos ("chlil"), letters in order or initials ("rdtrip", "rt"
  for *Road trip*), no accents needed; best matches first. The same search is above your
  playlists in the desktop left side.
- **Explore** (planned): new releases, charts, moods & genres.

## Navigation

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
- **Home and Library** show what they had right away (also after the app was closed) and only
  load new ones from YouTube Music once they're **6 hours** old (*Settings → Home*: 30 min to a
  week, or only by hand). **Pull Home or Library down** (or tap the logo on Home) to load new
  ones now (only a pull down: swiping a row sideways never refreshes). A **thin bar** runs along
  the top of Home while new suggestions load: when you asked for them always (at least a moment,
  so you see it happened), by itself only when it takes a while. Your own changes (a new
  playlist, renaming) show right away. Rows that scroll sideways have **page dots**.
- **Back where you were**: opening the app (Android or desktop) goes straight back into the
  solo room you were in when you closed it (*Settings → App*, on by default); the same for
  party rooms is a separate setting (off by default). Going to the room list yourself means it starts there next time.
- **Long lists** come in pages: playlists (Liked music and your own too) load more as you
  scroll down; songs in Local have **Load more**. How many songs a page has (100, 300 or 1000, or a
  number from 10 to 5000 typed in) is a setting in *Settings → Look*.
- Phones: the mini player's progress line is at its **bottom**, between it and the bottom bar.
- Actions that need a room permission (for example adding songs) are **disabled** when you
  don't have that permission.

## Settings

- **Settings** ✅ is one full screen, the same in rooms, on the room list and at your server's
  `/account`, in YouTube Music's style: a list of pages under a few headings (desktop: the
  list on the left; phones: the list, then a page). No deeper menus: a page groups its
  settings under small headings.
  - **This room** (in a room): **Overview** (name, code, roles when joining, autoplay,
    karaoke, starting a song, password, always on, opening it on your other device),
    **Queue** (fair order, vote to skip, limits) and **People** (members, roles and bans),
    then *Clone room* and *Leave room*. A room's changes wait for **Save**.
  - **You**: **Account** (logging in; on your server: listening history, your rooms on other
    devices, your roles, password, log out; on other hosts: who you're logged in as there),
    **Friends** (your friends and groups, each with their own page, see [friends](../accounts/friends.md)) and
    **YouTube Music** (sign-in, history, search and radio with your account; *Sign out* at the
    bottom).
  - **Playing**: **Playback** (next while paused, start radio, asking before interrupting a
    party, pause only stops it here, press play to play here, your other devices, skip silence in podcasts), **Adding
    songs** (long playlists, see [queue](../rooms/queue.md#long-playlists-); when to say what's going
    on, below) and **Sound** (equalizer, headphones, playing next to other apps).
  - **How it looks**: **Look** (image quality, video pictures, songs per page), **Player**
    (the tabs, the dislike button), **Home**, **Search** (the order of the results, the Library
    tab's search bar), **Artist pages** (the order of an artist's page), **Song menu**, **Taps
    and swipes** and **Solo rooms** (what a solo room leaves out, below).
  - **Music and storage**: **Local music**, **Song cache and downloads** (the app) and
    **Where songs play from** (browsers: the order, and your own module).
  - **App** (version, updates, logs, opening the room you were in, the Back button) and **Server admin** (invites, accounts).
  - **Search** ✅: *Search settings* at the top of the list finds a setting as you type, by
    its name or what it says about itself, on every page you have (like Android's settings).
    Pages whose name matches come first; under them each matching setting with its page
    (*Playing · Playback*), and it can be changed right there. Tapping a setting's name, or
    the page above it, opens that page at the setting (it lights up briefly). A heading
    (like *Mix* in Radio) shows its whole card. Nothing found says so. Back or Esc closes the
    search. Settings this device doesn't have (podcasts' skip silence in a browser, the
    equalizer without the app) aren't found.
- **Your settings on all your devices** ✅: logged in with your YTMP account, almost every
  setting is kept on your account, so all your devices have it, and a change on one shows up
  on the others (when you come back to the app). A phone icon next to a setting keeps it
  **only on this device**: it turns blue (*This phone*), and under the setting is what your
  account has, with *Use that* to follow the account again (the device's own value is kept:
  tapping the icon again brings it back). Pages that are one setting as a
  whole (Home, Song menu, Solo rooms) have the icon next to their title. What belongs to one
  device stays on it and has no icon: the equalizer, headphones, the Bluetooth sync
  adjustment, the song cache and downloads, your own module and the Back button. Without an
  account, settings are kept on the device. Settings from before were moved once: onto your
  account when it had none yet, else kept on that device when they were different.
- **Solo rooms** ✅ (Settings → Solo rooms): switch off the party and sharing bits
  you don't need when you listen alone: the room code and share button at the top, the room
  name in the player, the Play on buttons (Play on stays in the avatar menu), "Playing on"
  under the controls, Karaoke, and Party screen, Show on a TV and Clone room in the avatar
  menu. Everything shows by default, and parties always show everything. The room code
  can also be hidden from the room itself: tap it in a solo room, then *Hide the room code in
  solo rooms*. Without the room code, search fills the top bar as a search box, also on
  phones where it's otherwise an icon (*Wide search box*, on by default).
- **Numbers you can type** ✅: every setting that is a number has a box to type it in, next to
  its slider or its choices: the volumes and the sync adjustment in *Play on*, the room's
  waiting and limits and the votes needed to skip (*This room*), songs per page, the song
  cache's size and the playback speed. Enter (or leaving the box) takes it, kept within what
  the setting allows.

## Podcasts ✅

YTMP plays YouTube Music's podcasts like YTM does:

- **Search**: the *Podcasts* and *Episodes* chips, and both in *All*.
- **Podcast page**: cover, author, description, *Latest episode*, and the episodes
  (newest first, with length and description). Tap = add to queue, ⋮ = the song menu.
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

## Gestures

✅ **Tap, swipe right, swipe left and hold** a song do what *Settings → Taps and swipes* says,
separately for songs in lists (search, albums, artists, playlists, Local), songs in the
queue (in *Up next*: the queue, the song playing and what played) and songs in *Autoplay*
(so, say, a swipe removes a song from the queue but adds an autoplay song to it). Each can be
*Play now*, *Play next*, *Add to queue*, *Start radio*, *Song menu* or *Nothing*; in the
queue and *Autoplay* also *Remove*. The defaults:

| Gesture | Songs in lists | Songs in the queue | Songs in *Autoplay* |
|---------|----------------|--------------------|---------------------|
| **Tap** | **Add to queue** (the end) | **Play now**: jump to it, like YTM (the songs before it go to the history as skipped; a played song plays again) | **Add to queue** (the end) |
| **Swipe right** | **Play next** | **Play next** (moved to the start of the queue) | **Play now**: it plays right away; the queue and the autoplay songs above it stay |
| **Swipe left** | **Play now** | **Remove**, like YTM (the current song too: the next one plays) | **Remove** |
| **Hold** | Open the [song menu](menus.md#song-menu) | Open the song menu | Open the song menu |
| **Drag** | Move it in your own playlists (the handle) | Move it: anywhere on the song (with a finger: hold, then move) | Move it, the same way |

A tap on a song that isn't in a list (Home's quick picks, the top search result, a podcast's
episodes, speed dial tiles) does what *Tap* does in lists (*Song menu* there opens the menu
where it has one, else adds to the queue).

Defaults that changed (a list's *Tap* was *Play next*, its *Swipe right* *Add to queue*;
*Autoplay*'s *Tap* was *Play now*, which played it next, and its *Swipe right* *Play next*): a
setting saved back then that still had the old default moves to the new one; any other choice
stays. *Autoplay* does what the queue's songs did until it's set itself (changing the queue's
no longer changes it), with the same move to its new defaults.

✅ **Swipe to skip** (*Settings → Taps and swipes*, default *Mini player and art*): swiping the
mini player or the art in the full player **left plays the next song, right goes back**
(within the first seconds of a song to the one before, later to its start, like Previous), like
YouTube Music. It can be only the mini player, only the art, or off. The mini player follows
the finger, and up (open) and down (clear the room) still work: the first clear direction
decides.

✅ **Play from here**: in an empty room (nothing queued, and nothing playing: no song, or
it's paused or finished), **tapping a song on an album or playlist page plays it from that
song**, like YouTube Music: that song plays now and the ones after it come next; the songs
before it are left out. When something is queued or playing, a tap there does what *Tap* in
lists says (*Play next* by default), so it doesn't throw away what's lined up. *Settings →
Playback → Tap a song to play an album or playlist from there* (on by default) switches the
empty-room tap off. The song menu on those pages always has **Play from here**. It's the
page's *Play* from that song, so the same limits apply (at most *Settings → Adding songs →
most songs at once*, or asked first above the set number, counting from that song on), and in
a party you're asked before it interrupts the music. Long playlists load the songs after it as
needed. It works on every playlist page (also Liked music and YouTube Music's own playlists
like *New episodes*).

✅ **Bigger buttons** on a touch screen (on by default, *Settings → Taps and swipes*): small
icon buttons (⋮, the drag handle, the ones at the top, Next in the mini player) get a
finger-sized tap area of 48 pixels, like YTM; the icons stay the same size. With a mouse nothing
changes.

✅ **⋮ on songs** on a touch screen (off by default, *Settings → Taps and swipes*): songs leave
out their ⋮ where holding a song opens its menu, so their titles get more room. Switched on,
the ⋮ shows. Where *Hold* does something else, the ⋮ always shows.

✅ **Holding doesn't select text** on a touch screen (on by default, *Settings → Taps and
swipes*), so holding a song or button never selects its words; text fields still can be
selected. Switched off, a hold selects text as usual.

A swiped song shows what letting go does underneath, in green once it's far enough (red to
remove); a short swipe does nothing. An action someone may not do (no permission) doesn't
swipe. A **right-click** or **⋮** always opens the song menu, also when *Hold* does something
else (on touch screens the ⋮ then shows, see *⋮ on songs* above). Swipes are for touch screens. Saved
on the device.

With **fair ordering** on, *play next* puts the song in your next fair slot (the message
says "Added … in your next turn"), unless you have the *Reorder* permission.

## YTMP-specific additions on top of a YTM-like UI

- **Room bar** ✅: room name, room code, participants, sharing (QR code)
- **Queue** ✅ showing who added each song
- **Room management** screens ✅ for admins (permissions, roles, kicking, settings)
- **Room switcher** for multiple solo rooms ✅ (the room list)
- **Output picker** ✅ (host outputs) and a "play on this device" toggle
- **Recap** pages from your listening history (planned)
- **Song menu** settings (order, show/hide, dividers, big buttons) ✅
- **Party screen** ✅ for a TV, laptop or tablet (see [party screen](party-screen.md))

## Keyboard shortcuts ✅

In a browser (not in the app): **Space** or **K** play/pause, **N** next, **P** previous,
**←** / **→** 10 seconds back or ahead, **↑** / **↓** volume, **/** jumps to the search box,
**?** shows them all (like YTM). They do what the buttons do (so they need the same
permissions), and aren't active while typing, in a dialog, or with Ctrl, Alt or Cmd held. Also
listed under *Settings → Playback*.

**Moving songs with the keyboard** ✅: on a song's drag handle (reached with Tab), **↑** / **↓**
move it one place, **Home** / **End** to the top or bottom of its list (in Up next: within the
queue, Autoplay or the played songs; on your own playlists: the playlist). The handle keeps the
focus, and a screen reader hears where the song is now.

Reached with the keyboard, everything shows a **white ring** where it is ✅; a search field
without a box of its own rings the box around it.

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

The UI is **English only** for now. More languages are a [future idea](../future-ideas.md#more-languages).
