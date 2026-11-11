"""yt-dlp, kept up to date by the module itself, for copies that can't wait for a new release.

YouTube breaks yt-dlp often and yt-dlp fixes it within days. The server's image is rebuilt
every night with the newest yt-dlp, but the phone (and the desktop app) only get the yt-dlp
they were built with. Given a folder of its own, the module:

- checks PyPI about once a day (`start`) for a yt-dlp newer than the one in use, downloads its
  wheel (pure Python), checks it against PyPI's sha256 and unpacks it in that folder. When the
  new yt-dlp needs another version of yt-dlp-ejs (its JavaScript, pinned exactly), that one
  comes along in the same folder;
- loads it at the next start (`activate`, before anything imports yt-dlp), in front of the
  bundled one. A running yt-dlp isn't swapped: the PO token and JavaScript providers are
  registered with it, and lookups in flight use it.

Falls back to the bundled yt-dlp, and never tries that version again, when the downloaded one
doesn't import (with the module's own code that builds on it) or its first lookups all fail
(`lookup_failed`; from the next start). One that worked once is trusted from then on: a later
failure is YouTube's doing, not the download's. A newer bundled yt-dlp (an app update) wins
over an older download.

The folder: `yt-dlp.json` (what's ready, what failed, when PyPI was last asked) and
`yt-dlp/<version>/` (the unpacked wheels, ready to put on `sys.path`).

This file must not import yt-dlp at the top: `activate` has to come first.
"""

from __future__ import annotations

import hashlib
import importlib
import json
import logging
import os
import re
import shutil
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

log = logging.getLogger("ytmp_ytm.update")

PYPI = "https://pypi.org"
CHECK_EVERY_S = 24 * 3600
# After the start, so the first check doesn't compete with the first searches.
_FIRST_CHECK_AFTER_S = 60
# A downloaded yt-dlp whose first this-many lookups all fail goes back to the bundled one.
FIRST_FAILURES = 3
_TIMEOUT_S = 30
_USER_AGENT = "ytmp-ytm (yt-dlp update check)"
# The module's own code that builds on yt-dlp's (partly internal) parts: it has to import too.
_SMOKE_IMPORTS = ("yt_dlp", "yt_dlp.extractor.youtube", "ytmp_ytm.core", "ytmp_ytm.jsc")

_folder: Path | None = None
_bundled: str | None = None
# The downloaded yt-dlp in use, or None when it's the bundled one.
_active: str | None = None
_failures = 0
_proven = False
_notes: list[tuple[int, str]] = []
_lock = threading.Lock()
_thread: threading.Thread | None = None


def activate(folder: str | os.PathLike) -> str | None:
    """Uses the downloaded yt-dlp from [folder], when there's one that's newer than the bundled
    one and hasn't failed before. Call it before anything imports yt-dlp. Returns the version
    it put in use, or None for the bundled one."""
    global _folder, _bundled, _active, _proven, _failures
    _folder = Path(folder)
    _folder.mkdir(parents=True, exist_ok=True)
    if "yt_dlp" in sys.modules:
        _note(logging.WARNING, "yt-dlp was imported before its update folder was set: using the bundled one")
        return None
    _bundled = _installed_version("yt-dlp")
    state = _load()
    ready = state.get("ready")
    if not ready:
        return None
    path = _versions() / ready
    if ready in state.get("bad", []) or not path.is_dir():
        _forget(state, ready)
        return None
    if _bundled and not _newer(ready, _bundled):
        _note(logging.INFO, f"yt-dlp {_bundled} came with the app; dropping the downloaded {ready}")
        _forget(state, ready)
        shutil.rmtree(path, ignore_errors=True)
        return None

    sys.path.insert(0, str(path))
    importlib.invalidate_caches()
    try:
        for name in _SMOKE_IMPORTS:
            importlib.import_module(name)
        found = sys.modules["yt_dlp"].version.__version__
        if found != ready:
            raise ImportError(f"it says it is {found}")
    except Exception as e:
        sys.path.remove(str(path))
        # yt-dlp and everything of the module's that may have imported it, so they come again
        # with the bundled one.
        for name in [n for n in sys.modules if n.split(".")[0] in ("yt_dlp", "yt_dlp_ejs")
                     or (n.startswith("ytmp_ytm.") and n != __name__)]:
            del sys.modules[name]
        importlib.invalidate_caches()
        _note(logging.WARNING, f"the downloaded yt-dlp {ready} doesn't load ({type(e).__name__}: {e}); using the bundled {_bundled}")
        _mark_bad(state, ready)
        return None
    _active = ready
    _proven = ready in state.get("proven", [])
    _failures = 0
    return ready


def describe() -> str:
    """The yt-dlp in use, for the log and the app: "2026.11.5 (downloaded; bundled 2026.10.26)"."""
    import yt_dlp

    version = yt_dlp.version.__version__
    if _active:
        return f"{version} (downloaded; bundled {_bundled})"
    return version


def take_notes() -> list[tuple[int, str]]:
    """What `activate` had to say, from before the log was set up: (level, message)."""
    notes = list(_notes)
    _notes.clear()
    return notes


def lookup_ok() -> None:
    """A stream lookup worked: the downloaded yt-dlp in use (if any) is trusted from now on."""
    global _proven
    if not _active or _proven:
        return
    _proven = True
    with _lock:
        state = _load()
        if _active not in state.setdefault("proven", []):
            state["proven"].append(_active)
            _save(state)


def lookup_failed() -> None:
    """A stream lookup failed (not YouTube's bot check). When the downloaded yt-dlp's first
    lookups all fail, the next start goes back to the bundled one."""
    global _failures
    if not _active or _proven:
        return
    _failures += 1
    if _failures == FIRST_FAILURES:
        log.warning("the downloaded yt-dlp %s failed its first %d lookups: the bundled %s again from the next start",
                    _active, FIRST_FAILURES, _bundled)
        with _lock:
            _mark_bad(_load(), _active)


def start(index: str = PYPI) -> None:
    """Checks PyPI now (a minute after the start) and then about once a day, in the background.
    Needs `activate` first."""
    global _thread
    if _folder is None or _thread is not None:
        return

    def run() -> None:
        time.sleep(_FIRST_CHECK_AFTER_S)
        while True:
            last = _load().get("checked", 0)
            wait = last + CHECK_EVERY_S - time.time()
            if wait > 0:
                time.sleep(min(wait, CHECK_EVERY_S))
                continue
            try:
                check(index)
            except Exception as e:
                log.warning("yt-dlp update check failed: %s: %s", type(e).__name__, e)
                # Not right away again (no network, PyPI down): in an hour.
                with _lock:
                    state = _load()
                    state["checked"] = time.time() - CHECK_EVERY_S + 3600
                    _save(state)

    _thread = threading.Thread(target=run, name="yt-dlp-update", daemon=True)
    _thread.start()


def check(index: str = PYPI) -> str | None:
    """Asks PyPI for the newest yt-dlp and downloads it when it's newer than the one in use
    (and didn't fail before). Returns the version it downloaded, ready for the next start."""
    if _folder is None:
        raise RuntimeError("no update folder: call activate() first")
    import yt_dlp

    running = yt_dlp.version.__version__
    release = _get_json(f"{index}/pypi/yt-dlp/json")
    latest = release["info"]["version"]
    with _lock:
        state = _load()
        state["checked"] = time.time()
        _save(state)
    if not _is_release(latest) or not _newer(latest, running) or latest in state.get("bad", []):
        return None
    if state.get("ready") == latest and (_versions() / latest).is_dir():
        return None

    _versions().mkdir(parents=True, exist_ok=True)
    unpacked = Path(tempfile.mkdtemp(prefix=f".{latest}-", dir=_versions()))
    try:
        _unpack(_wheel(release), unpacked)
        ejs = _pinned_ejs(release["info"].get("requires_dist") or [])
        if ejs and ejs != _installed_version("yt-dlp-ejs"):
            _unpack(_wheel(_get_json(f"{index}/pypi/yt-dlp-ejs/{ejs}/json")), unpacked)
        target = _versions() / latest
        shutil.rmtree(target, ignore_errors=True)
        unpacked.rename(target)
    finally:
        shutil.rmtree(unpacked, ignore_errors=True)

    with _lock:
        state = _load()
        state["ready"] = latest
        _save(state)
    # Older downloads go, except the one in use right now.
    for old in _versions().iterdir():
        if old.name not in (latest, _active):
            shutil.rmtree(old, ignore_errors=True)
    log.info("yt-dlp %s downloaded (in use: %s): it's used from the next start", latest, running)
    return latest


def _wheel(release: dict) -> tuple[str, str]:
    """The pure Python wheel of a PyPI release: (url, sha256)."""
    for file in release.get("urls") or []:
        if file.get("packagetype") == "bdist_wheel" and file.get("filename", "").endswith("-py3-none-any.whl"):
            sha256 = (file.get("digests") or {}).get("sha256")
            if not sha256:
                break
            return file["url"], sha256
    raise ValueError(f"no pure Python wheel with a sha256 for {release['info'].get('name')} {release['info'].get('version')}")


def _unpack(wheel: tuple[str, str], into: Path) -> None:
    url, sha256 = wheel
    with tempfile.TemporaryFile() as file:
        digest = hashlib.sha256()
        with _open(url) as response:
            while chunk := response.read(1 << 16):
                digest.update(chunk)
                file.write(chunk)
        if digest.hexdigest() != sha256.lower():
            raise ValueError(f"{url.rsplit('/', 1)[-1]}: its sha256 isn't the one PyPI gives")
        file.seek(0)
        with zipfile.ZipFile(file) as archive:
            root = into.resolve()
            for name in archive.namelist():
                if not (root / name).resolve().is_relative_to(root):
                    raise ValueError(f"the wheel has a file outside its folder: {name}")
            archive.extractall(into)


def _pinned_ejs(requires: list[str]) -> str | None:
    """The yt-dlp-ejs version yt-dlp asks for ("yt-dlp-ejs==0.8.0; extra == 'default'")."""
    for line in requires:
        if match := re.match(r"\s*yt[-_]dlp[-_]ejs\s*==\s*([\w.]+)", line):
            return match.group(1)
    return None


def _get_json(url: str) -> dict:
    with _open(url) as response:
        return json.load(response)


def _open(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT, "Accept": "application/json"})
    return urllib.request.urlopen(request, timeout=_TIMEOUT_S)


def _installed_version(package: str) -> str | None:
    """The version of [package] as installed (bundled), before `activate` changes the path."""
    try:
        from importlib.metadata import version

        return version(package)
    except Exception:
        pass
    # Without its metadata: read yt-dlp's version.py, without importing it.
    try:
        import importlib.util

        spec = importlib.util.find_spec(package.replace("-", "_"))
        file = os.path.join(list(spec.submodule_search_locations)[0], "version.py")
        text = spec.loader.get_data(file).decode()
        return re.search(r"__version__\s*=\s*['\"]([^'\"]+)", text).group(1)
    except Exception:
        return None


def _is_release(version: str) -> bool:
    return re.fullmatch(r"\d+(\.\d+)+", version) is not None


def _version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) if part.isdigit() else 0 for part in version.split("."))


def _newer(version: str, than: str) -> bool:
    return _version_key(version) > _version_key(than)


def _versions() -> Path:
    assert _folder is not None
    return _folder / "yt-dlp"


def _load() -> dict:
    try:
        return json.loads((_folder / "yt-dlp.json").read_text())
    except (OSError, ValueError):
        return {}


def _save(state: dict) -> None:
    path = _folder / "yt-dlp.json"
    temp = path.with_suffix(".json.tmp")
    temp.write_text(json.dumps(state))
    temp.replace(path)


def _forget(state: dict, version: str) -> None:
    if state.get("ready") == version:
        state["ready"] = None
        _save(state)


def _mark_bad(state: dict, version: str) -> None:
    if version not in state.setdefault("bad", []):
        state["bad"].append(version)
    _forget(state, version)
    _save(state)
    # The one in use stays until the next start: yt-dlp imports parts of itself as it goes.
    if version != _active:
        shutil.rmtree(_versions() / version, ignore_errors=True)


def _note(level: int, message: str) -> None:
    # The log isn't set up yet this early (the app's comes with the module): YtmCore logs these.
    _notes.append((level, message))


def _reset_for_tests() -> None:
    global _folder, _bundled, _active, _failures, _proven, _thread
    _folder = _bundled = _active = _thread = None
    _failures = 0
    _proven = False
    _notes.clear()
