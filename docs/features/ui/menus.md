# Song and card menus

## Card menu ✅

Albums, playlists, artists and podcasts shown as cards (Home, search, artist pages, also the
tiles of a speed dial and what's pinned on Home) have a menu,
like YouTube Music's: **hold** the card on a touch screen, **right-click** it, or its **⋮**,
which shows on the picture when you point at it with a mouse. A sheet from the bottom on a
phone (swipe it down to close it, like the song menu), a menu at the card on a computer.

- **Album, playlist or artist** (an artist: its top songs): Play, Shuffle play, Play next, Add
  to queue, Add to autoplay, Start radio (from its first song). These use the same limits as a
  page's buttons (*Settings → Adding songs*: at most so many songs, or asked first).
- **Album**, signed in to YouTube Music: Save to playlist (all its songs, to one of your
  playlists or a new one).
- **Podcast**: Play the latest episode.
- **All**: Pin to Home (or Unpin), Share (the phone's share sheet, or the YouTube Music link
  copied).

What you may not do in a party (no permission) is left out.

## Song menu

**Every song** has the same menu, wherever it appears ✅: search results (also the top
result), the queue (upcoming), the queue history (played or skipped), artist pages (top
songs and *Show all*), album pages, playlist pages (also Liked music and YouTube Music's own
playlists), podcast pages (episodes), Home (lists, quick picks, speed dial, pinned things),
find similar, your history, Local and imports. Open it with the **⋮** button, by
**right-clicking** the song, or by **holding** it on a touch screen. Like YouTube Music, a
song's menu on an album's or artist's page has no *Go to* that same page.

> Implemented so far: Play next, Add to queue, Play now (for the current song: play from
> the start), Start radio, Autoplay from here, Find similar, Go to artist / album / podcast
> (their pages; a search when the source gives no ID), Like and Save to playlist (with a
> linked YouTube Music account), Open in YouTube Music, and Remove (from the queue, the
> history or the autoplay queue; in *Library → History*: from your listening history). Download ✅ (YouTube Music songs and episodes), Share ✅ ([sharing.md](../rooms/sharing.md), also with [friends and groups](../accounts/friends.md)). Planned: YTMP playlists. The
> menu is customisable ✅ (below). The current song has the menu too: right-click
> the album art in the full player or the player bar, or its ⋮.
>
> **Artist pages** show the artist's top songs, albums and singles, with *Radio* and
> *Add top songs*. **Show all** under the songs opens all their songs (YTM's list, up to 200)
> as a page with *Play*, *Play next* and *Add to queue*.
>
> **From your library** on an artist's page ✅ (signed in to YouTube Music): their songs you
> liked, newest first (five, with *Show all*), then their playlists and podcasts you saved.
> By default it's right above *Songs*. Songs count when the artist is one of the song's
> artists; it goes by the artist itself, so another artist with the same or a similar name
> doesn't show up (only a song that doesn't say which artist it is goes by the name, the whole
> name, without minding capitals or accents). Its songs work like the others on the page (tap,
> ⋮, swipes). The page never waits for it: it uses the liked songs and library the app already
> keeps (the same as [search](search.md)'s *From your library*), and it's left out when there's
> nothing of theirs, you're not signed in, or your library isn't there yet.
>
> **The order of an artist's page** ✅ (*Settings → Artist pages*, or *Change the order of
> these sections* at the bottom of the page): move *From your library*, *Songs*, *Albums* and
> *Singles & EPs* up and down; *Defaults* puts them back. A section the saved order doesn't
> know yet goes where the default order puts it, and one YouTube Music adds that YTMP has no
> name for goes last. **Album pages** list the songs in order, with *Play*, *Play next* and
> *Add to queue*. Artist and album names under the current song open them.

| Action | Notes |
|--------|-------|
| Play next | Plays next |
| Play from here | On album and playlist pages: plays it from this song (see [gestures](README.md#gestures)) |
| Add to queue | |
| Remove | From the queue, the queue history or the autoplay queue. Needs *Remove own/others' songs*. |
| Start radio | Replace the queue with a [radio](../music/history-and-recommendations.md#radio--mix) from this song |
| Autoplay from here | Replace the [autoplay queue](../rooms/queue.md#autoplay-queue) with a radio from this song, without touching the queue |
| Find similar | Open [find similar](../music/history-and-recommendations.md#find-similar) from this song |
| Like ✅ | Only if a YouTube account is linked |
| Dislike ✅ | Thumbs down; in a solo room the playing song is skipped (if you may) |
| Remove from this playlist ✅ | On your own playlist's page (Liked music: *Remove from Liked music*) |
| Add to playlist | YTMP playlist, saved to your account (planned) |
| Save to playlist ✅ | YouTube Music playlist; only if a YouTube account is linked |
| Go to artist | |
| Go to album | |
| Sleep timer ✅ | Only for the song that's playing: see [sleep timer](../playback-and-outputs.md#sleep-timer-) |
| Download ✅ | Save the song as a plain music file (.m4a with its title, artist, album and cover). In a browser: a normal download. In the app: in the phone's *Music/YTMP* folder, so it shows in Local → *Music on this phone* and in other music apps. Not for local music. |
| Share ✅ | Its YouTube Music link: send it, copy it, or hold another phone against yours ([sharing.md](../rooms/sharing.md)), or send it to [friends and groups](../accounts/friends.md) (on their Home) |
| Open in YouTube Music | For YTM songs |

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

### Hold for choices ✅

**Hold** an action in the song menu for more choices (with a mouse: right-click it, or the
› next to it). A tap still does what it always does.

| Hold | Choices |
|---|---|
| Start radio, Autoplay this | *Radio from* (Mix, YouTube Music, Song graph, On this device), songs you know or new ones, with or without your YouTube Music account, your taste or everyone's (in parties); then *Start* |
| Find similar | where from |
| Play next, Add to queue | play next, the end of the queue, or autoplay (plays when the queue runs out) |
| Save to playlist | saves straight into the playlist you saved to last: "Saved to … · **Change**" |
| Dislike | dislike and don't play this artist in radios, or never put this song in radios (or allow them again) |
| Remove (Up next) | remove the songs by this artist, the songs this person added, the songs above this one or the songs below it (only the ones in its own part of the list: the queue, the played songs or autoplay; only when there are any you may remove). On the song that plays, *above* is the played songs and *below* the queue. All with Undo |
| Go to artist | pick one of all the song's artists (the menu shows only the first; with this hold off, a line each) |
| Download (the app, on the phone's own pages) | save as a music file, or only keep it offline in the app |
| Sleep timer | starts the time used last ("Stops in 30 min · Change") |

The player's buttons too: **Shuffle** (the queue, or the queue and autoplay), **Next**
(the next song from someone else, or past this album), **Play on** (straight to the
speaker or TV used last). *Settings → Song menu → Holding* switches holding off per action
and hides choices you don't use; saved with the menu's layout.

### Share links

> Planned. Built now: sharing a **room** (link and QR code in the room's share sheet), and a
> song's **YTM link** (*Copy link* in its Share sheet, next to [friends and groups](../accounts/friends.md)).

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
