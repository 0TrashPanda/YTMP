# Importing playlists

> Status: **planned**. First: a **Spotify data export** into **YouTube Music playlists**.
> Later: other exports (Google Takeout's playlists), playlists from a linked account (YouTube
> Music, Spotify) and **YTMP playlists** as a place to put them.
> Implementation: [playlist converter](../implementation/playlist-converter.md).

Bring your playlists from somewhere else into YTMP: from a file you download (an export) or
from an account you linked. YTMP finds each song on the source you're importing into (for now
YouTube Music) and makes the playlist there. It takes a while, so it runs in the background,
and you can check the songs it wasn't sure about.

## Where from

- **Spotify data export** (first): on spotify.com, *Account → Privacy settings → Download
  your data*, tick *Account data*. Spotify emails a zip within a few days. It has the playlists
  you made, your liked songs, saved albums, and the podcasts you follow and saved. No Spotify
  developer app or Premium needed.
- Later: **Google Takeout** (YouTube Music playlists), and **linked accounts**: YouTube Music
  (to YTMP playlists) and Spotify (it has
  [limits](future-ideas.md#spotify-module)).

## Where to

- A **new YouTube Music playlist** (private), or **added to one of yours** (only the songs that
  aren't in it yet). Needs a [linked YouTube account](accounts.md#linked-youtube-account).
- Later: a **YTMP playlist**, as a choice next to YouTube Music.

## How it goes

1. **Import** (in *Library*, and in *Settings*): pick where from, for now *Spotify data
   export*, and pick the zip. A short how-to says how to get it from Spotify.
2. **What to import**: the playlists in it with their number of songs, plus *Liked songs*
   and *Podcasts*, each with a checkbox. For each one: a new playlist (the name can be
   changed) or one of your playlists to add to.
3. The [settings](#settings) for this import, then **Start**.
4. YTMP looks up the songs in the background, a calm pace at a time so YouTube doesn't block
   your network. Songs that are in several playlists are looked up once, and songs it found
   before (also for other people on your server) aren't looked up again.
5. Each playlist is made **as soon as its songs are looked up**, with the songs YTMP is sure
   about, in their order. The others wait for you and are put in their place once you pick
   them.
6. A notification on the phone when it's done, or when it needs you.

If YouTube asks whether you're a bot, the import pauses (*"Paused by YouTube's bot check,
again at 14:20"*) and goes on by itself. Closing the app or restarting the server doesn't
lose anything: it goes on where it was. Importing the same thing again doesn't add songs twice.

## Your imports

A list of your imports, each with a bar, *312 of 1,040 songs · about 40 min left*, and how
many were **found**, **not sure** and **not found**. *Pause*, *Go on* and *Cancel* (the
playlists made so far stay). When it's done: *Open in YouTube Music*.

## Checking songs

The songs YTMP wasn't sure about or didn't find, in a list like YouTube Music's:

- Filters: *Not sure*, *Not found*, *All*.
- Each song shows what it was (title, artist, album) and what YTMP picked, with its cover.
  Tap to **listen**, to see the **other options** it found, to **search yourself**, or to
  **leave it out**.
- *Accept all* for when the ones it wasn't sure about look right.

A song you pick is remembered, so it's right in the next import too.

## Settings

The defaults are in *Settings*, and each import can change them before it starts.

- **Add songs I'm not sure about without asking**: off.
- **Music videos when there's no song version**: on. YTMP prefers the song itself (the
  album version) over its video.
- **Liked songs**: into a playlist *Liked on Spotify* (default), or as likes on YouTube Music
  (slowly; thousands of likes at once look like spam to YouTube).
- **Podcasts**: the shows you follow are followed on YouTube Music, saved episodes go into a
  playlist.
- **The same song twice in a playlist**: keep it (default) or once.

## Not included

- As far as we know the export only has playlists **you made** (not Spotify's own, such as Discover Weekly,
  nor ones you only follow) and it's a snapshot: later changes on Spotify don't come along.
- Your Spotify listening history isn't imported (yet).
