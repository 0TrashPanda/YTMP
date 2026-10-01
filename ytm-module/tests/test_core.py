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
