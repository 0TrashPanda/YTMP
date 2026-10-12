# Server Administration

For the person running a YTMP server.

## Admin page

> So far: the server admin manages accounts on `/account` (invite links, create accounts,
> set passwords). The rest below is still to do.

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

> Built differently: the server **updates itself** every night (Watchtower, with images
> GitHub builds for every release, and for the YouTube Music module whenever a new yt-dlp or
> ytmusicapi is out). Update right away with `docker compose pull && docker compose up -d`;
> stay on a version with `YTMP_VERSION` in `.env` (see the
> [Proxmox guide](../../deploy/proxmox.md#updating)). The module logs its yt-dlp version when
> it starts (`docker compose logs ytm`). Not built: the admin page showing versions.

- **yt-dlp** is updated **automatically** on the server, because YouTube changes often and
  an outdated yt-dlp breaks playback. The admin page shows the current version and
  allows a manual update.
- The admin page shows when a new YTMP release is available on GitHub.

## Logs ✅

The server logs to the console and to log files that clean up after themselves: a file per
day, kept 14 days and never more than 100 MB together (both settings in `[logs]` of
`ytmp.toml`). In Docker they're in the data volume (`logs/`), and Docker's own console log
is capped too. What's in them, to find out why a song stopped or buffered: every stream
lookup (how long, which format, when YouTube's link expires), yt-dlp's steps when a lookup
fails or is slow, links YouTube refuses (403) and the retry, slow or failed audio through
the server, and problems listeners' players report (a direct link that failed, long
buffering).

