from ytmp_ytm import core


def test_video_id_strips_prefix():
    assert core.video_id("ytm:abc") == "abc"
    assert core.video_id("yt:abc") == "abc"
    assert core.video_id("abc") == "abc"


def test_song_from_search_result():
    result = {
        "videoId": "wU26xVT_vBU",
        "title": "One More Time",
        "artists": [{"name": "Daft Punk", "id": "UC1"}],
        "album": {"name": "Discovery", "id": "MPRE1"},
        "duration_seconds": 321,
        "isExplicit": False,
        "thumbnails": [{"url": "https://lh3.googleusercontent.com/x=w60-h60-l90-rj", "width": 60, "height": 60}],
    }
    song = core._song_from_search(result)
    assert song["id"] == "ytm:wU26xVT_vBU"
    assert song["durationMs"] == 321_000
    assert song["artists"] == [{"id": "UC1", "name": "Daft Punk"}]
    assert song["album"] == {"id": "MPRE1", "name": "Discovery"}
    # A large version of the album art is added.
    assert song["thumbnails"][-1] == {
        "url": "https://lh3.googleusercontent.com/x=w544-h544-l90-rj",
        "width": 544,
        "height": 544,
    }


def test_results_without_video_are_skipped():
    assert core._song_from_search({"title": "no video"}) is None


def test_clean_error():
    assert core._clean_error("ERROR: [youtube] abc: Video unavailable") == "Video unavailable"


def test_fresh_stream_skips_the_cache(monkeypatch):
    ytm = core.YtmCore.__new__(core.YtmCore)
    ytm._lock = core.threading.Lock()
    ytm._streams = {"abc": core.StreamInfo("https://old", core.time.time() + 3600, "audio/mp4", None)}
    ytm._ydl_opts = {}

    class FakeYdl:
        def __init__(self, _opts): pass
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def extract_info(self, _url, download): return {"url": "https://new?expire=9999999999", "ext": "m4a"}

    monkeypatch.setattr(core.yt_dlp, "YoutubeDL", FakeYdl)
    assert ytm.stream("ytm:abc").url == "https://old"
    assert ytm.stream("ytm:abc", fresh=True).url == "https://new?expire=9999999999"
    assert ytm.stream("ytm:abc").url == "https://new?expire=9999999999"
