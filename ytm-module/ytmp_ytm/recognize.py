"""Which song is playing: a few seconds of sound -> Shazam (shazamio) -> the same song on YouTube Music.

The web app records from the microphone and sends what it has after 3, 5, 8 and 12 seconds, until
one is recognized (docs/features/ui.md, "Recognize a song"). Measured on this PC: 3 seconds is
enough for most songs (2 never was), and Shazam answers in 0.4–0.8 s.

shazamio is unofficial (like yt-dlp): Shazam refuses when asked too often (429), which is
[RateLimited] here instead of shazamio's own retries of up to a minute. Its fingerprinting is
compiled (shazamio_core), so a phone's Python can't use it: [available] says so.
"""

from __future__ import annotations

import logging
import re
import unicodedata

log = logging.getLogger("ytmp_ytm.recognize")

try:
    from aiohttp_retry import ExponentialRetry
    from shazamio import Shazam
    from shazamio.client import HTTPClient
    from shazamio.exceptions import RateLimited as _ShazamRateLimited
except ImportError:  # the phone (no shazamio_core for Android)
    Shazam = None


class RateLimited(Exception):
    """Shazam is refusing for a moment: try again a bit later."""


def available() -> bool:
    return Shazam is not None


_shazam = None


def _client():
    global _shazam
    if _shazam is None:
        # One try: the web app asks again with more sound anyway.
        _shazam = Shazam(http_client=HTTPClient(retry_options=ExponentialRetry(attempts=1)))
    return _shazam


async def identify(wav: bytes) -> dict | None:
    """Shazam's title, artist and ISRC for the sound, or None when it doesn't know it."""
    if Shazam is None:
        raise RuntimeError("Recognizing songs needs shazamio")
    try:
        answer = await _client().recognize(wav)
    except _ShazamRateLimited as e:
        raise RateLimited(str(e)) from e
    track = answer.get("track") or {}
    if not track.get("title"):
        return None
    return {"title": track["title"], "artist": track.get("subtitle") or "", "isrc": track.get("isrc")}


def _words(text: str) -> str:
    """Lower case, no accents, no "(feat. …)" or "- Remastered" parts, only letters and digits."""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode() or text
    text = re.sub(r"[\(\[].*?[\)\]]", " ", text.lower())
    text = re.sub(r"\s-\s.*$", " ", text)
    return " ".join(re.findall(r"\w+", text))


def best_song(found: dict, songs: list[dict]) -> dict | None:
    """The YouTube Music song that is the recognized one: same title and an artist in common.

    [songs] are YTMP songs (parse.py), best search result first. None when no title fits,
    so the app offers a search instead of playing something else.
    """
    title = _words(found["title"])
    artists = set(_words(found["artist"]).split())
    best, best_score = None, 0
    for i, song in enumerate(songs):
        theirs = _words(song["title"])
        if theirs == title:
            score = 3
        elif title and theirs and (title in theirs or theirs in title):
            score = 2
        else:
            continue
        names = set(" ".join(_words(a["name"]) for a in song.get("artists", [])).split())
        if artists & names:
            score += 2
        score -= i * 0.1
        if score > best_score:
            best, best_score = song, score
    return best
