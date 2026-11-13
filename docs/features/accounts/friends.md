# Friends and sharing songs

Share a song you like with a friend, or with a whole group of friends at once: it shows on
their **Home**, under **From your friends**. Make a group for the music you share with some
people (a hardcore group, say): a cool hardcore song you find goes to everyone in it.

> Status: built ✅. Friends are accounts on the **same YTMP server**. Not yet: friends on other
> servers, a notification when something is shared, and a YTMP link for people without YTMP.

## Friends

- You need a **YTMP account** (on a server). Friends are other accounts on that server.
- Friends are in **Settings → Friends** (also the *Friends* button on Home's *From your
  friends*, and *Friends* in the share sheet).
- **Add a friend** by their **username**. They see your request under *Asking to be
  your friend* and **Accept** or **Decline**. Once they accept, you're friends both ways.
  When they asked you first, adding them is the same as accepting.
- Requests you sent show under your friends (*Asked · waiting for a yes*), with *Cancel*.
- **Remove** a friend: you can't share with each other any more (groups you're both in stay).
- The page shows your own username, to tell friends.

## A friend's or a group's page

Tap a friend or a group in *Settings → Friends* to open their page (in a room it opens as a
page of the room, like an artist's; on the room list it shows inside Settings):

- **Show on Home**: a switch for whether what they share shows on your Home. Off for a friend
  hides everything they share with you, also in groups; off for a group hides what's shared in
  it. Kept with your account (all your devices). In the list it says *Not on Home*.
- **Shared with you**: everything they ever shared with you, newest first, with their note and
  when (on a friend's page also the group it came through; on a group's page who shared it).
  Songs you took off Home stay here. In a room, tap plays (like Home's songs), ⋮ opens the song
  menu, and **Play**, **Play next** and **Add to queue** take all of them.

## Groups

- **New group**: a name (like *Hardcore*) and who's in it, picked from your friends.
- The one who made a group **owns** it: they rename it, add and remove people (*Edit*) and
  **delete** it. Everyone else can **leave** it.
- Anyone in a group can share with it; everyone in it gets the song (not the one who shared).

## Sharing a song

- **Share** is in every song's menu (⋮, right-click or hold). Its sheet ([sharing](../rooms/sharing.md):
  *Send…*, *Copy link*, holding phones together) also lists your **groups** and your
  **friends** to tick, with an optional **note** ("this one!") and **Send**. *Undo* right after
  takes it back from everyone.
- Someone in a group you picked who you also picked by name gets the song once.

## From your friends, on Home

- What friends shared with you, newest first: the song, who shared it (and in which group,
  like *Bob · Hardcore*), how long ago, and their note.
- Tap plays it (like Home's other songs; set in *Settings → Gestures*), ⋮ opens the song menu,
  **×** takes it off your Home, with **Undo** for a few seconds (it stays on the friend's page).
- The section shows above YouTube Music's sections when something is shared with you, and
  moves, hides and changes layout (carousel, list, speed dial) like any Home section
  (*Settings → Home*). With nothing shared it doesn't show.
- Works in every room where you're logged in with your YTMP account: on your server, in the
  app, and in rooms on other hosts (the songs come straight from your server).

How it's built: [auth](../../implementation/auth.md#friends-groups-and-shared-songs).
