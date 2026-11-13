"""This computer's own music and song cache, for the desktop app
(docs/implementation/desktop-app.md#music-and-songs-on-this-computer).

Only with YTMP_YTM_DESKTOP_DIR (the app's data folder): the module there runs on the listener's
computer, so it can read their music folders and keep songs on their disk. The app's window
plays both from here (the app passes `ytmpfiles://` requests on to [Desktop.file]); nothing
leaves the computer unless the server asks for a song another player needs ([Desktop.upload]).

- Music on this computer: `local:dev-<device>:s:<key>` songs from the folders picked in the
  settings, with albums (`…:a:…`) and artists (`…:r:…`) of their own.
- Song cache: YouTube Music songs this computer plays, kept as they're played (one download,
  read by the player as it comes in), so the next time they start at once, without a lookup.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

import httpx

from . import core

log = logging.getLogger("ytmp_ytm.desktop")

AUDIO_EXTENSIONS = {".mp3", ".m4a", ".mp4", ".aac", ".flac", ".ogg", ".oga", ".opus", ".wav", ".wma", ".webm"}
_TYPES = {
    ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".mp4": "audio/mp4", ".aac": "audio/aac", ".flac": "audio/flac",
    ".ogg": "audio/ogg", ".oga": "audio/ogg", ".opus": "audio/ogg", ".wav": "audio/wav", ".wma": "audio/x-ms-wma",
    ".webm": "audio/webm",
}
_COVER_NAMES = ("cover", "folder", "front", "album", "albumart")
# What one answer to the window holds at most: the player asks for the rest (the app keeps
# each answer in memory, so a whole film-length file isn't read at once).
PIECE = 2 * 1024 * 1024
# The folders are looked at again after this long, when the library is asked for.
_RESCAN_S = 10 * 60
# Songs longer than this (mixes, whole concerts) aren't kept, like on the phone.
_MAX_CACHED_MS = 60 * 60 * 1000
# A song played again this many days later counts for a week more in the keep score.
_KEEP_BONUS_S = 7 * 86400
_PLAYS_WINDOW_S = 90 * 86400


def _hash(text: str, n: int = 16) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:n]


@dataclass
class Track:
    path: str
    key: str
    title: str
    artist: str
    album_artist: str
    album: str
    disc: int | None
    track: int | None
    year: str | None
    genre: str | None
    duration_ms: int
    folder: str
    mtime: float
    size: int
    art: bool = False

    def to_json(self) -> dict:
        return self.__dict__.copy()


@dataclass
class _Fill:
    """A cached song coming in: one download, read by the player as it arrives."""

    song_id: str
    part: Path
    # Where it's read from: the part file, then the kept file.
    file: Path | None = None
    have: int = 0
    total: int | None = None
    mime: str = "audio/mp4"
    done: bool = False
    failed: str | None = None
    info: core.StreamInfo | None = None
    changed: threading.Condition = field(default_factory=threading.Condition)


class Desktop:
    def __init__(self, ytm: core.YtmCore, folder: Path, device: str, files_base: str):
        self._ytm = ytm
        self._dir = folder
        self._dir.mkdir(parents=True, exist_ok=True)
        self.device = device
        self.library_id = f"dev-{device}"
        self._files_base = files_base
        self._lock = threading.RLock()
        self._settings = self._read(self._dir / "desktop.json", {})
        self._music_index: dict[str, dict] = self._read(self._dir / "music-index.json", {})
        self._tracks: dict[str, Track] = {}
        self._scanned_at = 0.0
        self._scanning = False
        self._scan_thread: threading.Thread | None = None
        self._cache_dir = self._dir / "song-cache"
        self._cache_dir.mkdir(exist_ok=True)
        self._cache: dict[str, dict] = self._read(self._cache_dir / "index.json", {})
        self._fills: dict[str, _Fill] = {}
        self._http = httpx.Client(timeout=httpx.Timeout(15.0, read=30.0), follow_redirects=True)
        self._load_tracks()
        if self.music_on:
            self.rescan()

    # --- settings ---------------------------------------------------------------------

    @property
    def cache_bytes(self) -> int:
        return max(0, int(self._settings.get("cacheMb", 0))) * 1024 * 1024

    @property
    def music_on(self) -> bool:
        return bool(self._settings.get("music")) and bool(self.folders)

    @property
    def folders(self) -> list[str]:
        return [f for f in self._settings.get("folders", []) if isinstance(f, str)]

    def status(self) -> dict:
        with self._lock:
            sizes = [e.get("size", 0) for e in self._cache.values()]
            return {
                "device": self.device,
                "libraryId": self.library_id,
                "cacheMb": self.cache_bytes // (1024 * 1024),
                "cacheSongs": len(sizes),
                "cacheBytes": sum(sizes),
                "music": bool(self._settings.get("music")),
                "folders": [{"path": f, "name": Path(f).name or f} for f in self.folders],
                "musicSongs": len(self._tracks) if self.music_on else 0,
                "scanning": self._scanning,
            }

    def set_settings(self, changes: dict) -> dict:
        with self._lock:
            if "cacheMb" in changes:
                self._settings["cacheMb"] = max(0, min(int(changes["cacheMb"]), 1024 * 1024))
            if "music" in changes:
                self._settings["music"] = bool(changes["music"])
            if "folders" in changes:
                folders = [str(Path(f)) for f in changes["folders"] if isinstance(f, str) and f.strip()]
                self._settings["folders"] = list(dict.fromkeys(folders))
            self._write(self._dir / "desktop.json", self._settings)
        self.trim()
        if self.music_on:
            self.rescan()
        return self.status()

    # --- music on this computer ----------------------------------------------------------

    def _load_tracks(self) -> None:
        tracks = {}
        for path, entry in self._music_index.items():
            try:
                t = Track(**entry)
            except TypeError:
                continue
            if any(_inside(path, f) for f in self.folders):
                tracks[t.key] = t
        self._tracks = tracks

    def rescan(self, wait: bool = False) -> None:
        """Looks through the folders again (in the background): new files, changed tags, removed files."""
        with self._lock:
            thread = self._scan_thread
            if not self._scanning:
                self._scanning = True
                thread = self._scan_thread = threading.Thread(target=self._scan, name="music-scan", daemon=True)
                thread.start()
        if wait and thread:
            thread.join()

    def _scan(self) -> None:
        started = time.monotonic()
        try:
            index: dict[str, dict] = {}
            read = 0
            for root in self.folders:
                base = Path(root)
                for dirpath, dirnames, filenames in os.walk(base):
                    dirnames[:] = [d for d in dirnames if not d.startswith(".")]
                    for name in filenames:
                        path = Path(dirpath) / name
                        if path.suffix.lower() not in AUDIO_EXTENSIONS:
                            continue
                        try:
                            stat = path.stat()
                        except OSError:
                            continue
                        key = str(path)
                        old = self._music_index.get(key)
                        if old and old.get("mtime") == stat.st_mtime and old.get("size") == stat.st_size:
                            index[key] = old
                            continue
                        track = _read_tags(path, base, stat)
                        read += 1
                        if track:
                            index[key] = track.to_json()
            with self._lock:
                self._music_index = index
                self._scanned_at = time.time()
                self._load_tracks()
                self._write(self._dir / "music-index.json", index)
            log.info("music on this computer: %d songs in %d folders (%d read) in %.1f s",
                     len(self._tracks), len(self.folders), read, time.monotonic() - started)
        except Exception:
            log.exception("music on this computer: scan failed")
        finally:
            with self._lock:
                self._scanning = False

    def _song_id(self, key: str) -> str:
        return f"local:{self.library_id}:s:{key}"

    def _album_id(self, t: Track) -> str:
        return f"local:{self.library_id}:a:{_hash(t.folder + '/' + t.album.lower())}"

    def _artist_id(self, name: str) -> str:
        return f"local:{self.library_id}:r:{_hash(name.lower())}"

    def _art(self, t: Track) -> list[dict]:
        if not t.art:
            return []
        return [{"url": f"{self._files_base}art/{self._song_id(t.key)}", "width": 544, "height": 544}]

    def _song(self, t: Track) -> dict:
        return {
            "id": self._song_id(t.key),
            "title": t.title,
            "artists": [{"id": self._artist_id(t.artist), "name": t.artist}],
            "album": {"id": self._album_id(t), "name": t.album} if t.album else None,
            "durationMs": t.duration_ms,
            "thumbnails": self._art(t),
            "explicit": False,
            "podcast": None,
        }

    def _ordered(self) -> list[Track]:
        if self.music_on and time.time() - self._scanned_at > _RESCAN_S:
            self.rescan()
        with self._lock:
            tracks = list(self._tracks.values()) if self.music_on else []
        # Copies (the same artist, album, disc, track and title, about as long): the shortest path.
        seen: dict[tuple, Track] = {}
        for t in sorted(tracks, key=lambda t: len(t.path)):
            same = (t.album_artist.lower(), t.album.lower(), t.disc, t.track, t.title.lower(), round(t.duration_ms / 2000))
            seen.setdefault(same, t)
        return sorted(seen.values(), key=lambda t: (t.album_artist.lower(), t.album.lower(), t.disc or 0, t.track or 0, t.title.lower()))

    def library(self) -> dict:
        """The Local tab's page (LocalLibraryPage): albums, artists, songs, genres and folders."""
        ordered = self._ordered()
        songs = sorted((self._song(t) for t in ordered), key=lambda s: s["title"].lower())
        place = {s["id"]: i for i, s in enumerate(songs)}
        albums, artists = self._albums(ordered), self._artists(ordered)
        genres: dict[str, list[Track]] = {}
        names: dict[str, dict[str, int]] = {}
        folders: dict[str, list[int]] = {}
        for t in ordered:
            if t.genre:
                genres.setdefault(t.genre.lower(), []).append(t)
                spelled = names.setdefault(t.genre.lower(), {})
                spelled[t.genre] = spelled.get(t.genre, 0) + 1
            folders.setdefault(t.folder, []).append(place[self._song_id(t.key)])
        return {
            "id": self.library_id,
            "name": "On this computer",
            "kind": "computer",
            "albums": [self._album_item(a) for a in albums],
            "artists": [{"kind": "artist", "id": self._artist_id(name), "name": name, "thumbnails": self._art(ts[0])}
                        for name, ts in artists],
            "songs": songs,
            "genres": [{"name": max(names[g], key=names[g].get), "songs": [place[self._song_id(t.key)] for t in ts]}
                       for g, ts in sorted(genres.items())],
            "folders": [{"name": f, "songs": s} for f, s in sorted(folders.items())],
            "playlists": [],
        }

    def summary(self) -> dict:
        return {"id": self.library_id, "name": "On this computer", "kind": "computer", "songCount": len(self._ordered())}

    def _albums(self, ordered: list[Track]) -> list[list[Track]]:
        groups: dict[tuple, list[Track]] = {}
        for t in ordered:
            if t.album:
                groups.setdefault((t.folder, t.album.lower()), []).append(t)
        return sorted(groups.values(), key=lambda ts: (ts[0].album.lower(), _album_artist(ts).lower()))

    def _artists(self, ordered: list[Track]) -> list[tuple[str, list[Track]]]:
        groups: dict[str, list[Track]] = {}
        names: dict[str, str] = {}
        for t in ordered:
            groups.setdefault(t.artist.lower(), []).append(t)
            names.setdefault(t.artist.lower(), t.artist)
        return sorted(((names[k], ts) for k, ts in groups.items()), key=lambda p: p[0].lower())

    def _album_summary(self, ts: list[Track]) -> dict:
        t = ts[0]
        return {"id": self._album_id(t), "title": t.album, "kind": "Album", "year": t.year,
                "thumbnails": self._art(next((x for x in ts if x.art), t))}

    def _album_item(self, ts: list[Track]) -> dict:
        artist = _album_artist(ts)
        return {"kind": "album", "album": self._album_summary(ts), "artists": [{"id": self._artist_id(artist), "name": artist}]}

    def album(self, album_id: str) -> dict:
        for ts in self._albums(self._ordered()):
            if self._album_id(ts[0]) == album_id:
                summary = self._album_summary(ts)
                artist = _album_artist(ts)
                return {**summary, "artists": [{"id": self._artist_id(artist), "name": artist}],
                        "songs": [self._song(t) for t in ts]}
        raise core.NotFound("That album isn't on this computer")

    def artist(self, artist_id: str) -> dict:
        ordered = self._ordered()
        for name, ts in self._artists(ordered):
            if self._artist_id(name) == artist_id:
                albums = [a for a in self._albums(ordered) if any(t.artist.lower() == name.lower() for t in a)]
                return {"id": artist_id, "name": name, "thumbnails": self._art(ts[0]), "description": None,
                        "songs": [self._song(t) for t in ts], "albums": [self._album_summary(a) for a in albums],
                        "singles": [], "songsPlaylistId": None}
        raise core.NotFound("That artist isn't on this computer")

    def track(self, song_id: str) -> Track | None:
        prefix = f"local:{self.library_id}:s:"
        if not song_id.startswith(prefix):
            return None
        with self._lock:
            return self._tracks.get(song_id[len(prefix):]) if self.music_on else None

    def art(self, song_id: str) -> tuple[bytes, str] | None:
        t = self.track(song_id)
        return _cover(Path(t.path)) if t else None

    # --- answering the window -------------------------------------------------------------

    def file(self, song_id: str, range_header: str | None) -> tuple[int, dict, bytes]:
        """A piece of a song: a file of this computer's music, or a song of the cache (filling it as it plays)."""
        start, end = core.parse_range(range_header)
        track = self.track(song_id)
        if track:
            return _piece(Path(track.path), _TYPES.get(Path(track.path).suffix.lower(), "application/octet-stream"), start, end)
        if not song_id.startswith("ytm:"):
            raise core.NotFound("That song isn't on this computer")
        with self._lock:
            entry = self._cache.get(song_id)
            if entry and (self._cache_dir / entry["file"]).exists():
                self._played(song_id, entry)
                return _piece(self._cache_dir / entry["file"], entry["type"], start, end)
        return self._from_fill(song_id, start, end)

    def _played(self, song_id: str, entry: dict) -> None:
        now = time.time()
        if now - entry.get("last", 0) > 30 * 60:
            entry["plays"] = [p for p in entry.get("plays", []) if now - p < _PLAYS_WINDOW_S] + [now]
            entry["last"] = now
            self._save_cache()

    def _from_fill(self, song_id: str, start: int, end: int) -> tuple[int, dict, bytes]:
        fill = self._fill(song_id)
        with fill.changed:
            # Far ahead of what came in (a seek): that piece straight from YouTube, the download goes on.
            if not fill.done and fill.total is not None and start > fill.have + PIECE:
                info = fill.info
            else:
                info = None
                fill.changed.wait_for(lambda: fill.done or fill.failed or fill.have > start, timeout=30)
        if info is not None:
            return self._direct(info, start, end, fill)
        if fill.failed and fill.have <= start:
            raise core.Unavailable(fill.failed)
        total = fill.total
        if total is not None and start >= total:
            return 416, {"Content-Range": f"bytes */{total}"}, b""
        last = min(end, start + PIECE - 1, fill.have - 1)
        with open(fill.file or fill.part, "rb") as f:
            f.seek(start)
            data = f.read(last - start + 1)
        headers = {"Content-Type": fill.mime, "Accept-Ranges": "bytes",
                   "Content-Range": f"bytes {start}-{start + len(data) - 1}/{total if total is not None else '*'}"}
        return 206, headers, data

    def _direct(self, info: core.StreamInfo, start: int, end: int, fill: _Fill) -> tuple[int, dict, bytes]:
        last = min(end, start + PIECE - 1)
        r = self._http.get(info.url, headers=dict(info.http_headers, Range=f"bytes={start}-{last}"))
        if r.status_code >= 400:
            raise core.Unavailable(f"YouTube answered {r.status_code}")
        return 206, {"Content-Type": fill.mime, "Accept-Ranges": "bytes",
                     "Content-Range": r.headers.get("content-range", f"bytes {start}-{start + len(r.content) - 1}/*")}, r.content

    def _fill(self, song_id: str) -> _Fill:
        with self._lock:
            fill = self._fills.get(song_id)
            if fill and not fill.failed:
                return fill
            fill = _Fill(song_id, self._cache_dir / f"{_file_name(song_id)}.part")
            self._fills[song_id] = fill
        threading.Thread(target=self._download, args=(fill,), name=f"cache-{song_id}", daemon=True).start()
        return fill

    def _download(self, fill: _Fill) -> None:
        song_id = fill.song_id
        try:
            info = self._ytm.stream(song_id)
            with fill.changed:
                fill.info = info
                fill.mime = info.mime_type
            keep = self.cache_bytes > 0 and (info.duration_ms or 0) <= _MAX_CACHED_MS
            with open(fill.part, "wb") as out:  # closed before it's kept: Windows won't move an open file
                while fill.total is None or fill.have < fill.total:
                    end = fill.have + core.CHUNK_SIZE - 1
                    r = self._http.get(info.url, headers=dict(info.http_headers, Range=f"bytes={fill.have}-{end}"))
                    if r.status_code == 403 and fill.have == 0:
                        self._ytm.forget_stream(song_id)
                        info = self._ytm.stream(song_id, fresh=True)
                        fill.info = info
                        continue
                    if r.status_code >= 400:
                        raise core.Unavailable(f"YouTube answered {r.status_code}")
                    total = _total(r.headers.get("content-range")) or (fill.have + len(r.content) if r.status_code == 200 else None)
                    if not r.content:
                        break
                    out.write(r.content)
                    out.flush()
                    with fill.changed:
                        fill.have += len(r.content)
                        fill.total = total
                        fill.changed.notify_all()
            if fill.total is not None and fill.have >= fill.total and keep:
                name = _file_name(song_id) + (".webm" if "webm" in fill.mime else ".m4a")
                with self._lock:
                    try:
                        os.replace(fill.part, self._cache_dir / name)
                    except PermissionError:  # Windows: the player reads the part file right now
                        shutil.copyfile(fill.part, self._cache_dir / name)
                    now = time.time()
                    self._cache[song_id] = {"file": name, "type": fill.mime, "size": fill.have, "saved": now, "last": now, "plays": [now]}
                    self._save_cache()
                log.info("song cache: kept %s (%d KB)", song_id, fill.have // 1024)
                with fill.changed:
                    fill.file = self._cache_dir / name
                    fill.done = True
                    fill.changed.notify_all()
                self.trim()
            else:
                with fill.changed:
                    fill.done = True
                    fill.changed.notify_all()
        except Exception as e:  # the player hears it, and the song isn't kept
            log.warning("song cache: %s didn't come in: %s", song_id, e)
            with fill.changed:
                fill.failed = str(e) or type(e).__name__
                fill.changed.notify_all()
        finally:
            # Read from the part file while it lasts; a kept song is read from its own file.
            def forget():
                # A song that isn't kept is read from its part file while it plays.
                time.sleep(60 if fill.file else 15 * 60)
                with self._lock:
                    if self._fills.get(song_id) is fill:
                        del self._fills[song_id]
                fill.part.unlink(missing_ok=True)
            threading.Thread(target=forget, daemon=True).start()

    def trim(self) -> None:
        """Back under 90 % of the size when over it: lowest keep score first; off empties it."""
        with self._lock:
            limit = self.cache_bytes
            total = sum(e.get("size", 0) for e in self._cache.values())
            if total <= limit:
                return
            target = limit * 0.9 if limit else 0
            busy = {s for s, f in self._fills.items() if not f.done}
            for song_id, entry in sorted(self._cache.items(), key=lambda kv: _keep_score(kv[1])):
                if total <= target:
                    break
                if song_id in busy:
                    continue
                (self._cache_dir / entry["file"]).unlink(missing_ok=True)
                total -= entry.get("size", 0)
                del self._cache[song_id]
            self._save_cache()

    def clear_cache(self) -> dict:
        with self._lock:
            for entry in self._cache.values():
                (self._cache_dir / entry["file"]).unlink(missing_ok=True)
            self._cache.clear()
            self._save_cache()
        return self.status()

    def cached(self, song_id: str) -> bool:
        with self._lock:
            entry = self._cache.get(song_id)
            return bool(entry) and (self._cache_dir / entry["file"]).exists()

    # --- sending a song to the server --------------------------------------------------------

    def upload(self, song_id: str, url: str, secret: str) -> None:
        """Sends one of this computer's songs to the server, which asked for it for another player."""
        track = self.track(song_id)
        if not track:
            raise core.NotFound("That song isn't on this computer")
        path = Path(track.path)
        started = time.monotonic()
        with open(path, "rb") as f:
            r = self._http.put(url, content=f, timeout=httpx.Timeout(30.0, write=600.0),
                               headers={"X-Device-Secret": secret, "Content-Type": _TYPES.get(path.suffix.lower(), "application/octet-stream")})
        if r.status_code >= 400:
            raise core.Unavailable(f"The server answered {r.status_code}: {r.text[:200]}")
        log.info("sent %s to the server (%d KB in %.1f s)", song_id, track.size // 1024, time.monotonic() - started)

    # --- files -------------------------------------------------------------------------------

    def _save_cache(self) -> None:
        self._write(self._cache_dir / "index.json", self._cache)

    @staticmethod
    def _read(path: Path, default):
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return default

    @staticmethod
    def _write(path: Path, value) -> None:
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(value), encoding="utf-8")
        os.replace(tmp, path)


def _inside(path: str, folder: str) -> bool:
    try:
        return Path(path).is_relative_to(Path(folder))
    except (ValueError, OSError):
        return False


def _album_artist(ts: list[Track]) -> str:
    names = {t.album_artist for t in ts}
    return names.pop() if len(names) == 1 else "Various Artists"


def _keep_score(entry: dict) -> float:
    plays = len([p for p in entry.get("plays", []) if time.time() - p < _PLAYS_WINDOW_S])
    return entry.get("last", 0) + _KEEP_BONUS_S * (max(plays, 1) - 1)


def _file_name(song_id: str) -> str:
    return song_id.replace(":", "_")


def _total(content_range: str | None) -> int | None:
    if content_range and "/" in content_range:
        tail = content_range.rsplit("/", 1)[1]
        return int(tail) if tail.isdigit() else None
    return None


def _piece(path: Path, mime: str, start: int, end: int) -> tuple[int, dict, bytes]:
    total = path.stat().st_size
    if start >= total:
        return 416, {"Content-Range": f"bytes */{total}"}, b""
    last = min(end, total - 1, start + PIECE - 1)
    with open(path, "rb") as f:
        f.seek(start)
        data = f.read(last - start + 1)
    return 206, {"Content-Type": mime, "Accept-Ranges": "bytes", "Content-Range": f"bytes {start}-{last}/{total}"}, data


def _first(tags, *names: str) -> str | None:
    for name in names:
        value = tags.get(name) if tags else None
        if value:
            text = str(value[0] if isinstance(value, list) else value).strip()
            if text:
                return text
    return None


def _number(text: str | None) -> int | None:
    if not text:
        return None
    head = text.split("/")[0].strip()
    return int(head) if head.isdigit() else None


def _read_tags(path: Path, base: Path, stat: os.stat_result) -> Track | None:
    import mutagen

    try:
        audio = mutagen.File(path, easy=True)
    except Exception:
        audio = None
    tags = audio.tags if audio is not None else None
    length = getattr(getattr(audio, "info", None), "length", 0) or 0
    title = _first(tags, "title") or path.stem
    artist = _first(tags, "artist") or _first(tags, "albumartist") or "Unknown artist"
    relative = path.parent.relative_to(base)
    folder = "/".join([base.name, *relative.parts]) if relative.parts else base.name
    return Track(
        path=str(path),
        key=_hash(str(path)),
        title=title,
        artist=artist,
        album_artist=_first(tags, "albumartist") or artist,
        album=_first(tags, "album") or "",
        disc=_number(_first(tags, "discnumber")),
        track=_number(_first(tags, "tracknumber")),
        year=(_first(tags, "date") or "")[:4] or None,
        genre=_first(tags, "genre"),
        duration_ms=int(length * 1000),
        folder=folder,
        mtime=stat.st_mtime,
        size=stat.st_size,
        art=_cover(path) is not None,
    )


def _cover(path: Path) -> tuple[bytes, str] | None:
    """The picture in the file, else a cover image in its folder."""
    import mutagen

    try:
        audio = mutagen.File(path)
    except Exception:
        audio = None
    tags = getattr(audio, "tags", None)
    try:
        if tags is not None:
            for key in list(tags.keys()):
                if str(key).startswith("APIC"):  # MP3
                    frame = tags[key]
                    return frame.data, frame.mime or "image/jpeg"
            if "covr" in tags:  # M4A
                cover = tags["covr"][0]
                return bytes(cover), "image/png" if getattr(cover, "imageformat", 0) == 14 else "image/jpeg"
        pictures = getattr(audio, "pictures", None)  # FLAC
        if pictures:
            return pictures[0].data, pictures[0].mime or "image/jpeg"
    except Exception:
        pass
    for entry in sorted(path.parent.iterdir()) if path.parent.is_dir() else []:
        if entry.suffix.lower() in (".jpg", ".jpeg", ".png") and entry.stem.lower() in _COVER_NAMES:
            return entry.read_bytes(), "image/png" if entry.suffix.lower() == ".png" else "image/jpeg"
    return None
