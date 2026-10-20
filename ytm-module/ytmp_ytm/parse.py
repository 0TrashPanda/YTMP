"""Turning what YouTube Music answers (ytmusicapi's results) into YTMP's songs, pages and cards.

Plain functions without YouTube or HTTP, used by core.py. See docs/implementation/module-api.md.
"""

from __future__ import annotations

import re

MODULE_ID = "ytm"

_LARGE_ART = 544


SECTION_TITLES = {
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
ALL_LIMITS = {
    "songs": 4, "videos": 4, "albums": 6, "artists": 6, "community_playlists": 6, "featured_playlists": 6,
    "podcasts": 6, "episodes": 4,
}


# YouTube Music's own playlists are "featured"; everyone else's are "community".
_YTM_AUTHOR = "YouTube Music"


def song_from_search(r: dict) -> dict | None:
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
        "thumbnails": thumbnails(r.get("thumbnails") or []),
        "explicit": bool(r.get("isExplicit")),
    }


def search_item(r: dict) -> dict | None:
    """One search result as a SearchItem (protocol/Catalog.kt), or None for kinds we don't show."""
    kind = r.get("resultType")
    if kind in ("song", "video"):
        song = song_from_search(r)
        return {"kind": "song", "song": song, "video": kind == "video"} if song else None
    if kind == "episode":
        podcast = r.get("podcast") or {}
        ref = {"id": podcast["id"], "name": podcast.get("name", "")} if podcast.get("id") else None
        song = episode_song(r, ref)
        return {"kind": "song", "song": song, "video": False} if song else None
    if kind == "podcast" and r.get("browseId"):
        return {
            "kind": "podcast",
            "id": r["browseId"],
            "title": r.get("title") or "",
            "author": r.get("author") or (r.get("artists") or [{}])[0].get("name"),
            "thumbnails": thumbnails(r.get("thumbnails") or []),
        }
    if kind == "album" and r.get("browseId"):
        return {
            "kind": "album",
            "album": album_summary(r, "Album"),
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
            "thumbnails": thumbnails(r.get("thumbnails") or []),
        }
    if kind == "playlist" and r.get("browseId"):
        count = r.get("itemCount")
        return {
            "kind": "playlist",
            "id": r["browseId"],
            "title": r.get("title") or "",
            "author": r.get("author"),
            "itemCount": int(count) if isinstance(count, (int, str)) and str(count).isdigit() else None,
            "thumbnails": thumbnails(r.get("thumbnails") or []),
        }
    return None  # profiles, …


def home_item(c: dict) -> dict | None:
    """One item of a home section as a SearchItem: songs and videos lack a length (the stream has it)."""
    if c.get("videoId"):
        song = song_from_search(c)
        return {"kind": "song", "song": song, "video": c.get("videoType") != "MUSIC_VIDEO_TYPE_ATV"} if song else None
    browse_id = c.get("browseId") or ""
    if browse_id.startswith("MPRE"):
        return {
            "kind": "album",
            "album": album_summary(c, c.get("type") or "Album"),
            "artists": [{"id": a.get("id"), "name": a.get("name", "")} for a in c.get("artists") or []],
        }
    if browse_id.startswith("MPSP"):
        return {"kind": "podcast", "id": browse_id, "title": c.get("title") or "", "author": None, "thumbnails": thumbnails(c.get("thumbnails") or [])}
    if browse_id.startswith("UC"):
        return {"kind": "artist", "id": browse_id, "name": c.get("title") or "", "thumbnails": thumbnails(c.get("thumbnails") or [])}
    if c.get("playlistId"):
        return {
            "kind": "playlist",
            "id": c["playlistId"],
            "title": c.get("title") or "",
            # e.g. "Alicia Keys, Ed Sheeran, Lewis Capaldi"
            "author": c.get("description"),
            "itemCount": None,
            "thumbnails": thumbnails(c.get("thumbnails") or []),
        }
    return None


def playlist_songs(tracks: list[dict], places: bool) -> dict:
    """A playlist's songs, and with [places] each one's place in it (your own: for removing and moving it)."""
    pairs = [(t, s) for t in tracks if (s := song_from_search(t))]
    return {"songs": [s for _, s in pairs], "itemIds": [t.get("setVideoId") or "" for t, _ in pairs] if places else []}


def library_playlist(p: dict) -> dict | None:
    if not p.get("playlistId"):
        return None
    count = p.get("count")
    return {
        "kind": "playlist",
        "id": p["playlistId"],
        "title": p.get("title") or "",
        "author": p.get("description") or None,
        "itemCount": int(count.replace(",", "")) if isinstance(count, str) and count.replace(",", "").isdigit() else None,
        "thumbnails": thumbnails(p.get("thumbnails") or []),
    }


def library_podcast(p: dict) -> dict | None:
    """A podcast in the library; "New episodes" (an automatic playlist) is a playlist."""
    browse_id = p.get("browseId") or ""
    channel = (p.get("channel") or {}).get("name")
    if browse_id.startswith("MPSP"):
        return {"kind": "podcast", "id": browse_id, "title": p.get("title") or "", "author": channel, "thumbnails": thumbnails(p.get("thumbnails") or [])}
    if p.get("podcastId"):
        return {"kind": "playlist", "id": p["podcastId"], "title": p.get("title") or "", "author": channel, "itemCount": None, "thumbnails": thumbnails(p.get("thumbnails") or [])}
    return None


def search_type(r: dict) -> str | None:
    """Which search type (chip) a result of the "all" search belongs to."""
    kind = r.get("resultType")
    if kind == "playlist":
        return "featured_playlists" if r.get("author") == _YTM_AUTHOR else "community_playlists"
    return {
        "song": "songs", "video": "videos", "album": "albums", "artist": "artists", "podcast": "podcasts", "episode": "episodes",
    }.get(kind)


def sections_from_all(results: list[dict], songs: list[dict], videos: list[dict]) -> list[dict]:
    """YTM's unfiltered search: the top result, then a few of each kind, in YTM's order.

    [songs] and [videos] (filtered searches) replace the mixed results' songs and videos,
    which lack durations.
    """
    complete = {r["videoId"]: r for r in songs + videos if r.get("videoId")}
    sections: list[dict] = []
    for r in results:
        if r.get("category") == "Top result":
            r = complete.get(r.get("videoId"), r) if r.get("videoId") else r
            if item := search_item(r):
                sections.append({"title": "Top result", "type": None, "items": [item]})
            break
    by_type: dict[str, list[dict]] = {}
    for r in results:
        if r.get("category") != "Top result" and (t := search_type(r)) and (item := search_item(r)):
            by_type.setdefault(t, []).append(item)
    for t, filtered in (("songs", songs), ("videos", videos)):
        items = [item for r in filtered if (item := search_item(r))]
        if items:
            by_type[t] = items  # keeps its place if the mixed results had it, else goes last
        else:
            by_type.pop(t, None)
    for t, items in by_type.items():  # dicts keep insertion order: the order YTM gave
        sections.append({"title": SECTION_TITLES[t], "type": t, "items": items[:ALL_LIMITS[t]]})
    return sections


def episode_song(e: dict, podcast: dict | None) -> dict | None:
    """A podcast episode as a Song with "podcast" set. Its length may be rounded; the stream has the exact one."""
    vid = e.get("videoId")
    if not vid:
        return None
    return {
        "id": f"{MODULE_ID}:{vid}",
        "title": e.get("title") or "",
        "artists": [],
        "album": None,
        "durationMs": int((e.get("duration_seconds") or 0) * 1000) or parse_spoken_length(e.get("duration")),
        "thumbnails": thumbnails(e.get("thumbnails") or []),
        "explicit": False,
        "podcast": podcast,
    }


def episode_date(value: str | None) -> str | None:
    """ytmusicapi sometimes puts the view count where the date goes; that's not a date."""
    return None if not value or "view" in value else value


def parse_spoken_length(length: str | None) -> int:
    """'3 hr 36 min', '29 min', '45 sec' -> milliseconds; '4:21' too; 0 if unknown."""
    if not length:
        return 0
    if ":" in length:
        return parse_length(length)
    units = {"hr": 3600, "hour": 3600, "min": 60, "sec": 1}
    seconds = 0
    for amount, unit in re.findall(r"(\d+)\s*(hr|hour|min|sec)", length):
        seconds += int(amount) * units[unit]
    return seconds * 1000


def song_from_watch(t: dict) -> dict | None:
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
        "durationMs": parse_length(t.get("length")),
        "thumbnails": thumbnails(t.get("thumbnail") or t.get("thumbnails") or []),
        "explicit": bool(t.get("isExplicit")),
    }


def album_summary(x: dict, kind: str) -> dict:
    return {
        "id": x["browseId"],
        "title": x.get("title") or "",
        "kind": x.get("type") or kind,
        "year": x.get("year"),
        "thumbnails": thumbnails(x.get("thumbnails") or []),
    }


def parse_length(length: str | None) -> int:
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


def thumbnails(thumbs: list[dict]) -> list[dict]:
    out = [{"url": t["url"], "width": t.get("width", 0), "height": t.get("height", 0)} for t in thumbs]
    # YTM only lists small sizes; the same image is available larger by changing the URL.
    # Artist pictures are cropped ("=w120-c-h120-…"), album art isn't ("=w120-h120-…").
    size = r"=w\d+-(c-)?h\d+"
    if thumbs and re.search(size, thumbs[-1]["url"]):
        large = re.sub(size, lambda m: f"=w{_LARGE_ART}-{m.group(1) or ''}h{_LARGE_ART}", thumbs[-1]["url"])
        out.append({"url": large, "width": _LARGE_ART, "height": _LARGE_ART})
    return out
