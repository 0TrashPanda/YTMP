"""YouTube Music logic as plain functions, with no HTTP.

The server wraps this in http.py; on Android, Chaquopy will call it directly.
See docs/implementation/module-api.md.
"""

from __future__ import annotations

import copy
import logging
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
import urllib.parse
from dataclasses import dataclass, field

import requests
import yt_dlp
import ytmusicapi.mixins.playlists
from ytmusicapi import LikeStatus, YTMusic
from ytmusicapi.continuations import CONTINUATION_ITEMS, get_continuation_token
from ytmusicapi.helpers import initialize_headers
from ytmusicapi.navigation import nav
from ytmusicapi.parsers.playlists import parse_playlist_items

from . import parse, pot
from .parse import MODULE_ID

_PREFIXES = ("ytm:", "yt:")
_ARTIST_SONGS = 20
# Public playlists can be huge; your own (Liked music too) come in parts (YouTube's, ~100 songs).
_PLAYLIST_SONGS = 1000
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
# Re-resolve stream URLs this long before YouTube says they expire.
_EXPIRY_MARGIN_S = 10 * 60
# A stream lookup taking longer than this gets yt-dlp's steps in the log, like a failed one.
_SLOW_RESOLVE_S = 8.0
# With PO tokens, yt-dlp also asks as YouTube's mobile web player (what yt-dlp's PO Token
# Guide recommends), with a token for its player request too. That player's links come with
# an ad and only work once it's over, so the usual player (no ads) goes first: the mobile web
# player is there for when YouTube's bot check turns the usual one away.
_PO_TOKEN_ARGS = {"player_client": ["visionos", "mweb"], "fetch_pot": ["always"], "skip": ["hls"]}
# Waiting longer than this for an ad to end is not worth it: the song fails instead.
_MAX_AD_WAIT_S = 60
# After YouTube's bot check, no lookups for this long, longer each time it's still there:
# asking again right away makes the network look even more like a bot.
_BOT_CHECK_PAUSES_S = (2 * 60, 4 * 60, 8 * 60, 16 * 60, 30 * 60)
_BOT_CHECK = "YouTube is blocking requests from this network for now (bot check)."

log = logging.getLogger("ytmp_ytm")

# New episodes on Home: at most this many.
_NEW_EPISODES = 10
# The podcast of an episode whose podcast couldn't be looked up: still an episode (no music radio after it).
_UNKNOWN_PODCAST = {"id": "", "name": "Podcast"}
# What YouTube Music calls a podcast episode in a watch playlist.
_EPISODE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
# Albums as playlists (album links: music.youtube.com/playlist?list=OLAK5uy_…).
_ALBUM_PLAYLIST = "OLAK5uy_"
# Which podcast an episode is from (video ID -> {id, name}), shared by all accounts: it never changes.
_podcast_of_episode: dict[str, dict | None] = {}
_podcast_lock = threading.Lock()


def log_to(sink) -> None:
    """Sends this module's log to [sink].log(level, logger name, text): on Android, the app's log (Chaquopy)."""

    class _Handler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            try:
                text = record.getMessage()
                if record.exc_info:
                    text += "\n" + logging.Formatter().formatException(record.exc_info)
                sink.log(record.levelno, record.name, text)
            except Exception:
                pass

    log.addHandler(_Handler())
    log.setLevel(logging.INFO)
    log.propagate = False


class NotFound(Exception):
    pass


class Unavailable(Exception):
    pass


class Blocked(Unavailable):
    """YouTube's bot check: no lookups from this network for a while. The song itself is fine."""


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
    def __init__(self, language: str = "en", location: str = "BE", js_runtime: str | None = None,
                 pot_url: str | None = None):
        """[pot_url]: a bgutil PO token server (http://host:4416), for PO tokens on a server.
        On the phone, the app hands pot.py a WebView instead."""
        self._ytm = YTMusic(language=language, location=location)
        self._ydl_opts = {
            "format": "bestaudio[ext=m4a]/bestaudio",
            "noplaylist": True,
        }
        if js_runtime:
            self._ydl_opts["js_runtimes"] = {js_runtime: {}}
        self._pot_url = pot_url
        if pot_url:
            self._ydl_opts["extractor_args"] = {"youtubepot-bgutilhttp": {"base_url": [pot_url]}}
        self._bot_check = _BotCheck()
        self._streams: dict[str, StreamInfo] = {}
        self._lock = threading.Lock()
        self._language = language
        self._location = location
        # The signed-in YouTube Music account (the phone owner's), next to the anonymous one
        # that everything shared (search, rooms) keeps using.
        self._user: YTMusic | None = None
        log.info("yt-dlp %s, ytmusicapi %s", yt_dlp.version.__version__, _version("ytmusicapi"))

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

    def signed_in_as(self, cookie: str) -> "YtmCore":
        """This core for one signed-in account (a server serves several): the same anonymous
        YouTube Music and stream cache, with [cookie]'s account for the personal pages.

        Raises NotSignedIn when the cookies aren't (or no longer) signed in.
        """
        view = copy.copy(self)
        view._user = None
        view.sign_in(cookie)
        return view

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
                {"title": "Playlists", "items": [item for p in playlists.result() if (item := parse.library_playlist(p))]},
                {"title": "Podcasts", "items": [item for p in podcasts.result() if (item := parse.library_podcast(p))]},
            ]
        return {"sections": sections}

    def search(self, query: str, type: str = "all", limit: int = 20, personal: bool = False) -> dict:
        """Search results as sections (see SearchPage in protocol/Catalog.kt).

        One type gives one section; "all" gives YTM's mixed results, grouped like YTM does.
        With [personal], as the signed-in account (results fit its taste, like in YTM).
        """
        ytm = self._signed_in() if personal else self._ytm
        if type == "all":
            # YTM's mixed results mostly lack song durations (and sometimes artists), so the
            # songs and videos come from their own searches, run at the same time.
            with ThreadPoolExecutor(3) as pool:
                mixed = pool.submit(ytm.search, query)
                songs = pool.submit(ytm.search, query, filter="songs", limit=parse.ALL_LIMITS["songs"])
                videos = pool.submit(ytm.search, query, filter="videos", limit=parse.ALL_LIMITS["videos"])
                return {"sections": parse.sections_from_all(mixed.result(), songs.result(), videos.result())}
        if type not in SEARCH_TYPES:
            raise NotFound(f"Unknown search type: {type}")
        results = ytm.search(query, filter=SEARCH_TYPES[type], limit=limit)
        # ytmusicapi treats limit as a minimum, so cut the list ourselves.
        items = [item for r in results if (item := parse.search_item(r))][:limit]
        return {"sections": [{"title": parse.SECTION_TITLES[type], "type": type, "items": items}]}

    def suggestions(self, query: str, personal: bool = False) -> list[str]:
        """What YTM suggests while typing [query], e.g. "daft p" -> "daft punk one more time".

        With [personal], as the signed-in account.
        """
        if not query.strip():
            return []
        ytm = self._signed_in() if personal else self._ytm
        return [s for s in ytm.get_search_suggestions(query) if isinstance(s, str)]

    def radio(self, seed_id: str, limit: int = 25, personal: bool = False) -> list[dict]:
        """YTM's radio for a song: similar songs, usually starting with the song itself.

        With [personal], as the signed-in account (it fits its taste, like in YTM).
        """
        ytm = self._signed_in() if personal else self._ytm
        try:
            playlist = ytm.get_watch_playlist(videoId=video_id(seed_id), radio=True, limit=limit)
        except Exception as e:  # ytmusicapi raises plain exceptions for unknown IDs
            raise NotFound(str(e)) from e
        return [song for t in playlist.get("tracks") or [] if (song := parse.song_from_watch(t))][:limit]

    def song(self, song_id: str) -> dict:
        """One song (or episode) by its ID, for a link to it: what it is, by whom, how long."""
        vid = video_id(song_id)
        try:
            tracks = self._ytm.get_watch_playlist(videoId=vid, limit=1).get("tracks") or []
        except Exception as e:  # ytmusicapi raises plain exceptions for unknown IDs
            raise NotFound(str(e)) from e
        track = next((t for t in tracks if t.get("videoId") == vid), None)
        song = parse.song_from_watch(track) if track else None
        if not song:
            raise NotFound("YouTube Music doesn't have that song")
        if track.get("videoType") == _EPISODE:
            # An episode plays as one (resumed, at the room's speed) with its podcast.
            song["podcast"] = self._podcast_of(vid) or _UNKNOWN_PODCAST
        return song

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
                songs = [s for t in playlist.get("tracks") or [] if (s := parse.song_from_search(t))]
            except Exception:
                songs = []
        if not songs:
            songs = [s for t in songs_section.get("results") or [] if (s := parse.song_from_search(t))]
        return {
            "id": artist_id,
            "name": a.get("name") or "",
            "thumbnails": parse.thumbnails(a.get("thumbnails") or []),
            "description": a.get("description"),
            "songs": songs[:_ARTIST_SONGS],
            "albums": [parse.album_summary(x, "Album") for x in (a.get("albums") or {}).get("results") or [] if x.get("browseId")],
            "singles": [parse.album_summary(x, "Single") for x in (a.get("singles") or {}).get("results") or [] if x.get("browseId")],
            "songsPlaylistId": (songs_section.get("browseId") or "").removeprefix("VL") or None,
        }

    def album(self, album_id: str) -> dict:
        """An album with its songs (they use the album's art). Also by its playlist ID (OLAK5uy_…), as album links have it."""
        try:
            if album_id.startswith(_ALBUM_PLAYLIST):
                album_id = self._ytm.get_album_browse_id(album_id) or album_id
            alb = self._ytm.get_album(album_id)
        except Exception as e:
            raise NotFound(str(e)) from e
        thumbnails = parse.thumbnails(alb.get("thumbnails") or [])
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
                "durationMs": int((t.get("duration_seconds") or 0) * 1000) or parse.parse_length(t.get("duration")),
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

    def playlist(self, playlist_id: str, personal: bool = False, more: str | None = None) -> dict:
        """A playlist with its songs: a public one, or with [personal] also the signed-in account's own (Liked music is "LM").

        Your own come in parts: the first, then [more] (the page's "more") asks for the next songs only.
        """
        ytm = self._signed_in() if personal else self._ytm
        if personal and more:
            return self._more_of_playlist(ytm, playlist_id, more)
        token = None
        try:
            if personal and _first_part_only:
                _first_part.token = None
                p = ytm.get_playlist(playlist_id, limit=0)
                token = _first_part.token
            else:
                p = ytm.get_playlist(playlist_id, limit=None if personal else _PLAYLIST_SONGS)
        except Exception as e:
            # ytmusicapi's message is about YouTube's page layout: keep it for the log only.
            log.info("playlist %s: %s", playlist_id, e)
            raise NotFound("Couldn't find this playlist (it may be private)") from e
        author = p.get("author")
        owned = bool(personal and p.get("owned"))
        count = p.get("trackCount")
        return {
            "id": playlist_id,
            "title": p.get("title") or "",
            "author": (author.get("name") if isinstance(author, dict) else author) or None,
            "description": p.get("description"),
            "thumbnails": parse.thumbnails(p.get("thumbnails") or []),
            **parse.playlist_songs(p.get("tracks") or [], places=personal),
            "owned": owned,
            "more": token,
            "songCount": count if isinstance(count, int) else None,
        }

    def _more_of_playlist(self, ytm: YTMusic, playlist_id: str, token: str) -> dict:
        """The next part of your own playlist: only its songs (and the token for the part after it)."""
        try:
            response = ytm._send_request("browse", {"continuation": token})
            items = nav(response, CONTINUATION_ITEMS, True) or []
            tracks = parse_playlist_items(items)
            following = get_continuation_token(items) if items else None
        except Exception as e:
            log.info("playlist %s, more: %s", playlist_id, e)
            raise NotFound("Couldn't load more songs of this playlist") from e
        # Only the songs count: the page adds them to the part(s) it has.
        return {
            "id": playlist_id,
            "title": "",
            "author": None,
            "description": None,
            "thumbnails": [],
            **parse.playlist_songs(tracks, places=True),
            "more": following if tracks else None,
        }

    def podcast(self, podcast_id: str, personal: bool = False) -> dict:
        """A podcast with its episodes, newest first. [personal]: as the signed-in account, so it says whether it's in its library."""
        ytm = self._signed_in() if personal else self._ytm
        try:
            p = ytm.get_podcast(podcast_id)
        except Exception as e:
            raise NotFound(str(e)) from e
        ref = {"id": podcast_id, "name": p.get("title") or ""}
        author = p.get("author")
        return {
            "id": podcast_id,
            "title": ref["name"],
            "author": (author.get("name") if isinstance(author, dict) else author) or None,
            "description": p.get("description"),
            "thumbnails": parse.thumbnails(p.get("thumbnails") or []),
            "episodes": [
                {"song": song, "date": parse.episode_date(e.get("date")), "description": e.get("description")}
                for e in p.get("episodes") or []
                if (song := parse.episode_song(e, ref))
            ],
            "saved": bool(p.get("saved")) if personal else None,
        }

    def set_podcast_saved(self, podcast_id: str, saved: bool) -> None:
        """Subscribes to a podcast (adds it to the account's library), or unsubscribes."""
        playlist_id = podcast_id.removeprefix("MPSP")
        self._signed_in().rate_playlist(playlist_id, LikeStatus.LIKE if saved else LikeStatus.INDIFFERENT)

    def new_episodes(self, limit: int = _NEW_EPISODES) -> list[dict]:
        """The newest episodes of the podcasts in the account's library (YouTube Music's New episodes), as songs."""
        episodes = (self._signed_in().get_episodes_playlist("RDPN").get("episodes") or [])[:limit]
        # That list doesn't say which podcast an episode is from; the episode's own page does.
        with ThreadPoolExecutor(4) as pool:
            podcasts = list(pool.map(self._podcast_of, [e.get("videoId") for e in episodes]))
        return [song for e, podcast in zip(episodes, podcasts) if (song := parse.episode_song(e, podcast or _UNKNOWN_PODCAST))]

    def _podcast_of(self, video_id: str | None) -> dict | None:
        """The podcast an episode is from ({id, name}), looked up once."""
        if not video_id:
            return None
        with _podcast_lock:
            if video_id in _podcast_of_episode:
                return _podcast_of_episode[video_id]
        try:
            author = self._ytm.get_episode(video_id).get("author") or {}
        except Exception as e:
            log.info("No podcast for episode %s: %s", video_id, e)
            return None
        podcast = {"id": author["id"], "name": author.get("name") or ""} if author.get("id") else None
        with _podcast_lock:
            if len(_podcast_of_episode) > 2000:
                _podcast_of_episode.clear()
            _podcast_of_episode[video_id] = podcast
        return podcast

    def lyrics(self, song_id: str) -> dict:
        """The song's lyrics in YouTube Music: {plain, synced: [{timeMs, text}] or None, source}.

        Synced (each line with its start) when YouTube Music has them timed. NotFound when it
        has none.
        """
        vid = video_id(song_id)
        try:
            browse_id = self._ytm.get_watch_playlist(videoId=vid, limit=1).get("lyrics")
            found = self._ytm.get_lyrics(browse_id, timestamps=True) if browse_id else None
        except Exception as e:
            log.info("lyrics %s: %s", vid, e)
            raise NotFound("No lyrics for this song") from e
        if not found or not found.get("lyrics"):
            raise NotFound("No lyrics for this song")
        # "Source: LyricFind": who YouTube Music has them from.
        provider = str(found.get("source") or "").removeprefix("Source:").strip()
        source = f"YouTube Music ({provider})" if provider else "YouTube Music"
        if found.get("hasTimestamps"):
            synced = [{"timeMs": int(line.start_time), "text": line.text} for line in found["lyrics"]]
            return {"plain": "\n".join(line["text"] for line in synced), "synced": synced, "source": source}
        return {"plain": str(found["lyrics"]), "synced": None, "source": source}

    def rating(self, song_id: str) -> str:
        """The signed-in account's thumbs on this song: "like", "dislike" or "none"."""
        vid = video_id(song_id)
        tracks = self._signed_in().get_watch_playlist(videoId=vid, limit=1).get("tracks") or []
        status = next((t.get("likeStatus") for t in tracks[:1] if t.get("videoId") == vid), None)
        return {"LIKE": "like", "DISLIKE": "dislike"}.get(status or "", "none")

    def set_rating(self, song_id: str, rating: str) -> None:
        status = {"like": LikeStatus.LIKE, "dislike": LikeStatus.DISLIKE}.get(rating, LikeStatus.INDIFFERENT)
        self._signed_in().rate_song(video_id(song_id), status)

    def liked(self, song_id: str) -> bool:
        """Whether the signed-in account likes this song (thumbs up)."""
        return self.rating(song_id) == "like"

    def set_liked(self, song_id: str, liked: bool) -> None:
        self.set_rating(song_id, "like" if liked else "none")

    # Your own playlists: items are songs with their place in the playlist ({songId, itemId}).

    def remove_from_playlist(self, playlist_id: str, items: list[dict]) -> None:
        videos = [{"videoId": video_id(str(i["songId"])), "setVideoId": str(i["itemId"])} for i in items if i.get("itemId")]
        if not videos:
            return
        if self._signed_in().remove_playlist_items(playlist_id, videos) != "STATUS_SUCCEEDED":
            raise Unavailable("Couldn't remove it from the playlist")

    def move_in_playlist(self, playlist_id: str, item_id: str, before_item_id: str | None) -> None:
        move = (item_id, before_item_id) if before_item_id else item_id
        if self._signed_in().edit_playlist(playlist_id, moveItem=move) != "STATUS_SUCCEEDED":
            raise Unavailable("Couldn't move it")

    def rename_playlist(self, playlist_id: str, title: str) -> None:
        title = title.replace("<", "").replace(">", "").strip()
        if not title:
            raise Unavailable("A playlist needs a name")
        if self._signed_in().edit_playlist(playlist_id, title=title) != "STATUS_SUCCEEDED":
            raise Unavailable("Couldn't rename the playlist")

    def delete_playlist(self, playlist_id: str) -> None:
        if self._signed_in().delete_playlist(playlist_id) != "STATUS_SUCCEEDED":
            raise Unavailable("Couldn't delete the playlist")

    def add_to_history(self, song_id: str) -> None:
        """Tells YouTube Music the signed-in account played this song (its history, and so its suggestions)."""
        user = self._signed_in()
        status = user.add_history_item(user.get_song(video_id(song_id))).status_code
        if status != 204:
            raise Unavailable(f"YouTube Music didn't take the play ({status})")

    def history(self) -> list[dict]:
        """The signed-in account's YouTube Music history, newest first: {song, daysAgo}.

        YouTube Music lists about the last 200 songs, in groups by day ("Today", "Yesterday",
        "Monday", "Last week", "March 2026"); daysAgo is about how long ago that was (None: unknown).
        """
        try:
            tracks = self._signed_in().get_history()
        except NotSignedIn:
            raise
        except Exception as e:
            log.info("history: %s", e)
            raise Unavailable("Couldn't load your YouTube Music history") from e
        today = time.localtime()
        out = []
        for t in tracks:
            song = parse.song_from_search(t)
            if song:
                out.append({"song": song, "daysAgo": parse.days_ago(t.get("played"), today)})
        return out

    def own_playlists(self) -> list[dict]:
        """The signed-in account's own playlists (the ones songs can be saved to): {id, title, thumbnails}."""
        return [
            {"id": p["playlistId"], "title": p.get("title") or "", "thumbnails": parse.thumbnails(p.get("thumbnails") or [])}
            for p in self._signed_in().get_library_playlists(limit=_LIBRARY_ITEMS)
            if p.get("owned") and p.get("playlistId") not in ("LM", "SE")
        ]

    def add_to_playlist(self, playlist_id: str, song_ids: list[str]) -> None:
        result = self._signed_in().add_playlist_items(playlist_id, [video_id(s) for s in song_ids])
        if not isinstance(result, dict) or result.get("status") != "STATUS_SUCCEEDED":
            # YouTube asks to confirm duplicates instead of adding them.
            raise Unavailable("Already in this playlist" if "duplicate" in str(result).lower() else "Couldn't add it to the playlist")

    def create_playlist(self, title: str, song_ids: list[str]) -> str:
        """A new private playlist with these songs; returns its ID."""
        result = self._signed_in().create_playlist(title.replace("<", "").replace(">", ""), "", video_ids=[video_id(s) for s in song_ids])
        if not isinstance(result, str):
            raise Unavailable("Couldn't make the playlist")
        return result

    def home(self, personal: bool = False) -> dict:
        """YTM's home page: quick picks, new releases, mixes and playlists.

        [personal]: the signed-in account's own home, instead of the one without an account.
        """
        ytm = self._signed_in() if personal else self._ytm
        sections = []
        if personal:
            # New episodes of your podcasts first, like YouTube Music shows them to podcast listeners.
            try:
                episodes = self.new_episodes()
                if episodes:
                    sections.append({"title": "New episodes", "items": [{"kind": "song", "song": s, "video": False} for s in episodes]})
            except Exception as e:
                log.info("No new episodes on Home: %s", e)
        for s in ytm.get_home(limit=_HOME_SECTIONS):
            items = [item for c in s.get("contents") or [] if (item := parse.home_item(c))]
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

        self._bot_check.check()
        steps = _YdlLog(vid)
        opts = dict(self._ydl_opts, logger=steps)
        if self._pot_url or pot.available():
            opts["extractor_args"] = dict(opts.get("extractor_args") or {}, youtube=_PO_TOKEN_ARGS)
        started = time.monotonic()
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(f"https://music.youtube.com/watch?v={vid}", download=False)
        except yt_dlp.utils.DownloadError as e:
            log.warning("stream %s: yt-dlp failed after %.1f s: %s\n%s", vid, time.monotonic() - started, e, steps.text())
            if "confirm you" in str(e) and "not a bot" in str(e):
                pause = self._bot_check.seen()
                log.warning("stream %s: YouTube's bot check: no lookups for %d min", vid, pause // 60)
                raise Blocked(f"{_BOT_CHECK} Try again in {pause // 60} min.") from e
            raise Unavailable(_clean_error(str(e))) from e
        except Exception:
            log.exception("stream %s: yt-dlp crashed after %.1f s\n%s", vid, time.monotonic() - started, steps.text())
            raise

        url = info["url"]
        query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        # A link that comes with an ad works once the ad would be over.
        if (wait := (info.get("available_at") or 0) - time.time()) > 0:
            if wait > _MAX_AD_WAIT_S:
                raise Unavailable(f"YouTube wants an ad of {round(wait)} s first")
            log.info("stream %s: waiting %.0f s for YouTube's link (it comes with an ad)", vid, wait)
            time.sleep(wait)
        expires = query.get("expire", [None])[0]
        stream = StreamInfo(
            url=url,
            expires_at=float(expires) if expires else time.time() + 3600,
            mime_type="audio/mp4" if info.get("ext") == "m4a" else f"audio/{info.get('ext', 'webm')}",
            bitrate=info.get("abr"),
            duration_ms=int(info["duration"] * 1000) if info.get("duration") else None,
            http_headers=dict(info.get("http_headers") or {}),
        )
        self._bot_check.passed()
        took = time.monotonic() - started
        log.info(
            "stream %s: %s in %.1f s, format %s (%s, %s kbps, %s), host %s, expires in %d min%s",
            vid, "re-resolved" if fresh else "resolved", took, info.get("format_id"), info.get("ext"),
            round(info["abr"]) if info.get("abr") else "?", query.get("c", ["?"])[0].lower(),
            urllib.parse.urlparse(url).hostname,
            (stream.expires_at - time.time()) // 60, f"\n{steps.text()}" if took > _SLOW_RESOLVE_S else "",
        )
        with self._lock:
            self._streams[vid] = stream
        return stream

    def forget_stream(self, song_id: str) -> None:
        """Drops a cached stream URL that stopped working, so the next lookup resolves it again."""
        with self._lock:
            self._streams.pop(video_id(song_id), None)


_REPEAT_WARNING_S = 3600
_warned: dict[str, float] = {}
_warned_lock = threading.Lock()


class _YdlLog:
    """yt-dlp's logger for one lookup: warnings go to the log right away; its steps are kept,
    and only logged when the lookup fails or is slow (they'd fill the log otherwise)."""

    _MAX_STEPS = 60

    def __init__(self, vid: str):
        self._vid = vid
        self._steps: list[str] = []
        self._started = time.monotonic()

    def _keep(self, message: str) -> None:
        if len(self._steps) < self._MAX_STEPS:
            self._steps.append(f"  +{time.monotonic() - self._started:5.1f}s {message}")

    def debug(self, message: str) -> None:
        self._keep(message)

    def info(self, message: str) -> None:
        self._keep(message)

    def warning(self, message: str) -> None:
        self._keep(f"WARNING: {message}")
        # The same warning comes with every lookup (like the missing JavaScript runtime): once an hour is enough.
        key = message.replace(self._vid, "<id>")
        now = time.monotonic()
        with _warned_lock:
            if now - _warned.get(key, -_REPEAT_WARNING_S) < _REPEAT_WARNING_S:
                return
            _warned[key] = now
        log.warning("stream %s: yt-dlp: %s", self._vid, message)

    def error(self, message: str) -> None:
        # The DownloadError that follows is logged with all steps.
        self._keep(message)

    def text(self) -> str:
        return "\n".join(self._steps) or "  (yt-dlp said nothing)"


def _version(package: str) -> str:
    try:
        from importlib.metadata import version
        return version(package)
    except Exception:
        return "?"


# Your own playlists in parts: ytmusicapi loads the parts after the first itself (get_playlist's
# limit), and doesn't say how to ask for the next one. With limit 0 it stops after the first
# part, and this keeps that part's token (per thread). If a new ytmusicapi works differently,
# they're loaded whole, as before.
_first_part = threading.local()
_load_parts = getattr(ytmusicapi.mixins.playlists, "get_continuations_2025", None)


def _first_part_then_token(results, limit, request_func, parse_func):
    if limit == 0:
        _first_part.token = get_continuation_token(results.get("contents") or [])
        return []
    return _load_parts(results, limit, request_func, parse_func)


_first_part_only = _load_parts is not None
if _first_part_only:
    ytmusicapi.mixins.playlists.get_continuations_2025 = _first_part_then_token


def _account(user: YTMusic) -> dict:
    try:
        info = user.get_account_info()
    except requests.RequestException:
        raise  # offline: that says nothing about the sign-in
    except Exception as e:  # expired or signed out: YouTube answers without an account
        raise NotSignedIn(f"Not signed in to YouTube Music ({e})") from e
    return {"name": info.get("accountName") or "", "handle": info.get("channelHandle"), "photoUrl": info.get("accountPhotoUrl")}


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


class _BotCheck:
    """When YouTube's bot check was last seen; shared by a core's signed-in views."""

    def __init__(self):
        self._lock = threading.Lock()
        self._until = 0.0
        self._times = 0

    def check(self) -> None:
        """Raises Blocked while lookups are paused."""
        with self._lock:
            left = self._until - time.time()
        if left > 0:
            raise Blocked(f"{_BOT_CHECK} Try again in {max(1, round(left / 60))} min.")

    def seen(self) -> int:
        """Pauses lookups, and returns for how many seconds."""
        with self._lock:
            pause = _BOT_CHECK_PAUSES_S[min(self._times, len(_BOT_CHECK_PAUSES_S) - 1)]
            self._times += 1
            self._until = time.time() + pause
        return pause

    def passed(self) -> None:
        with self._lock:
            self._times = 0


def _clean_error(message: str) -> str:
    return re.sub(r"^ERROR: (\[[^\]]+\] )?([\w-]+: )?", "", message).strip()
