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


def test_bot_check_gets_a_clear_message():
    message = "ERROR: [youtube] abc: Sign in to confirm you\u2019re not a bot. Use --cookies-from-browser or --cookies"
    assert "bot check" in core._clean_error(message)


def test_parse_range():
    assert core.parse_range(None)[0] == 0
    assert core.parse_range("bytes=100-") == (100, core._OPEN_END)
    assert core.parse_range("bytes=0-1023") == (0, 1023)
    assert core.total_size("bytes 0-1023/5000") == 5000
    assert core.total_size(None) is None



def test_song_from_watch_track():
    track = {
        "videoId": "abc",
        "title": "Digital Love",
        "length": "4:58",
        "artists": [{"id": "UC1", "name": "Daft Punk"}],
        "album": {"id": "MPRE1", "name": "Discovery"},
        "thumbnail": [{"url": "https://lh3/x=w60-h60-l90-rj", "width": 60, "height": 60}],
    }
    song = core._song_from_watch(track)
    assert song["id"] == "ytm:abc"
    assert song["durationMs"] == 298_000
    assert song["album"] == {"id": "MPRE1", "name": "Discovery"}
    assert song["thumbnails"][-1]["width"] == 544


def test_parse_length():
    assert core._parse_length("1:02:03") == 3_723_000
    assert core._parse_length(None) == 0
    assert core._parse_length("live") == 0


def test_album_summary():
    x = {"title": "Discovery", "browseId": "MPRE1", "year": "2001", "thumbnails": [{"url": "https://lh3/x=w226-h226-l90-rj", "width": 226, "height": 226}]}
    summary = core._album_summary(x, "Album")
    assert summary["id"] == "MPRE1"
    assert summary["kind"] == "Album"
    assert summary["year"] == "2001"


def test_search_items_of_each_kind():
    thumbs = [{"url": "https://lh3/x=w60-h60-l90-rj", "width": 60, "height": 60}]
    video = core._search_item({"resultType": "video", "videoId": "v1", "title": "Live", "artists": [], "thumbnails": thumbs})
    assert video["kind"] == "song" and video["video"] is True and video["song"]["id"] == "ytm:v1"
    album = core._search_item({
        "resultType": "album", "browseId": "MPRE1", "title": "Discovery", "type": "Album", "year": "2001",
        "artists": [{"name": "Daft Punk", "id": "UC1"}], "thumbnails": thumbs,
    })
    assert album["album"]["id"] == "MPRE1" and album["artists"] == [{"id": "UC1", "name": "Daft Punk"}]
    artist = core._search_item({"resultType": "artist", "artist": "Daft Punk", "browseId": "UC1", "thumbnails": thumbs})
    assert (artist["id"], artist["name"]) == ("UC1", "Daft Punk")
    playlist = core._search_item({"resultType": "playlist", "browseId": "VLPL1", "title": "Mix", "author": "Jazzy", "itemCount": "12"})
    assert (playlist["id"], playlist["author"], playlist["itemCount"]) == ("VLPL1", "Jazzy", 12)
    assert core._search_item({"resultType": "profile", "browseId": "UC2", "name": "someone"}) is None


def test_all_search_is_grouped_like_ytm():
    results = [
        # An artist as top result has no browseId, only "artists".
        {"category": "Top result", "resultType": "artist", "artists": [{"name": "Daft Punk", "id": "UC1"}]},
        {"resultType": "song", "videoId": "s1", "title": "One"},
        {"resultType": "playlist", "browseId": "VLRD1", "title": "Presenting Daft Punk", "author": "YouTube Music"},
        {"resultType": "playlist", "browseId": "VLPL1", "title": "My mix", "author": "Jazzy"},
        {"resultType": "song", "videoId": "s2", "title": "Two"},
        {"resultType": "episode", "videoId": "e1", "title": "A podcast episode"},
        {"resultType": "profile", "browseId": "UC2", "name": "someone"},
    ]
    # The filtered searches have the complete songs (with durations).
    songs = [{"resultType": "song", "videoId": "s2", "title": "Two", "duration_seconds": 200}]
    videos = [{"resultType": "video", "videoId": "v1", "title": "Live", "duration_seconds": 300}]
    sections = core._sections_from_all(results, songs, videos)
    assert [(s["title"], s["type"]) for s in sections] == [
        ("Top result", None),
        ("Songs", "songs"),
        ("Featured playlists", "featured_playlists"),
        ("Community playlists", "community_playlists"),
        ("Episodes", "episodes"),
        ("Videos", "videos"),
    ]
    assert sections[0]["items"][0] == {"kind": "artist", "id": "UC1", "name": "Daft Punk", "thumbnails": []}
    assert [(i["song"]["id"], i["song"]["durationMs"]) for i in sections[1]["items"]] == [("ytm:s2", 200_000)]


def test_a_song_as_top_result_gets_the_complete_data():
    results = [{"category": "Top result", "resultType": "song", "videoId": "s1", "title": "One"}]
    songs = [{"resultType": "song", "videoId": "s1", "title": "One", "duration_seconds": 321, "artists": [{"name": "A", "id": "UC1"}]}]
    top = core._sections_from_all(results, songs, [])[0]["items"][0]["song"]
    assert (top["durationMs"], top["artists"]) == (321_000, [{"id": "UC1", "name": "A"}])


def test_artist_pictures_get_a_large_version_too():
    thumbs = core._thumbnails([{"url": "https://yt3/x=w120-c-h120-k-c0x00ffffff-no-l90-rj", "width": 120, "height": 120}])
    assert thumbs[-1] == {"url": "https://yt3/x=w544-c-h544-k-c0x00ffffff-no-l90-rj", "width": 544, "height": 544}


def test_suggestions_for_nothing_ask_nobody():
    ytm = core.YtmCore.__new__(core.YtmCore)
    assert ytm.suggestions("  ") == []


def test_episodes_are_songs_of_a_podcast():
    item = core._search_item({
        "resultType": "episode", "videoId": "e1", "title": "Episode 1", "date": "Oct 18, 2020",
        "podcast": {"id": "MPSP1", "name": "The Show"},
    })
    assert item["kind"] == "song"
    assert item["song"]["podcast"] == {"id": "MPSP1", "name": "The Show"}
    assert item["song"]["artists"] == []
    podcast = core._search_item({"resultType": "podcast", "browseId": "MPSP1", "title": "The Show"})
    assert (podcast["kind"], podcast["id"]) == ("podcast", "MPSP1")


def test_spoken_lengths():
    assert core._parse_spoken_length("3 hr 36 min") == (3 * 3600 + 36 * 60) * 1000
    assert core._parse_spoken_length("29 min") == 29 * 60 * 1000
    assert core._parse_spoken_length("4:21") == 261_000
    assert core._parse_spoken_length(None) == 0


def test_view_counts_are_not_dates():
    assert core._episode_date("860K views") is None
    assert core._episode_date("Oct 18, 2020") == "Oct 18, 2020"


def test_home_items_of_each_kind():
    song = core._home_item({"videoId": "abc", "title": "Song", "videoType": "MUSIC_VIDEO_TYPE_ATV", "artists": [{"name": "A", "id": "UC1"}]})
    assert song["kind"] == "song" and song["song"]["id"] == "ytm:abc" and song["video"] is False
    album = core._home_item({"browseId": "MPREb_x", "title": "Album", "type": "Single", "artists": [{"name": "A", "id": "UC1"}]})
    assert album["kind"] == "album" and album["album"]["kind"] == "Single"
    playlist = core._home_item({"playlistId": "RDCLAK5uy_x", "title": "Pop Gold", "description": "Alicia Keys, Ed Sheeran"})
    assert playlist == {"kind": "playlist", "id": "RDCLAK5uy_x", "title": "Pop Gold", "author": "Alicia Keys, Ed Sheeran", "itemCount": None, "thumbnails": []}
    assert core._home_item({"title": "Nothing to open"}) is None


def test_signing_in_needs_youtube_music_cookies():
    import pytest
    with pytest.raises(core.NotSignedIn):
        core.YtmCore().sign_in("SID=abc; HSID=def")


def test_personal_pages_need_an_account():
    import pytest
    ytm = core.YtmCore()
    assert ytm.account() is None
    with pytest.raises(core.NotSignedIn):
        ytm.library()
    with pytest.raises(core.NotSignedIn):
        ytm.home(personal=True)


def test_library_items():
    liked = core._library_playlist({"playlistId": "LM", "title": "Liked music", "count": "1,204"})
    assert liked["kind"] == "playlist" and liked["itemCount"] == 1204
    podcast = core._library_podcast({"title": "The Daily", "channel": {"id": "UC1", "name": "NYT"}, "browseId": "MPSPPLx", "podcastId": "PLx"})
    assert podcast == {"kind": "podcast", "id": "MPSPPLx", "title": "The Daily", "author": "NYT", "thumbnails": []}
    new = core._library_podcast({"title": "New Episodes", "channel": {"id": None, "name": "Auto playlist"}, "browseId": "VLRDPN", "podcastId": "RDPN"})
    assert new["kind"] == "playlist" and new["id"] == "RDPN"


def test_likes_and_playlists_need_an_account():
    import pytest
    ytm = core.YtmCore()
    for call in (lambda: ytm.liked("ytm:abc"), lambda: ytm.set_liked("ytm:abc", True), ytm.own_playlists,
                 lambda: ytm.add_to_playlist("PLx", ["ytm:abc"]), lambda: ytm.create_playlist("Mine", ["ytm:abc"])):
        with pytest.raises(core.NotSignedIn):
            call()


class _FakeUser:
    def __init__(self):
        self.rated = []

    def get_watch_playlist(self, videoId, limit):
        return {"tracks": [{"videoId": videoId, "likeStatus": "LIKE" if videoId == "liked" else "INDIFFERENT"}, {"videoId": "next", "likeStatus": "LIKE"}]}

    def rate_song(self, vid, rating):
        self.rated.append((vid, rating))

    def get_library_playlists(self, limit):
        return [{"playlistId": "LM", "title": "Liked music", "owned": False}, {"playlistId": "PLmine", "title": "Mine", "owned": True},
                {"playlistId": "PLtheirs", "title": "Theirs", "owned": False}]

    def add_playlist_items(self, playlist_id, video_ids):
        return {"status": "STATUS_SUCCEEDED"} if video_ids != ["dupe"] else {"actions": ["confirmDialog: duplicate"]}


def test_likes_and_saving_with_an_account():
    import pytest
    ytm = core.YtmCore()
    user = ytm._user = _FakeUser()
    assert ytm.liked("ytm:liked") and not ytm.liked("ytm:other")
    ytm.set_liked("ytm:abc", True)
    ytm.set_liked("ytm:abc", False)
    assert user.rated == [("abc", core.LikeStatus.LIKE), ("abc", core.LikeStatus.INDIFFERENT)]
    assert [p["id"] for p in ytm.own_playlists()] == ["PLmine"]
    ytm.add_to_playlist("PLmine", ["ytm:abc"])
    with pytest.raises(core.Unavailable, match="Already"):
        ytm.add_to_playlist("PLmine", ["ytm:dupe"])
