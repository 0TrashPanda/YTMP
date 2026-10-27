"""YouTube pages this module couldn't read, kept to turn into tests (docs/implementation/module-api.md#unreadable-pages).

YouTube sometimes sends some visitors another layout, which ytmusicapi can't read. This keeps
YouTube's answers to the last few requests of each call (per thread), and when the call fails
because a page didn't look as expected, or only read with a fallback, writes them to a file:
the module's own requests and answers, never the request headers (no cookies). The newest
[MAX_FILES] are kept. On the phone they go out with *Send logs*.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import tempfile
import time
from pathlib import Path

log = logging.getLogger("ytmp_ytm")

MAX_FILES = 10
_MAX_BYTES = 4_000_000
_KEEP_LAST = 4

# A server's module keeps them in its temp folder unless YTMP_YTM_KEEP_DIR says where ("" : not at all).
_dir: Path | None = (
    Path(os.environ["YTMP_YTM_KEEP_DIR"]) if os.environ.get("YTMP_YTM_KEEP_DIR")
    else None if "YTMP_YTM_KEEP_DIR" in os.environ else Path(tempfile.gettempdir()) / "ytmp-ytm-pages"
)
_seen = threading.local()

# What a page that doesn't look as expected raises in ytmusicapi and in this module.
_UNREADABLE = (KeyError, IndexError, TypeError, AttributeError, ValueError)


def keep_in(path: str | None) -> None:
    """Where to keep them (None: not kept)."""
    global _dir
    _dir = Path(path) if path else None


def record(ytm) -> None:
    """Remembers what [ytm] (a YTMusic) gets from YouTube, for [save]."""
    send = ytm._send_request

    def recorded(endpoint, body, *args, **kwargs):
        response = send(endpoint, body, *args, **kwargs)
        seen = getattr(_seen, "pages", None)
        if seen is not None:
            seen.append({"endpoint": endpoint, "body": body, "response": response})
            del seen[:-_KEEP_LAST]
        return response

    ytm._send_request = recorded


def start() -> None:
    """A new call: forget the pages of the one before."""
    _seen.pages = []


def unreadable(error: BaseException) -> bool:
    """Whether [error] (or what caused it) says a page didn't look as expected."""
    e: BaseException | None = error
    while e is not None:
        if isinstance(e, _UNREADABLE):
            return True
        e = e.__cause__ or e.__context__
    return False


def save(what: str, ident: str, error: BaseException | str, note: str = "") -> Path | None:
    """Writes the pages of this call to a file; returns it (None: not kept, or nothing to keep)."""
    pages = getattr(_seen, "pages", None)
    if _dir is None or not pages:
        return None
    try:
        _dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in ident)[:40]
        path = _dir / f"page-{stamp}-{what}-{safe}.json"
        text = json.dumps(
            {"what": what, "id": ident, "error": f"{type(error).__name__}: {error}" if isinstance(error, BaseException) else error,
             "note": note, "at": stamp, "pages": pages},
            separators=(",", ":"),
        )
        if len(text) > _MAX_BYTES:
            text = json.dumps({"what": what, "id": ident, "error": str(error), "note": note, "at": stamp, "pages": pages[-1:]}, separators=(",", ":"))
        path.write_text(text)
        for old in sorted(_dir.glob("page-*.json"))[:-MAX_FILES]:
            old.unlink(missing_ok=True)
        log.warning("Kept a YouTube page this module couldn't read (%s %s): %s", what, ident, path.name)
        return path
    except OSError as e:
        log.warning("Couldn't keep a page: %s", e)
        return None


def kept() -> list[Path]:
    """The kept pages, oldest first."""
    return sorted(_dir.glob("page-*.json")) if _dir and _dir.is_dir() else []
