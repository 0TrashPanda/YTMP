"""HTTP layer for the YTM module (docs/implementation/module-api.md)."""

from __future__ import annotations

import hashlib
import os
import secrets
import threading
from collections import OrderedDict
from contextlib import asynccontextmanager

import httpx
import uvicorn
from fastapi import Depends, FastAPI, Header, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, StreamingResponse

from . import core

API_VERSION = 1
VERSION = "0.1.0"


def create_app(ytm: core.YtmCore, key: str | None) -> FastAPI:
    http = httpx.AsyncClient(timeout=httpx.Timeout(10.0, read=30.0), follow_redirects=True)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await http.aclose()

    app = FastAPI(title="YTMP YTM module", version=VERSION, lifespan=lifespan)

    def check_key(authorization: str | None = Header(default=None)) -> None:
        if key and not (authorization and secrets.compare_digest(authorization, f"Bearer {key}")):
            raise _ApiError(401, "unauthorized", "Missing or wrong module key")

    @app.exception_handler(core.Unavailable)
    async def unavailable(_: Request, e: core.Unavailable):
        return _error(422, "unavailable", str(e))

    @app.exception_handler(core.NotFound)
    async def not_found(_: Request, e: core.NotFound):
        return _error(404, "not_found", str(e))

    @app.exception_handler(core.NotSignedIn)
    async def not_signed_in(_: Request, e: core.NotSignedIn):
        return _error(401, "not_signed_in", str(e))

    @app.exception_handler(_ApiError)
    async def api_error(_: Request, e: _ApiError):
        return _error(e.status, e.code, e.message)

    @app.get("/health")
    def health():
        return {"ok": True}

    @app.get("/info", dependencies=[Depends(check_key)])
    def info():
        return {
            "id": core.MODULE_ID,
            "name": "YouTube Music",
            "version": VERSION,
            "apiVersion": API_VERSION,
            "capabilities": ["search", "suggestions", "stream", "audio", "radio", "artist", "album", "playlist", "podcast", "home", "personal"],
        }

    @app.get("/search", dependencies=[Depends(check_key)])
    def search(q: str, type: str = "all", limit: int = 20):
        return ytm.search(q, type=type, limit=min(max(limit, 1), 50))

    @app.get("/search/suggestions", dependencies=[Depends(check_key)])
    def suggestions(q: str):
        return {"items": ytm.suggestions(q)}

    @app.get("/radio", dependencies=[Depends(check_key)])
    def radio(seed: str, limit: int = 25):
        return {"items": ytm.radio(seed, limit=min(max(limit, 1), 50)), "next": None}

    @app.get("/artists/{artist_id}", dependencies=[Depends(check_key)])
    def artist(artist_id: str):
        return ytm.artist(artist_id)

    @app.get("/albums/{album_id}", dependencies=[Depends(check_key)])
    def album(album_id: str):
        return ytm.album(album_id)

    @app.get("/playlists/{playlist_id}", dependencies=[Depends(check_key)])
    def playlist(playlist_id: str):
        return ytm.playlist(playlist_id)

    @app.get("/home", dependencies=[Depends(check_key)])
    def home():
        return ytm.home()

    @app.get("/podcasts/{podcast_id}", dependencies=[Depends(check_key)])
    def podcast(podcast_id: str):
        return ytm.podcast(podcast_id)

    @app.get("/songs/{song_id}/stream", dependencies=[Depends(check_key)])
    def stream(song_id: str):
        return ytm.stream(song_id).to_json()

    @app.get("/songs/{song_id}/audio", dependencies=[Depends(check_key)])
    async def audio(song_id: str, range: str | None = Header(default=None)):
        info = await run_in_threadpool(ytm.stream, song_id)

        async def fetch(info: core.StreamInfo, start: int, end: int) -> httpx.Response:
            headers = dict(info.http_headers, Range=f"bytes={start}-{end}")
            return await http.send(http.build_request("GET", info.url, headers=headers), stream=True)

        # YouTube answers requests without a range (or with a huge one) very slowly, so always
        # fetch it in chunks with an explicit range, like yt-dlp does, and pass them on as one stream.
        start, end = core.parse_range(range)
        first = await fetch(info, start, min(end, start + core.CHUNK_SIZE - 1))
        if first.status_code == 403:
            # The cached URL went bad before its expiry; get a fresh one and try once more.
            await first.aclose()
            info = await run_in_threadpool(ytm.stream, song_id, True)
            first = await fetch(info, start, min(end, start + core.CHUNK_SIZE - 1))
        if first.status_code == 416:
            await first.aclose()
            raise _ApiError(416, "invalid", "Range not satisfiable")
        if first.status_code >= 400:
            await first.aclose()
            raise _ApiError(502, "upstream_error", f"YouTube answered {first.status_code}")

        total = core.total_size(first.headers.get("content-range"))
        end = min(end, total - 1) if total else end

        async def body():
            response = first
            position = start
            try:
                while True:
                    async for chunk in response.aiter_raw():
                        position += len(chunk)
                        yield chunk
                    await response.aclose()
                    if total is None or position > end:
                        return
                    response = await fetch(info, position, min(end, position + core.CHUNK_SIZE - 1))
                    if response.status_code >= 400:
                        return
            finally:
                await response.aclose()

        headers = {"accept-ranges": "bytes"}
        if total is not None:
            headers["content-length"] = str(end - start + 1)
        if range and total is not None:
            headers["content-range"] = f"bytes {start}-{end}/{total}"
        return StreamingResponse(body(), status_code=206 if range else 200, headers=headers, media_type=info.mime_type)

    # --- one account's YouTube Music (a server keeps the sign-in, and sends it each time) ---

    users: OrderedDict[str, core.YtmCore] = OrderedDict()
    users_lock = threading.Lock()

    def me(x_ytm_cookie: str = Header()) -> core.YtmCore:
        """The signed-in view for the cookies in X-Ytm-Cookie, kept for the next request."""
        key_ = hashlib.sha256(x_ytm_cookie.encode()).hexdigest()
        with users_lock:
            if (user := users.get(key_)) is not None:
                users.move_to_end(key_)
                return user
        user = ytm.signed_in_as(x_ytm_cookie)
        with users_lock:
            users[key_] = user
            while len(users) > _MAX_USERS:
                users.popitem(last=False)
        return user

    @app.get("/me/account", dependencies=[Depends(check_key)])
    def my_account(user: core.YtmCore = Depends(me)):
        return user.account()

    @app.get("/me/home", dependencies=[Depends(check_key)])
    def my_home(user: core.YtmCore = Depends(me)):
        return user.home(personal=True)

    @app.get("/me/library", dependencies=[Depends(check_key)])
    def my_library(user: core.YtmCore = Depends(me)):
        return user.library()

    @app.get("/me/playlists", dependencies=[Depends(check_key)])
    def my_playlists(user: core.YtmCore = Depends(me)):
        return user.own_playlists()

    @app.post("/me/playlists", dependencies=[Depends(check_key)])
    def my_new_playlist(body: dict, user: core.YtmCore = Depends(me)):
        return {"id": user.create_playlist(str(body.get("title") or ""), list(body.get("songIds") or []))}

    @app.get("/me/playlists/{playlist_id}", dependencies=[Depends(check_key)])
    def my_playlist(playlist_id: str, user: core.YtmCore = Depends(me)):
        return user.playlist(playlist_id, personal=True)

    @app.post("/me/playlists/{playlist_id}/songs", dependencies=[Depends(check_key)])
    def my_playlist_add(playlist_id: str, body: dict, user: core.YtmCore = Depends(me)):
        user.add_to_playlist(playlist_id, list(body.get("songIds") or []))
        return {"ok": True}

    @app.get("/me/likes/{song_id}", dependencies=[Depends(check_key)])
    def my_like(song_id: str, user: core.YtmCore = Depends(me)):
        return {"liked": user.liked(song_id)}

    @app.put("/me/likes/{song_id}", dependencies=[Depends(check_key)])
    def my_like_set(song_id: str, body: dict, user: core.YtmCore = Depends(me)):
        user.set_liked(song_id, bool(body.get("liked")))
        return {"liked": bool(body.get("liked"))}

    @app.post("/me/history/{song_id}", dependencies=[Depends(check_key)])
    def my_history_add(song_id: str, user: core.YtmCore = Depends(me)):
        user.add_to_history(song_id)
        return {"ok": True}

    return app


# Signed-in accounts kept ready (each holds a YTMusic session).
_MAX_USERS = 50


class _ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message}}, status_code=status)


def main() -> None:
    ytm = core.YtmCore(
        language=os.environ.get("YTMP_YTM_LANGUAGE", "en"),
        location=os.environ.get("YTMP_YTM_LOCATION", "BE"),
        js_runtime=os.environ.get("YTMP_YTM_JS_RUNTIME") or None,
    )
    app = create_app(ytm, os.environ.get("YTMP_MODULE_KEY") or None)
    uvicorn.run(
        app,
        host=os.environ.get("YTMP_YTM_HOST", "127.0.0.1"),
        port=int(os.environ.get("YTMP_YTM_PORT", "8401")),
    )


if __name__ == "__main__":
    main()
