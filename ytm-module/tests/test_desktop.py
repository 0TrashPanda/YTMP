"""The desktop app's music and song cache on this computer (desktop.py): a temporary folder and a fake YouTube."""

import re
import time

import httpx
import pytest

from ytmp_ytm import core, desktop

SONG = bytes(range(256)) * (3 * 4096 + 7)  # ~3 MB, more than one chunk


class FakeYtm:
    def __init__(self):
        self.lookups = 0

    def stream(self, song_id, fresh=False):
        self.lookups += 1
        return core.StreamInfo(url="https://yt.test/a", expires_at=time.time() + 3600, mime_type="audio/mp4",
                               bitrate=128, duration_ms=200_000)

    def forget_stream(self, song_id):
        pass


def youtube(request: httpx.Request) -> httpx.Response:
    start, end = (int(x) for x in re.fullmatch(r"bytes=(\d+)-(\d+)", request.headers["range"]).groups())
    end = min(end, len(SONG) - 1)
    return httpx.Response(206, content=SONG[start:end + 1], headers={"content-range": f"bytes {start}-{end}/{len(SONG)}"})


@pytest.fixture
def made(tmp_path):
    def make(**settings):
        d = desktop.Desktop(FakeYtm(), tmp_path / "app", "abc123", "ytmpfiles://localhost/")
        d._http = httpx.Client(transport=httpx.MockTransport(youtube))
        if settings:
            d.set_settings(settings)
        return d
    return make


def test_music_from_the_picked_folders(made, tmp_path):
    music = tmp_path / "Music"
    (music / "Album").mkdir(parents=True)
    (music / "Album" / "One.mp3").write_bytes(b"not really audio" * 100)
    (music / "Album" / "notes.txt").write_text("not music")
    (music / "Two.flac").write_bytes(b"x" * 50)

    d = made()
    assert d.summary()["songCount"] == 0, "off by default"
    d.set_settings({"music": True, "folders": [str(music)]})
    d.rescan(wait=True)

    page = d.library()
    assert page["kind"] == "computer"
    assert [s["title"] for s in page["songs"]] == ["One", "Two"]
    assert all(s["id"].startswith("local:dev-abc123:s:") for s in page["songs"])
    assert sorted(f["name"] for f in page["folders"]) == ["Music", "Music/Album"]

    one = page["songs"][0]["id"]
    status, headers, data = d.file(one, "bytes=4-9")
    assert status == 206 and data == (b"not really audio" * 100)[4:10]
    assert headers["Content-Range"] == "bytes 4-9/1600" and headers["Content-Type"] == "audio/mpeg"

    # Switched off: nothing of it shows or plays.
    d.set_settings({"music": False})
    assert d.library()["songs"] == []
    with pytest.raises(core.NotFound):
        d.file(one, None)


def test_a_song_is_kept_as_it_plays_and_then_played_from_the_disk(made):
    d = made(cacheMb=100)
    status, headers, data = d.file("ytm:abc", "bytes=0-")
    assert status == 206 and data == SONG[:len(data)] and len(data) > 0
    assert headers["Content-Range"].endswith(f"/{len(SONG)}")

    for _ in range(100):
        if d.cached("ytm:abc"):
            break
        time.sleep(0.05)
    assert d.cached("ytm:abc")
    assert d.status()["cacheSongs"] == 1

    lookups = d._ytm.lookups
    status, _, data = d.file("ytm:abc", f"bytes={len(SONG) - 10}-")
    assert data == SONG[-10:]
    assert d._ytm.lookups == lookups, "no lookup for a kept song"

    d.set_settings({"cacheMb": 0})
    assert d.status()["cacheSongs"] == 0, "off empties it"


def test_an_off_cache_keeps_nothing(made):
    d = made()
    d.file("ytm:abc", "bytes=0-")
    time.sleep(0.5)
    assert not d.cached("ytm:abc")
