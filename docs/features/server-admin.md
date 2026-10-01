# Server Administration

For the person running a YTMP server.

## Admin page

A web page (for server admins only) to manage the server without editing config files
by hand:

- **Accounts**: create, invite, disable or delete accounts; account sign-up mode
  (see [accounts](accounts.md#creating-an-account))
- **Rooms**: see active rooms, close rooms, manage "always on" rooms
- **Trusted servers**: which other auth servers this server trusts
  (see [accounts](accounts.md#auth-servers))
- **Cache**: size, usage and clearing of the YTM audio cache
- **Server settings**: room codes, timeouts, who may create rooms, tracking default, …

## Updates

- **yt-dlp** is updated **automatically** on the server, because YouTube changes often and
  an outdated yt-dlp breaks playback. The admin page shows the current version and
  allows a manual update.
- The admin page shows when a new YTMP release is available on GitHub.
