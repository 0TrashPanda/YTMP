# Friends and sharing songs

Share a song you like with a friend, or with a whole group of friends at once: it shows on
their **Home**, under **From your friends**. Make a group for the music you share with some
people (a hardcore group, say): a cool hardcore song you find goes to everyone in it.

> Status: built ✅. Friends are accounts on the **same YTMP server**. Not yet: friends on other
> servers, a notification when something is shared, and a YTMP link for people without YTMP.

## Friends

- You need a **YTMP account** (on a server). Friends are other accounts on that server.
- **Add a friend** by their **username** (*Friends* page: in Library, the avatar menu, or the
  *Friends* button on Home's *From your friends*). They see your request under *Asking to be
  your friend* and **Accept** or **Decline**. Once they accept, you're friends both ways.
  When they asked you first, adding them is the same as accepting.
- Requests you sent show under your friends (*Asked · waiting for a yes*), with *Cancel*.
- **Remove** a friend: you can't share with each other any more (groups you're both in stay).
- The page shows your own username, to tell friends.

## Groups

- **New group**: a name (like *Hardcore*) and who's in it, picked from your friends.
- The one who made a group **owns** it: they rename it, add and remove people (*Edit*) and
  **delete** it. Everyone else can **leave** it.
- Anyone in a group can share with it; everyone in it gets the song (not the one who shared).

## Sharing a song

- **Share** is in every song's menu (⋮, right-click or hold). Its sheet ([sharing](sharing.md):
  *Send…*, *Copy link*, holding phones together) also lists your **groups** and your
  **friends** to tick, with an optional **note** ("this one!") and **Send**. *Undo* right after
  takes it back from everyone.
- Someone in a group you picked who you also picked by name gets the song once.

## From your friends, on Home

- What friends shared with you, newest first: the song, who shared it (and in which group,
  like *Bob · Hardcore*), how long ago, and their note.
- Tap plays it (like Home's other songs; set in *Settings → Gestures*), ⋮ opens the song menu,
  **×** takes it off your Home.
- The section shows above YouTube Music's sections when something is shared with you, and
  moves, hides and changes layout (carousel, list, speed dial) like any Home section
  (*Settings → Home*). With nothing shared it doesn't show.
- Works in every room where you're logged in with your YTMP account: on your server, in the
  app, and in rooms on other hosts (the songs come straight from your server).

Implementation: `server/.../Social.kt` (the store), the `/api/account/friends`, `/groups` and
`/shares` routes in `AccountApi.kt`, and in the web app `lib/friends.svelte.ts`,
`SongShareSheet.svelte`, `FriendShares.svelte` (Home) and `FriendsView.svelte`. They're reached
like your listening history (see [auth](../implementation/auth.md#your-history-on-other-pages)).
