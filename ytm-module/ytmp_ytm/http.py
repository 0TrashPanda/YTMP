"""HTTP layer for the YTM module (docs/implementation/module-api.md)."""

from __future__ import annotations

import os
import secrets
from contextlib import asynccontextmanager

import httpx
import uvicorn
from fastapi import Depends, FastAPI, Header, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.background import BackgroundTask

from . import core

API_VERSION = 1
VERSION = "0.1.0"
_PASSED_RESPONSE_HEADERS = ("content-type", "content-length", "content-range", "accept-ranges")


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
            "capabilities": ["search", "stream", "audio"],
        }

    @app.get("/search", dependencies=[Depends(check_key)])
    def search(q: str, limit: int = 20):
        return {"items": ytm.search(q, limit=min(max(limit, 1), 50)), "next": None}

    @app.get("/songs/{song_id}/stream", dependencies=[Depends(check_key)])
    def stream(song_id: str):
        return ytm.stream(song_id).to_json()

    @app.get("/songs/{song_id}/audio", dependencies=[Depends(check_key)])
    async def audio(song_id: str, range: str | None = Header(default=None)):
        async def fetch(info: core.StreamInfo) -> httpx.Response:
            headers = dict(info.http_headers)
            if range:
                headers["Range"] = range
            return await http.send(http.build_request("GET", info.url, headers=headers), stream=True)

        info = await run_in_threadpool(ytm.stream, song_id)
        upstream = await fetch(info)
        if upstream.status_code == 403:
            # The cached URL went bad before its expiry; get a fresh one and try once more.
            await upstream.aclose()
            info = await run_in_threadpool(ytm.stream, song_id, True)
            upstream = await fetch(info)
        if upstream.status_code >= 400:
            await upstream.aclose()
            raise _ApiError(502, "upstream_error", f"YouTube answered {upstream.status_code}")
        passed = {k: v for k, v in upstream.headers.items() if k.lower() in _PASSED_RESPONSE_HEADERS}
        passed.setdefault("accept-ranges", "bytes")
        return StreamingResponse(
            upstream.aiter_raw(),
            status_code=upstream.status_code,
            headers=passed,
            media_type=info.mime_type,
            background=BackgroundTask(upstream.aclose),
        )

    return app


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
