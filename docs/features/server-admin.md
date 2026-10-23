# Server Administration

For the person running a YTMP server.

## Admin page ✅

*Settings → Server admin*, on the server's own pages, for the server's admins only. A server
can have several admins; the first account becomes one, and an admin can make others admin.
There is always at least one.

- **Accounts** ✅: every account with when it was last used and on how many devices. Per
  account: make it server admin (or not), rename it, set a new password, log it out on every
  device, delete it (with its listening history, settings and rooms). New accounts (also
  as admin), invite links (once, 7 days), and the open invite links, which can be revoked.
- **Settings** ✅: who can sign up (anyone, with an invite link, or only admins), guests (people
  without an account), listening history for new accounts, trusted servers (other servers
  whose accounts can join, see [accounts](accounts.md#auth-servers)), room codes (letters,
  digits or both, and their length), streaming audio through the server, the
  [song cache](sources.md#song-cache) (how full, its size, *Clear cache*) and how much goes
  in the logs. Numbers are typed. They start as in `ytmp.toml`; a change works right away,
  wins over `ytmp.toml` and says what `ytmp.toml` has, with *Reset* to go back to it. The
  server's address, port, database, module key, speakers and log files stay in `ytmp.toml`.
- **Rooms** ✅: every room with its owner, who's in it now and what plays; *Close* ends one.
- **About** ✅: the server's version and whether a newer release is out, the YouTube Music
  module's yt-dlp and ytmusicapi (or that it can't be reached), and how many accounts and
  rooms there are.
- Not yet: disabling an account without deleting it, timeouts, who may create rooms.

## Updates

> Built differently: the server **updates itself** every night (Watchtower, with images
> GitHub builds for every release, and for the YouTube Music module whenever a new yt-dlp or
> ytmusicapi is out). Update right away with `docker compose pull && docker compose up -d`;
> stay on a version with `YTMP_VERSION` in `.env` (see the
> [Proxmox guide](../../deploy/proxmox.md#updating)). The module logs its yt-dlp version when
> it starts (`docker compose logs ytm`). The admin page shows the versions (*About*).

- **yt-dlp** is updated **automatically** on the server, because YouTube changes often and
  an outdated yt-dlp breaks playback. The admin page shows the current version and
  allows a manual update.
- The admin page shows when a new YTMP release is available on GitHub.
- **Open pages** ✅ get the new version by themselves: a browser that still shows the old
  page reloads it soon after the update (when it comes back into view, when it reconnects to
  the restarted server, or within 10 minutes). Not while sound plays in that browser (it
  would need a tap to play again) or while you're typing: then it waits, and says a tap
  reloads it now. Nobody has to clear their browser's cache.

## Logs ✅

The server logs to the console and to log files that clean up after themselves: a file per
day, kept 14 days and never more than 100 MB together (both settings in `[logs]` of
`ytmp.toml`). In Docker they're in the data volume (`logs/`), and Docker's own console log
is capped too. What's in them, to find out why a song stopped or buffered: every stream
lookup (how long, which format, when YouTube's link expires), yt-dlp's steps when a lookup
fails or is slow, links YouTube refuses (403) and the retry, slow or failed audio through
the server, and problems listeners' players report (a direct link that failed, long
buffering).

