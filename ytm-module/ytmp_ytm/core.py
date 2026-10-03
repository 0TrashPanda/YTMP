"""YouTube Music logic as plain functions, with no HTTP.

The server wraps this in http.py; on Android, Chaquopy will call it directly.
See docs/implementation/module-api.md.
"""

from __future__ import annotations

import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
import urllib.parse
from dataclasses import dataclass, field

import requests
import yt_dlp
from ytmusicapi import YTMusic
from ytmusicapi.helpers import initialize_headers

MODULE_ID = "ytm"
_PREFIXES = ("ytm:", "yt:")
_LARGE_ART = 544
_ARTIST_SONGS = 20
_PLAYLIST_SONGS = 200
_HOME_SECTIONS = 12
_LIBRARY_ITEMS = 200
_YTM_ORIGIN = "https://music.youtube.com"
# Search types (the chips above the results) -> ytmusicapi's search filter.
SEARCH_TYPES = {
    "songs": "songs",
    "videos": "videos",
    "albums": "albums",
    "artists": "artists",
    "community_playlists": "community_playlists",
    "featured_playlists": "featured_playlists",
    "podcasts": "podcasts",
    "episodes": "episodes",
}
_SECTION_TITLES = {
    "songs": "Songs",
    "videos": "Videos",
    "albums": "Albums",
    "artists": "Artists",
    "community_playlists": "Community playlists",
    "featured_playlists": "Featured playlists",
    "podcasts": "Podcasts",
    "episodes": "Episodes",
}
# How many of each kind the "all" search shows; the section's chip shows more.
_ALL_LIMITS = {
    "songs": 4, "videos": 4, "albums": 6, "artists": 6, "community_playlists": 6, "featured_playlists": 6,
    "podcasts": 6, "episodes": 4,
}
# YouTube Music's own playlists are "featured"; everyone else's are "community".
_YTM_AUTHOR = "YouTube Music"
# Re-resolve stream URLs this long before YouTube says they expire.
_EXPIRY_MARGIN_S = 10 * 60


class NotFound(Exception):
    pass


class Unavailable(Exception):
    pass


class NotSignedIn(Exception):
    pass


@dataclass
class StreamInfo:
    url: str
    expires_at: float
    mime_type: str
    bitrate: float | None
    # Exact, unlike some listings (podcasts say "3 hr 36 min"); the host uses it to end the song.
    duration_ms: int | None = None
    http_headers: dict[str, str] = field(default_factory=dict)

    def to_json(self) -> dict:
        return {
            "url": self.url,
            "expiresAt": int(self.expires_at * 1000),
            "mimeType": self.mime_type,
            "bitrate": self.bitrate,
            "loudnessDb": None,
            "durationMs": self.duration_ms,
        }


def video_id(song_id: str) -> str:
    """'ytm:abc' -> 'abc'. Plain IDs are accepted too."""
    for prefix in _PREFIXES:
        if song_id.startswith(prefix):
            return song_id[len(prefix):]
    return song_id


class YtmCore:
    def __init__(self, language: str = "en", location: str = "BE", js_runtime: str | None = None):
        self._ytm = YTMusic(language=language, location=location)
        self._ydl_opts = {
            "format": "bestaudio[ext=m4a]/bestaudio",
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
        }
        if js_runtime:
            self._ydl_opts["js_runtimes"] = {js_runtime: {}}
        self._streams: dict[str, StreamInfo] = {}
        self._lock = threading.Lock()
        self._language = language
        self._location = location
        # The signed-in YouTube Music account (the phone owner's), next to the anonymous one
        # that everything shared (search, rooms) keeps using.
        self._user: YTMusic | None = None

    def sign_in(self, cookie: str) -> dict:
        """Uses the cookies of a signed-in music.youtube.com session for personal pages.

        Raises NotSignedIn when they don't belong to a signed-in session (any more).
        Returns the account (see account()).
        """
        if "__Secure-3PAPISID=" not in cookie:
            raise NotSignedIn("Not signed in to YouTube Music")
        headers = initialize_headers()
        headers.update({"cookie": cookie, "x-goog-authuser": "0", "x-origin": _YTM_ORIGIN, "origin": _YTM_ORIGIN, "authorization": "SAPISIDHASH 0"})
        user = YTMusic(auth=dict(headers), language=self._language, location=self._location)
        account = _account(user)
        self._user = user
        return account

    def sign_out(self) -> None:
        self._user = None

    def account(self) -> dict | None:
        """The signed-in account: {name, handle, photoUrl}, or None."""
        return _account(self._user) if self._user else None

    def _signed_in(self) -> YTMusic:
        if not self._user:
            raise NotSignedIn("Not signed in to YouTube Music")
        return self._user

    def library(self) -> dict:
        """The signed-in account's playlists (Liked music first) and podcasts, as home sections."""
        user = self._signed_in()
        with ThreadPoolExecutor(2) as pool:
            playlists = pool.submit(user.get_library_playlists, limit=_LIBRARY_ITEMS)
            podcasts = pool.submit(user.get_library_podcasts, limit=_LIBRARY_ITEMS)
            sections = [
                {"title": "Playlists", "items": [item for p in playlists.result() if (item := _library_playlist(p))]},
                {"title": "Podcasts", "items": [item for p in podcasts.result() if (item := _library_podcast(p))]},
            ]
        return {"sections": sections}

    def search(self, query: str, type: str = "all", limit: int = 20) -> dict:
        """Search results as sections (see SearchPage in protocol/Catalog.kt).

        One type gives one section; "all" gives YTM's mixed results, grouped like YTM does.
        """
        if type == "all":
            # YTM's mixed results mostly lack song durations (and sometimes artists), so the
            # songs and videos come from their own searches, run at the same time.
            with ThreadPoolExecutor(3) as pool:
                mixed = pool.submit(self._ytm.search, query)
                songs = pool.submit(self._ytm.search, query, filter="songs", limit=_ALL_LIMITS["songs"])
                videos = pool.submit(self._ytm.search, query, filter="videos", limit=_ALL_LIMITS["videos"])
                return {"sections": _sections_from_all(mixed.result(), songs.result(), videos.result())}
        if type not in SEARCH_TYPES:
            raise NotFound(f"Unknown search type: {type}")
        results = self._ytm.search(query, filter=SEARCH_TYPES[type], limit=limit)
        # ytmusicapi treats limit as a minimum, so cut the list ourselves.
        items = [item for r in results if (item := _search_item(r))][:limit]
        return {"sections": [{"title": _SECTION_TITLES[type], "type": type, "items": items}]}

    def suggestions(self, query: str) -> list[str]:
        """What YTM suggests while typing [query], e.g. "daft p" -> "daft punk one more time"."""
        if not query.strip():
            return []
        return [s for s in self._ytm.get_search_suggestions(query) if isinstance(s, str)]

    def radio(self, seed_id: str, limit: int = 25) -> list[dict]:
        """YTM's radio for a song: similar songs, usually starting with the song itself."""
        try:
            playlist = self._ytm.get_watch_playlist(videoId=video_id(seed_id), radio=True, limit=limit)
        except Exception as e:  # ytmusicapi raises plain exceptions for unknown IDs
            raise NotFound(str(e)) from e
        return [song for t in playlist.get("tracks") or [] if (song := _song_from_watch(t))][:limit]

    def artist(self, artist_id: str) -> dict:
        """An artist page: their top songs, albums and singles."""
        try:
            a = self._ytm.get_artist(artist_id)
        except Exception as e:
            raise NotFound(str(e)) from e
        songs_section = a.get("songs") or {}
        songs: list[dict] = []
        # The artist's songs are a playlist; read more of it than the 5 on the page.
        if songs_section.get("browseId"):
            try:
                playlist = self._ytm.get_playlist(songs_section["browseId"], limit=_ARTIST_SONGS)
                songs = [s for t in playlist.get("tracks") or [] if (s := _song_from_search(t))]
            except Exception:
                songs = []
        if not songs:
            songs = [s for t in songs_section.get("results") or [] if (s := _song_from_search(t))]
        return {
            "id": artist_id,
            "name": a.get("name") or "",
            "thumbnails": _thumbnails(a.get("thumbnails") or []),
            "description": a.get("description"),
            "songs": songs[:_ARTIST_SONGS],
            "albums": [_album_summary(x, "Album") for x in (a.get("albums") or {}).get("results") or [] if x.get("browseId")],
            "singles": [_album_summary(x, "Single") for x in (a.get("singles") or {}).get("results") or [] if x.get("browseId")],
        }

    def album(self, album_id: str) -> dict:
        """An album with its songs (they use the album's art)."""
        try:
            alb = self._ytm.get_album(album_id)
        except Exception as e:
            raise NotFound(str(e)) from e
        thumbnails = _thumbnails(alb.get("thumbnails") or [])
        album_ref = {"id": album_id, "name": alb.get("title") or ""}
        songs = []
        for t in alb.get("tracks") or []:
            if not t.get("videoId"):
                continue
            songs.append({
                "id": f"{MODULE_ID}:{t['videoId']}",
                "title": t.get("title") or "",
                "artists": [{"id": a.get("id"), "name": a.get("name", "")} for a in t.get("artists") or []],
                "album": album_ref,
                "durationMs": int((t.get("duration_seconds") or 0) * 1000) or _parse_length(t.get("duration")),
                "thumbnails": thumbnails,
                "explicit": bool(t.get("isExplicit")),
            })
        return {
            "id": album_id,
            "title": alb.get("title") or "",
            "kind": alb.get("type") or "Album",
            "year": alb.get("year"),
            "artists": [{"id": a.get("id"), "name": a.get("name", "")} for a in alb.get("artists") or []],
            "thumbnails": thumbnails,
            "songs": songs,
        }

    def playlist(self, playlist_id: str, personal: bool = False) -> dict:
        """A playlist with its songs: a public one, or with [personal] also the signed-in account's own (Liked music is "LM")."""
        ytm = self._signed_in() if personal else self._ytm
        try:
            p = ytm.get_playlist(playlist_id, limit=_PLAYLIST_SONGS)
        except Exception as e:
            raise NotFound(str(e)) from e
        author = p.get("author")
        return {
            "id": playlist_id,
            "title": p.get("title") or "",
            "author": (author.get("name") if isinstance(author, dict) else author) or None,
            "description": p.get("description"),
            "thumbnails": _thumbnails(p.get("thumbnails") or []),
            "songs": [s for t in p.get("tracks") or [] if (s := _song_from_search(t))],
        }

    def podcast(self, podcast_id: str) -> dict:
        """A podcast with its episodes, newest first."""
        try:
            p = self._ytm.get_podcast(podcast_id)
        except Exception as e:
            raise NotFound(str(e)) from e
        ref = {"id": podcast_id, "name": p.get("title") or ""}
        author = p.get("author")
        return {
            "id": podcast_id,
            "title": ref["name"],
            "author": (author.get("name") if isinstance(author, dict) else author) or None,
            "description": p.get("description"),
            "thumbnails": _thumbnails(p.get("thumbnails") or []),
            "episodes": [
                {"song": song, "date": _episode_date(e.get("date")), "description": e.get("description")}
                for e in p.get("episodes") or []
                if (song := _episode_song(e, ref))
            ],
        }

    def home(self, personal: bool = False) -> dict:
        """YTM's home page: quick picks, new releases, mixes and playlists.

        [personal]: the signed-in account's own home, instead of the one without an account.
        """
        ytm = self._signed_in() if personal else self._ytm
        sections = []
        for s in ytm.get_home(limit=_HOME_SECTIONS):
            items = [item for c in s.get("contents") or [] if (item := _home_item(c))]
            if items:
                sections.append({"title": s.get("title") or "", "items": items})
        return {"sections": sections}

    def stream(self, song_id: str, fresh: bool = False) -> StreamInfo:
        """Direct stream URL, resolved on demand and cached until shortly before it expires.

        [fresh] skips the cache, for when a cached URL stopped working before its expiry
        (YouTube sometimes revokes them early).
        """
        vid = video_id(song_id)
        with self._lock:
            cached = None if fresh else self._streams.get(vid)
        if cached and cached.expires_at - _EXPIRY_MARGIN_S > time.time():
            return cached

        try:
            with yt_dlp.YoutubeDL(self._ydl_opts) as ydl:
                info = ydl.extract_info(f"https://music.youtube.com/watch?v={vid}", download=False)
        except yt_dlp.utils.DownloadError as e:
            raise Unavailable(_clean_error(str(e))) from e

        url = info["url"]
        expires = urllib.parse.parse_qs(urllib.parse.urlparse(url).query).get("expire", [None])[0]
        stream = StreamInfo(
            url=url,
            expires_at=float(expires) if expires else time.time() + 3600,
            mime_type="audio/mp4" if info.get("ext") == "m4a" else f"audio/{info.get('ext', 'webm')}",
            bitrate=info.get("abr"),
            duration_ms=int(info["duration"] * 1000) if info.get("duration") else None,
            http_headers=dict(info.get("http_headers") or {}),
        )
        with self._lock:
            self._streams[vid] = stream
        return stream


def _song_from_search(r: dict) -> dict | None:
    vid = r.get("videoId")
    if not vid:
        return None
    album = r.get("album")
    return {
        "id": f"{MODULE_ID}:{vid}",
        "title": r.get("title") or "",
        "artists": [{"id": a.get("id"), "name": a.get("name", "")} for a in r.get("artists") or []],
        "album": {"id": album.get("id"), "name": album.get("name", "")} if album else None,
        "durationMs": int((r.get("duration_seconds") or 0) * 1000),
        "thumbnails": _thumbnails(r.get("thumbnails") or []),
        "explicit": bool(r.get("isExplicit")),
    }


def _search_item(r: dict) -> dict | None:
    """One search result as a SearchItem (protocol/Catalog.kt), or None for kinds we don't show."""
    kind = r.get("resultType")
    if kind in ("song", "video"):
        song = _song_from_search(r)
        return {"kind": "song", "song": song, "video": kind == "video"} if song else None
    if kind == "episode":
        podcast = r.get("podcast") or {}
        ref = {"id": podcast["id"], "name": podcast.get("name", "")} if podcast.get("id") else None
        song = _episode_song(r, ref)
        return {"kind": "song", "song": song, "video": False} if song else None
    if kind == "podcast" and r.get("browseId"):
        return {
            "kind": "podcast",
            "id": r["browseId"],
            "title": r.get("title") or "",
            "author": r.get("author") or (r.get("artists") or [{}])[0].get("name"),
            "thumbnails": _thumbnails(r.get("thumbnails") or []),
        }
    if kind == "album" and r.get("browseId"):
        return {
            "kind": "album",
            "album": _album_summary(r, "Album"),
            "artists": [{"id": a.get("id"), "name": a.get("name", "")} for a in r.get("artists") or []],
        }
    if kind == "artist":
        # An artist as the top result has its name and ID in "artists" instead.
        ref = next(iter(r.get("artists") or []), {})
        artist_id = r.get("browseId") or ref.get("id")
        if not artist_id:
            return None
        return {
            "kind": "artist",
            "id": artist_id,
            "name": r.get("artist") or ref.get("name") or "",
            "thumbnails": _thumbnails(r.get("thumbnails") or []),
        }
    if kind == "playlist" and r.get("browseId"):
        count = r.get("itemCount")
        return {
            "kind": "playlist",
            "id": r["browseId"],
            "title": r.get("title") or "",
            "author": r.get("author"),
            "itemCount": int(count) if isinstance(count, (int, str)) and str(count).isdigit() else None,
            "thumbnails": _thumbnails(r.get("thumbnails") or []),
        }
    return None  # profiles, …


def _home_item(c: dict) -> dict | None:
    """One item of a home section as a SearchItem: songs and videos lack a length (the stream has it)."""
    if c.get("videoId"):
        song = _song_from_search(c)
        return {"kind": "song", "song": song, "video": c.get("videoType") != "MUSIC_VIDEO_TYPE_ATV"} if song else None
    browse_id = c.get("browseId") or ""
    if browse_id.startswith("MPRE"):
        return {
            "kind": "album",
            "album": _album_summary(c, c.get("type") or "Album"),
            "artists": [{"id": a.get("id"), "name": a.get("name", "")} for a in c.get("artists") or []],
        }
    if browse_id.startswith("MPSP"):
        return {"kind": "podcast", "id": browse_id, "title": c.get("title") or "", "author": None, "thumbnails": _thumbnails(c.get("thumbnails") or [])}
    if browse_id.startswith("UC"):
        return {"kind": "artist", "id": browse_id, "name": c.get("title") or "", "thumbnails": _thumbnails(c.get("thumbnails") or [])}
    if c.get("playlistId"):
        return {
            "kind": "playlist",
            "id": c["playlistId"],
            "title": c.get("title") or "",
            # e.g. "Alicia Keys, Ed Sheeran, Lewis Capaldi"
            "author": c.get("description"),
            "itemCount": None,
            "thumbnails": _thumbnails(c.get("thumbnails") or []),
        }
    return None


def _account(user: YTMusic) -> dict:
    try:
        info = user.get_account_info()
    except requests.RequestException:
        raise  # offline: that says nothing about the sign-in
    except Exception as e:  # expired or signed out: YouTube answers without an account
        raise NotSignedIn(f"Not signed in to YouTube Music ({e})") from e
    return {"name": info.get("accountName") or "", "handle": info.get("channelHandle"), "photoUrl": info.get("accountPhotoUrl")}


def _library_playlist(p: dict) -> dict | None:
    if not p.get("playlistId"):
        return None
    count = p.get("count")
    return {
        "kind": "playlist",
        "id": p["playlistId"],
        "title": p.get("title") or "",
        "author": p.get("description") or None,
        "itemCount": int(count.replace(",", "")) if isinstance(count, str) and count.replace(",", "").isdigit() else None,
        "thumbnails": _thumbnails(p.get("thumbnails") or []),
    }


def _library_podcast(p: dict) -> dict | None:
    """A podcast in the library; "New episodes" (an automatic playlist) is a playlist."""
    browse_id = p.get("browseId") or ""
    channel = (p.get("channel") or {}).get("name")
    if browse_id.startswith("MPSP"):
        return {"kind": "podcast", "id": browse_id, "title": p.get("title") or "", "author": channel, "thumbnails": _thumbnails(p.get("thumbnails") or [])}
    if p.get("podcastId"):
        return {"kind": "playlist", "id": p["podcastId"], "title": p.get("title") or "", "author": channel, "itemCount": None, "thumbnails": _thumbnails(p.get("thumbnails") or [])}
    return None


def _search_type(r: dict) -> str | None:
    """Which search type (chip) a result of the "all" search belongs to."""
    kind = r.get("resultType")
    if kind == "playlist":
        return "featured_playlists" if r.get("author") == _YTM_AUTHOR else "community_playlists"
    return {
        "song": "songs", "video": "videos", "album": "albums", "artist": "artists", "podcast": "podcasts", "episode": "episodes",
    }.get(kind)


def _sections_from_all(results: list[dict], songs: list[dict], videos: list[dict]) -> list[dict]:
    """YTM's unfiltered search: the top result, then a few of each kind, in YTM's order.

    [songs] and [videos] (filtered searches) replace the mixed results' songs and videos,
    which lack durations.
    """
    complete = {r["videoId"]: r for r in songs + videos if r.get("videoId")}
    sections: list[dict] = []
    for r in results:
        if r.get("category") == "Top result":
            r = complete.get(r.get("videoId"), r) if r.get("videoId") else r
            if item := _search_item(r):
                sections.append({"title": "Top result", "type": None, "items": [item]})
            break
    by_type: dict[str, list[dict]] = {}
    for r in results:
        if r.get("category") != "Top result" and (t := _search_type(r)) and (item := _search_item(r)):
            by_type.setdefault(t, []).append(item)
    for t, filtered in (("songs", songs), ("videos", videos)):
        items = [item for r in filtered if (item := _search_item(r))]
        if items:
            by_type[t] = items  # keeps its place if the mixed results had it, else goes last
        else:
            by_type.pop(t, None)
    for t, items in by_type.items():  # dicts keep insertion order: the order YTM gave
        sections.append({"title": _SECTION_TITLES[t], "type": t, "items": items[:_ALL_LIMITS[t]]})
    return sections


def _episode_song(e: dict, podcast: dict | None) -> dict | None:
    """A podcast episode as a Song with "podcast" set. Its length may be rounded; the stream has the exact one."""
    vid = e.get("videoId")
    if not vid:
        return None
    return {
        "id": f"{MODULE_ID}:{vid}",
        "title": e.get("title") or "",
        "artists": [],
        "album": None,
        "durationMs": int((e.get("duration_seconds") or 0) * 1000) or _parse_spoken_length(e.get("duration")),
        "thumbnails": _thumbnails(e.get("thumbnails") or []),
        "explicit": False,
        "podcast": podcast,
    }


def _episode_date(value: str | None) -> str | None:
    """ytmusicapi sometimes puts the view count where the date goes; that's not a date."""
    return None if not value or "view" in value else value


def _parse_spoken_length(length: str | None) -> int:
    """'3 hr 36 min', '29 min', '45 sec' -> milliseconds; '4:21' too; 0 if unknown."""
    if not length:
        return 0
    if ":" in length:
        return _parse_length(length)
    units = {"hr": 3600, "hour": 3600, "min": 60, "sec": 1}
    seconds = 0
    for amount, unit in re.findall(r"(\d+)\s*(hr|hour|min|sec)", length):
        seconds += int(amount) * units[unit]
    return seconds * 1000


def _song_from_watch(t: dict) -> dict | None:
    """A track of a watch playlist (radio) has slightly different fields than a search result."""
    vid = t.get("videoId")
    if not vid:
        return None
    album = t.get("album")
    return {
        "id": f"{MODULE_ID}:{vid}",
        "title": t.get("title") or "",
        "artists": [{"id": a.get("id"), "name": a.get("name", "")} for a in t.get("artists") or []],
        "album": {"id": album.get("id"), "name": album.get("name", "")} if album else None,
        "durationMs": _parse_length(t.get("length")),
        "thumbnails": _thumbnails(t.get("thumbnail") or t.get("thumbnails") or []),
        "explicit": bool(t.get("isExplicit")),
    }


def _album_summary(x: dict, kind: str) -> dict:
    return {
        "id": x["browseId"],
        "title": x.get("title") or "",
        "kind": x.get("type") or kind,
        "year": x.get("year"),
        "thumbnails": _thumbnails(x.get("thumbnails") or []),
    }


def _parse_length(length: str | None) -> int:
    """'4:21' or '1:02:03' -> milliseconds; 0 if unknown."""
    if not length:
        return 0
    try:
        seconds = 0
        for part in length.split(":"):
            seconds = seconds * 60 + int(part)
        return seconds * 1000
    except ValueError:
        return 0


def _thumbnails(thumbs: list[dict]) -> list[dict]:
    out = [{"url": t["url"], "width": t.get("width", 0), "height": t.get("height", 0)} for t in thumbs]
    # YTM only lists small sizes; the same image is available larger by changing the URL.
    # Artist pictures are cropped ("=w120-c-h120-…"), album art isn't ("=w120-h120-…").
    size = r"=w\d+-(c-)?h\d+"
    if thumbs and re.search(size, thumbs[-1]["url"]):
        large = re.sub(size, lambda m: f"=w{_LARGE_ART}-{m.group(1) or ''}h{_LARGE_ART}", thumbs[-1]["url"])
        out.append({"url": large, "width": _LARGE_ART, "height": _LARGE_ART})
    return out


# Fetch YouTube audio in pieces this big: requests without a range are throttled.
CHUNK_SIZE = 1024 * 1024
_OPEN_END = 1 << 62


def parse_range(header: str | None) -> tuple[int, int]:
    """'bytes=100-' -> (100, open end); no header -> the whole file. Suffix ranges aren't supported."""
    match = re.fullmatch(r"bytes=(\d+)-(\d*)", (header or "").strip())
    if not match:
        return 0, _OPEN_END
    return int(match.group(1)), int(match.group(2)) if match.group(2) else _OPEN_END


def total_size(content_range: str | None) -> int | None:
    """'bytes 0-1023/5000' -> 5000."""
    match = re.search(r"/(\d+)$", content_range or "")
    return int(match.group(1)) if match else None


def _clean_error(message: str) -> str:
    if "confirm you" in message and "not a bot" in message:
        # YouTube's bot check: it blocks this network for a while after many requests.
        return "YouTube is blocking requests from this network for now (bot check). Try again later."
    return re.sub(r"^ERROR: (\[[^\]]+\] )?([\w-]+: )?", "", message).strip()
