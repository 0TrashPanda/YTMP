"""YouTube Music logic as plain functions, with no HTTP.

The server wraps this in http.py; on Android, Chaquopy will call it directly.
See docs/implementation/module-api.md.
"""

from __future__ import annotations

import re
import threading
import time
import urllib.parse
from dataclasses import dataclass, field

import yt_dlp
from ytmusicapi import YTMusic

MODULE_ID = "ytm"
_PREFIXES = ("ytm:", "yt:")
_LARGE_ART = 544
_ARTIST_SONGS = 20
# Re-resolve stream URLs this long before YouTube says they expire.
_EXPIRY_MARGIN_S = 10 * 60


class NotFound(Exception):
    pass


class Unavailable(Exception):
    pass


@dataclass
class StreamInfo:
    url: str
    expires_at: float
    mime_type: str
    bitrate: float | None
    http_headers: dict[str, str] = field(default_factory=dict)

    def to_json(self) -> dict:
        return {
            "url": self.url,
            "expiresAt": int(self.expires_at * 1000),
            "mimeType": self.mime_type,
            "bitrate": self.bitrate,
            "loudnessDb": None,
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

    def search(self, query: str, limit: int = 20) -> list[dict]:
        results = self._ytm.search(query, filter="songs", limit=limit)
        # ytmusicapi treats limit as a minimum, so cut the list ourselves.
        return [song for r in results if (song := _song_from_search(r))][:limit]

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
    if thumbs and re.search(r"=w\d+-h\d+", thumbs[-1]["url"]):
        large = re.sub(r"=w\d+-h\d+", f"=w{_LARGE_ART}-h{_LARGE_ART}", thumbs[-1]["url"])
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
