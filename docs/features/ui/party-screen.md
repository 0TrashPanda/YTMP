# Party screen

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
  [Chromecast](../../implementation/outputs/chromecast.md#the-party-screen-on-a-tv-).
- **On your own TV, in a room on a server** (with the app): the server can't reach the TVs
  at your place, but the app can. *Play on* lists the Chromecasts the phone finds (the ones
  the host doesn't offer itself), each with a switch that shows the party screen there,
  which plays the music too. It needs *play on your own device*. Switched off, cast over by
  someone else, or the TV turned off, the screen leaves the room.
