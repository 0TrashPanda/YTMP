#!/usr/bin/env python3
"""Smoke test of a running YTMP host: search, pages, radio and streaming, through its API.

Read-only: it creates no rooms and plays nothing. Only needs Python 3.

    # The app on a phone (USB debugging on), through its own server on port 8765. Keep the
    # app open on screen: Android cuts the network of an app in the background that isn't
    # hosting a room (search then fails with "Failed to resolve").
    adb forward tcp:8765 tcp:8765
    python3 tools/smoke-test.py http://127.0.0.1:8765

    # A server (streaming is only checked when it streams to you, see [audio] proxy in ytmp.toml):
    python3 tools/smoke-test.py https://ytmp.example.com

Exits with 1 when a check fails. The host's log shows the details (yt-dlp's steps for a
song that didn't work).
"""
import json, sys, time, urllib.request, urllib.error, urllib.parse

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8765").rstrip("/") + "/api"
results = []


class Skip(Exception):
    """Not a failure: this host doesn't do it (for you)."""


def get(path, headers=None, raw=False, timeout=60):
    req = urllib.request.Request(BASE + path, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read()
        return r.status, dict(r.headers), body if raw else json.loads(body or b"null")


def check(name, fn):
    t = time.time()
    try:
        detail = fn()
        results.append((True, name, time.time() - t, detail or ""))
    except Skip as e:
        print(f"SKIP  {name:<42}        {e}", flush=True)
        return None
    except Exception as e:
        if isinstance(e, urllib.error.HTTPError):
            e = f"HTTP {e.code}: {e.read()[:200]!r}"
        results.append((False, name, time.time() - t, str(e)[:300]))
    ok, _, dt, d = results[-1]
    print(f"{'PASS' if ok else 'FAIL'}  {name:<42} {dt:5.1f}s  {d}", flush=True)
    return results[-1]


ctx = {}


def host():
    _, _, h = get("/host")
    return f"kind={h.get('kind')}"


def search():
    _, _, d = get("/search?q=" + urllib.parse.quote("daft punk"))
    songs = [i["song"] for s in d["sections"] for i in s["items"] if i.get("kind") == "song"]
    assert songs, "no songs"
    ctx["songs"] = songs
    for s in d["sections"]:
        for i in s["items"]:
            if i.get("kind") == "artist": ctx.setdefault("artist", i["id"])
            if i.get("kind") == "album": ctx.setdefault("album", i["album"]["id"])
            if i.get("kind") == "playlist": ctx.setdefault("playlist", i["id"])
    ctx.setdefault("album", songs[0].get("album", {}).get("id"))
    return f"{len(d['sections'])} sections, {len(songs)} songs"


def search_type(t):
    def f():
        _, _, d = get(f"/search?type={t}&q=" + urllib.parse.quote("lofi"))
        n = sum(len(s["items"]) for s in d["sections"])
        assert n, "empty"
        if t == "community_playlists":
            ctx.setdefault("playlist", d["sections"][0]["items"][0]["id"])
        if t == "podcasts":
            ctx["podcast"] = d["sections"][0]["items"][0]["id"]
        return f"{n} items"
    return f


def suggestions():
    _, _, d = get("/search/suggestions?q=queen")
    assert d["items"], "none"
    return f"{len(d['items'])}: {d['items'][0]!r}"


def page(kind, key):
    def f():
        _, _, d = get(f"/{kind}/{urllib.parse.quote(ctx[key])}")
        return (d.get("name") or d.get("title") or "")[:40]
    return f


def similar():
    _, _, d = get("/similar?id=" + ctx["songs"][0]["id"])
    assert d["items"], "empty radio"
    ctx["radio"] = d["items"]
    return f"{len(d['items'])} songs"


def home():
    _, _, d = get("/home")
    return f"{len(d.get('sections', []))} sections"


def audio_start(song):
    def f():
        if ctx.get("no_audio"):
            raise Skip("this host doesn't stream to you")
        try:
            status, h, body = get(f"/audio/{song['id']}", {"Range": "bytes=0-65535"}, raw=True)
        except urllib.error.HTTPError as e:
            if e.code == 403:
                ctx["no_audio"] = True
                raise Skip("this host doesn't stream to you")
            raise
        assert status == 206 and len(body) == 65536, f"{status} {len(body)}B"
        total = int(h["Content-Range"].split("/")[1])
        ctx.setdefault("sizes", {})[song["id"]] = total
        assert body[4:8] in (b"ftyp",) or body[:4] == b"\x1aE\xdf\xa3", f"not audio: {body[:8]!r}"
        return f"{song['title'][:28]!r}, {total // 1024} KB"
    return f


def audio_seek(song):
    def f():
        total = ctx["sizes"][song["id"]]
        start = total * 3 // 4
        status, h, body = get(f"/audio/{song['id']}", {"Range": f"bytes={start}-{start + 99999}"}, raw=True)
        assert status == 206 and len(body) == min(100000, total - start), f"{status} {len(body)}B"
        return "range at 75% ok"
    return f


def audio_full(song):
    def f():
        t = time.time()
        status, h, body = get(f"/audio/{song['id']}", raw=True, timeout=180)
        dt = time.time() - t
        total = ctx["sizes"][song["id"]]
        assert len(body) == total, f"{len(body)} of {total}B"
        return f"{total // 1024} KB at {total / 1024 / dt:.0f} KB/s"
    return f


def bad_song():
    if ctx.get("no_audio"):
        raise Skip("this host doesn't stream to you")
    try:
        get("/audio/ytm:AAAAAAAAAAA", {"Range": "bytes=0-1000"}, raw=True)
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code} (expected an error)"
    raise AssertionError("an unknown song played")


check("host info", host)
check("search (all)", search)
for t in ("songs", "videos", "albums", "artists", "community_playlists", "featured_playlists", "podcasts", "episodes"):
    check(f"search {t}", search_type(t))
check("search suggestions", suggestions)
check("home page", home)
if ctx.get("artist"): check("artist page", page("artists", "artist"))
if ctx.get("album"): check("album page", page("albums", "album"))
if ctx.get("playlist"): check("playlist page", page("playlists", "playlist"))
if ctx.get("podcast"): check("podcast page", page("podcasts", "podcast"))
if ctx.get("songs"):
    check("radio (similar songs)", similar)
    picks = ctx["songs"][:4] + ctx.get("radio", [])[:6]
    for s in picks:
        check(f"stream start {s['id']}", audio_start(s))
    for s in picks[:3]:
        if s["id"] in ctx.get("sizes", {}):
            check(f"stream seek {s['id']}", audio_seek(s))
    for s in picks[:2]:
        if s["id"] in ctx.get("sizes", {}):
            check(f"stream whole song {s['id']}", audio_full(s))
check("unknown song fails cleanly", bad_song)

failed = [r for r in results if not r[0]]
print(f"\n{len(results) - len(failed)}/{len(results)} passed")
sys.exit(1 if failed else 0)
