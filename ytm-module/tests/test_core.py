import time

import pytest

from ytmp_ytm import core, parse


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
    song = parse.song_from_search(result)
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
    assert parse.song_from_search({"title": "no video"}) is None


def test_clean_error():
    assert core._clean_error("ERROR: [youtube] abc: Video unavailable") == "Video unavailable"


def _bare_core() -> core.YtmCore:
    ytm = core.YtmCore.__new__(core.YtmCore)
    ytm._lock = core.threading.Lock()
    ytm._streams = {}
    ytm._ydl_opts = {"extractor_args": {"youtube": core._PLAYER_SKIP}}
    ytm._pot_url = None
    ytm._bot_check = core._BotCheck()
    ytm._ydls = core._YdlPool()
    return ytm


class FakeYdl:
    """yt-dlp that answers with [result] (a link, or an exception to raise), and keeps its options."""
    result: object = "https://new?expire=9999999999"
    available_at: float | None = None
    opts: list[dict] = []

    def __init__(self, opts):
        FakeYdl.opts.append(opts)
        self.params = dict(opts)
        self.closed = False

    def close(self): self.closed = True

    def extract_info(self, _url, download):
        if isinstance(FakeYdl.result, Exception):
            raise FakeYdl.result
        return {"url": FakeYdl.result, "ext": "m4a", "available_at": FakeYdl.available_at}


def test_fresh_stream_skips_the_cache(monkeypatch):
    ytm = _bare_core()
    ytm._streams = {"abc": core.StreamInfo("https://old", core.time.time() + 3600, "audio/mp4", None)}
    monkeypatch.setattr(core.yt_dlp, "YoutubeDL", FakeYdl)
    monkeypatch.setattr(FakeYdl, "result", "https://new?expire=9999999999")
    assert ytm.stream("ytm:abc").url == "https://old"
    assert ytm.stream("ytm:abc", fresh=True).url == "https://new?expire=9999999999"
    assert ytm.stream("ytm:abc").url == "https://new?expire=9999999999"


def test_yt_dlp_is_kept_between_lookups(monkeypatch):
    monkeypatch.setattr(core.yt_dlp, "YoutubeDL", FakeYdl)
    monkeypatch.setattr(FakeYdl, "opts", [])
    monkeypatch.setattr(FakeYdl, "result", "https://new?expire=9999999999")
    ytm = _bare_core()
    ytm.stream("ytm:a")
    ytm.stream("ytm:b")
    # One instance for both, each lookup with its own log.
    assert len(FakeYdl.opts) == 1

    # One that failed isn't used again.
    monkeypatch.setattr(FakeYdl, "result", core.yt_dlp.utils.DownloadError("ERROR: gone"))
    with pytest.raises(core.Unavailable):
        ytm.stream("ytm:c")
    monkeypatch.setattr(FakeYdl, "result", "https://new?expire=9999999999")
    ytm.stream("ytm:d")
    assert len(FakeYdl.opts) == 2


def test_bot_check_pauses_lookups_for_longer_each_time(monkeypatch):
    ytm = _bare_core()
    now = [1000.0]
    monkeypatch.setattr(core.time, "time", lambda: now[0])
    monkeypatch.setattr(core.yt_dlp, "YoutubeDL", FakeYdl)
    monkeypatch.setattr(FakeYdl, "opts", [])
    bot_check = core.yt_dlp.utils.DownloadError(
        "ERROR: [youtube] abc: Sign in to confirm you\u2019re not a bot. Use --cookies-from-browser or --cookies")
    monkeypatch.setattr(FakeYdl, "result", bot_check)

    with pytest.raises(core.Blocked, match=r"bot check\)\. Try again in 2 min"):
        ytm.stream("ytm:abc")
    # While paused, YouTube isn't asked at all.
    now[0] += 60
    with pytest.raises(core.Blocked, match="Try again in 1 min"):
        ytm.stream("ytm:def")
    assert len(FakeYdl.opts) == 1

    # Still blocked after the pause: twice as long.
    now[0] += 61
    with pytest.raises(core.Blocked, match="Try again in 4 min"):
        ytm.stream("ytm:def")
    assert len(FakeYdl.opts) == 2

    # Working again: the next bot check starts over at the shortest pause.
    now[0] += 4 * 60 + 1
    monkeypatch.setattr(FakeYdl, "result", "https://ok?expire=9999999999")
    assert ytm.stream("ytm:def").url == "https://ok?expire=9999999999"
    monkeypatch.setattr(FakeYdl, "result", bot_check)
    with pytest.raises(core.Blocked, match="Try again in 2 min"):
        ytm.stream("ytm:ghi")


def test_other_failures_dont_pause(monkeypatch):
    ytm = _bare_core()
    monkeypatch.setattr(core.yt_dlp, "YoutubeDL", FakeYdl)
    monkeypatch.setattr(FakeYdl, "result", core.yt_dlp.utils.DownloadError("ERROR: [youtube] abc: Video unavailable"))
    with pytest.raises(core.Unavailable) as e:
        ytm.stream("ytm:abc")
    assert not isinstance(e.value, core.Blocked)
    monkeypatch.setattr(FakeYdl, "result", "https://ok?expire=9999999999")
    assert ytm.stream("ytm:def").url == "https://ok?expire=9999999999"


def test_a_link_with_an_ad_comes_once_the_ad_is_over(monkeypatch):
    ytm = _bare_core()
    now = [1000.0]
    monkeypatch.setattr(core.time, "time", lambda: now[0])
    slept = []
    monkeypatch.setattr(core.time, "sleep", lambda s: slept.append(s))
    monkeypatch.setattr(core.yt_dlp, "YoutubeDL", FakeYdl)
    monkeypatch.setattr(FakeYdl, "result", "https://ad?expire=9999999999")

    monkeypatch.setattr(FakeYdl, "available_at", 1005.0)
    assert ytm.stream("ytm:abc").url == "https://ad?expire=9999999999"
    assert slept == [5.0]

    monkeypatch.setattr(FakeYdl, "available_at", 1000.0 + 5 * 60)
    with pytest.raises(core.Unavailable, match="ad of 300 s"):
        ytm.stream("ytm:def")


def test_po_tokens_ask_as_the_mobile_web_player(monkeypatch):
    monkeypatch.setattr(core.yt_dlp, "YoutubeDL", FakeYdl)
    monkeypatch.setattr(FakeYdl, "result", "https://ok?expire=9999999999")

    monkeypatch.setattr(FakeYdl, "opts", [])
    _bare_core().stream("ytm:abc")
    # Without tokens: only the request whose answer isn't used is skipped.
    assert FakeYdl.opts[0]["extractor_args"] == {"youtube": {"player_skip": ["initial_data"]}}

    # A server with a bgutil server.
    monkeypatch.setattr(FakeYdl, "opts", [])
    server = core.YtmCore(pot_url="http://pot:4416")
    server.stream("ytm:abc")
    args = FakeYdl.opts[0]["extractor_args"]
    # The usual player first (no ads), the mobile web player with tokens for when it's turned away.
    assert args["youtube"]["player_client"] == ["visionos", "mweb"]
    assert args["youtubepot-bgutilhttp"] == {"base_url": ["http://pot:4416"]}

    # The phone, once the app handed over its WebView.
    monkeypatch.setattr(FakeYdl, "opts", [])
    monkeypatch.setattr(core.pot, "_botguard", object())
    _bare_core().stream("ytm:abc")
    assert FakeYdl.opts[0]["extractor_args"]["youtube"]["fetch_pot"] == ["always"]
    assert FakeYdl.opts[0]["extractor_args"]["youtube"]["player_skip"] == ["initial_data"]


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
    song = parse.song_from_watch(track)
    assert song["id"] == "ytm:abc"
    assert song["durationMs"] == 298_000
    assert song["album"] == {"id": "MPRE1", "name": "Discovery"}
    assert song["thumbnails"][-1]["width"] == 544


def test_parse_length():
    assert parse.parse_length("1:02:03") == 3_723_000
    assert parse.parse_length(None) == 0
    assert parse.parse_length("live") == 0


def test_album_summary():
    x = {"title": "Discovery", "browseId": "MPRE1", "year": "2001", "thumbnails": [{"url": "https://lh3/x=w226-h226-l90-rj", "width": 226, "height": 226}]}
    summary = parse.album_summary(x, "Album")
    assert summary["id"] == "MPRE1"
    assert summary["kind"] == "Album"
    assert summary["year"] == "2001"


def test_search_items_of_each_kind():
    thumbs = [{"url": "https://lh3/x=w60-h60-l90-rj", "width": 60, "height": 60}]
    video = parse.search_item({"resultType": "video", "videoId": "v1", "title": "Live", "artists": [], "thumbnails": thumbs})
    assert video["kind"] == "song" and video["video"] is True and video["song"]["id"] == "ytm:v1"
    album = parse.search_item({
        "resultType": "album", "browseId": "MPRE1", "title": "Discovery", "type": "Album", "year": "2001",
        "artists": [{"name": "Daft Punk", "id": "UC1"}], "thumbnails": thumbs,
    })
    assert album["album"]["id"] == "MPRE1" and album["artists"] == [{"id": "UC1", "name": "Daft Punk"}]
    artist = parse.search_item({"resultType": "artist", "artist": "Daft Punk", "browseId": "UC1", "thumbnails": thumbs})
    assert (artist["id"], artist["name"]) == ("UC1", "Daft Punk")
    playlist = parse.search_item({"resultType": "playlist", "browseId": "VLPL1", "title": "Mix", "author": "Jazzy", "itemCount": "12"})
    assert (playlist["id"], playlist["author"], playlist["itemCount"]) == ("VLPL1", "Jazzy", 12)
    assert parse.search_item({"resultType": "profile", "browseId": "UC2", "name": "someone"}) is None


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
    sections = parse.sections_from_all(results, songs, videos)
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
    top = parse.sections_from_all(results, songs, [])[0]["items"][0]["song"]
    assert (top["durationMs"], top["artists"]) == (321_000, [{"id": "UC1", "name": "A"}])


def test_artist_pictures_get_a_large_version_too():
    thumbs = parse.thumbnails([{"url": "https://yt3/x=w120-c-h120-k-c0x00ffffff-no-l90-rj", "width": 120, "height": 120}])
    assert thumbs[-1] == {"url": "https://yt3/x=w544-c-h544-k-c0x00ffffff-no-l90-rj", "width": 544, "height": 544}


def test_suggestions_for_nothing_ask_nobody():
    ytm = core.YtmCore.__new__(core.YtmCore)
    assert ytm.suggestions("  ") == []


def test_episodes_are_songs_of_a_podcast():
    item = parse.search_item({
        "resultType": "episode", "videoId": "e1", "title": "Episode 1", "date": "Oct 18, 2020",
        "podcast": {"id": "MPSP1", "name": "The Show"},
    })
    assert item["kind"] == "song"
    assert item["song"]["podcast"] == {"id": "MPSP1", "name": "The Show"}
    assert item["song"]["artists"] == []
    podcast = parse.search_item({"resultType": "podcast", "browseId": "MPSP1", "title": "The Show"})
    assert (podcast["kind"], podcast["id"]) == ("podcast", "MPSP1")


def test_spoken_lengths():
    assert parse.parse_spoken_length("3 hr 36 min") == (3 * 3600 + 36 * 60) * 1000
    assert parse.parse_spoken_length("29 min") == 29 * 60 * 1000
    assert parse.parse_spoken_length("4:21") == 261_000
    assert parse.parse_spoken_length(None) == 0


def test_view_counts_are_not_dates():
    assert parse.episode_date("860K views") is None
    assert parse.episode_date("Oct 18, 2020") == "Oct 18, 2020"


def test_home_items_of_each_kind():
    song = parse.home_item({"videoId": "abc", "title": "Song", "videoType": "MUSIC_VIDEO_TYPE_ATV", "artists": [{"name": "A", "id": "UC1"}]})
    assert song["kind"] == "song" and song["song"]["id"] == "ytm:abc" and song["video"] is False
    album = parse.home_item({"browseId": "MPREb_x", "title": "Album", "type": "Single", "artists": [{"name": "A", "id": "UC1"}]})
    assert album["kind"] == "album" and album["album"]["kind"] == "Single"
    playlist = parse.home_item({"playlistId": "RDCLAK5uy_x", "title": "Pop Gold", "description": "Alicia Keys, Ed Sheeran"})
    assert playlist == {"kind": "playlist", "id": "RDCLAK5uy_x", "title": "Pop Gold", "author": "Alicia Keys, Ed Sheeran", "itemCount": None, "thumbnails": []}
    assert parse.home_item({"title": "Nothing to open"}) is None


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
    liked = parse.library_playlist({"playlistId": "LM", "title": "Liked music", "count": "1,204"})
    assert liked["kind"] == "playlist" and liked["itemCount"] == 1204
    podcast = parse.library_podcast({"title": "The Daily", "channel": {"id": "UC1", "name": "NYT"}, "browseId": "MPSPPLx", "podcastId": "PLx"})
    assert podcast == {"kind": "podcast", "id": "MPSPPLx", "title": "The Daily", "author": "NYT", "thumbnails": []}
    new = parse.library_podcast({"title": "New Episodes", "channel": {"id": None, "name": "Auto playlist"}, "browseId": "VLRDPN", "podcastId": "RDPN"})
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


class _PlaylistUser:
    """A signed-in YTMusic, as far as playlists and ratings go."""

    def __init__(self):
        self.calls = []

    def get_playlist(self, playlist_id, limit=None):
        track = lambda vid, set_id: {"videoId": vid, "title": vid, "artists": [{"name": "A", "id": None}], "setVideoId": set_id, "duration_seconds": 100}
        return {"title": "Mine", "owned": True, "tracks": [track("aaaaaaaaaaa", "S1"), track("bbbbbbbbbbb", "S2")]}

    def get_watch_playlist(self, videoId, limit):
        return {"tracks": [{"videoId": videoId, "likeStatus": "DISLIKE"}]}

    def rate_song(self, vid, status):
        self.calls.append(("rate", vid, status))

    def remove_playlist_items(self, pid, videos):
        self.calls.append(("remove", pid, videos))
        return "STATUS_SUCCEEDED"

    def edit_playlist(self, pid, title=None, moveItem=None):
        self.calls.append(("edit", pid, title, moveItem))
        return "STATUS_SUCCEEDED"

    def delete_playlist(self, pid):
        self.calls.append(("delete", pid))
        return "STATUS_SUCCEEDED"


def _signed_in_core(monkeypatch):
    ytm = core.YtmCore.__new__(core.YtmCore)
    user = _PlaylistUser()
    monkeypatch.setattr(ytm, "_signed_in", lambda: user, raising=False)
    ytm._ytm = user
    return ytm, user


def test_own_playlists_come_with_their_items(monkeypatch):
    ytm, _ = _signed_in_core(monkeypatch)
    page = ytm.playlist("PLmine", personal=True)
    assert page["owned"] is True
    assert page["itemIds"] == ["S1", "S2"]
    assert [s["id"] for s in page["songs"]] == ["ytm:aaaaaaaaaaa", "ytm:bbbbbbbbbbb"]


def test_own_playlists_come_in_parts(monkeypatch):
    ytm, user = _signed_in_core(monkeypatch)

    # The first part: ytmusicapi stops after it (limit 0) and the token is kept.
    def first_part(playlist_id, limit=None):
        assert limit == 0
        shelf = {"contents": [{"song": 1}, {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "NEXT"}}}}]}
        assert core.ytmusicapi.mixins.playlists.get_continuations_2025(shelf, limit, None, None) == []
        return {"title": "Liked music", "owned": False, "trackCount": 250, "tracks": [{"videoId": "aaaaaaaaaaa", "title": "A", "artists": [], "duration_seconds": 100}]}

    monkeypatch.setattr(user, "get_playlist", first_part)
    page = ytm.playlist("LM", personal=True)
    assert (page["more"], page["songCount"], page["itemIds"]) == ("NEXT", 250, [""])

    # The next part: only its songs, with their places, and no token after the last part.
    sent = []
    monkeypatch.setattr(user, "_send_request", lambda endpoint, body: sent.append(body) or {"onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": ["x"]}}]}, raising=False)
    monkeypatch.setattr(core, "parse_playlist_items", lambda items: [{"videoId": "bbbbbbbbbbb", "title": "B", "artists": [], "setVideoId": "S9", "duration_seconds": 90}])
    monkeypatch.setattr(core, "get_continuation_token", lambda items: None)
    more = ytm.playlist("LM", personal=True, more="NEXT")
    assert sent == [{"continuation": "NEXT"}]
    assert ([s["id"] for s in more["songs"]], more["itemIds"], more["more"]) == (["ytm:bbbbbbbbbbb"], ["S9"], None)


def test_public_playlists_come_in_parts_too(monkeypatch):
    ytm, user = _signed_in_core(monkeypatch)
    sent = []

    def first_part(playlist_id, limit=None):
        assert limit == 0
        shelf = {"contents": [{"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "NEXT"}}}}]}
        core.ytmusicapi.mixins.playlists.get_continuations_2025(shelf, limit, None, None)
        return {"title": "Big", "trackCount": 900, "tracks": [{"videoId": "aaaaaaaaaaa", "title": "A", "artists": [], "duration_seconds": 100}]}

    monkeypatch.setattr(user, "get_playlist", first_part)
    page = ytm.playlist("PLbig")
    assert (page["more"], page["songCount"], page["owned"]) == ("NEXT", 900, False)

    monkeypatch.setattr(user, "_send_request", lambda endpoint, body: sent.append(body) or {"onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": ["x"]}}]}, raising=False)
    monkeypatch.setattr(core, "parse_playlist_items", lambda items: [{"videoId": "bbbbbbbbbbb", "title": "B", "artists": [], "setVideoId": "S9", "duration_seconds": 90}])
    monkeypatch.setattr(core, "get_continuation_token", lambda items: None)
    more = ytm.playlist("PLbig", more="NEXT")
    # Not yours: no places in it.
    assert (sent, [s["id"] for s in more["songs"]], more["itemIds"]) == ([{"continuation": "NEXT"}], ["ytm:bbbbbbbbbbb"], [])


def test_a_playlist_youtube_wont_show_says_so_plainly():
    import pytest

    class _SignedOut:
        def get_playlist(self, playlist_id, limit=None):
            # What ytmusicapi raises for Liked music without a sign-in.
            raise KeyError("Unable to find 'twoColumnBrowseResultsRenderer' using path [...]")

    ytm = core.YtmCore.__new__(core.YtmCore)
    ytm._ytm = _SignedOut()
    with pytest.raises(core.NotFound, match=r"^Couldn't find this playlist"):
        ytm.playlist("LM")


def test_editing_an_own_playlist(monkeypatch):
    ytm, user = _signed_in_core(monkeypatch)
    ytm.remove_from_playlist("PLmine", [{"songId": "ytm:aaaaaaaaaaa", "itemId": "S1"}])
    ytm.move_in_playlist("PLmine", "S2", "S1")
    ytm.move_in_playlist("PLmine", "S1", None)
    ytm.rename_playlist("PLmine", " New <name> ")
    ytm.delete_playlist("PLmine")
    assert user.calls == [
        ("remove", "PLmine", [{"videoId": "aaaaaaaaaaa", "setVideoId": "S1"}]),
        ("edit", "PLmine", None, ("S2", "S1")),
        ("edit", "PLmine", None, "S1"),
        ("edit", "PLmine", "New name", None),
        ("delete", "PLmine"),
    ]


def test_dislikes(monkeypatch):
    ytm, user = _signed_in_core(monkeypatch)
    assert ytm.rating("ytm:aaaaaaaaaaa") == "dislike"
    assert ytm.liked("ytm:aaaaaaaaaaa") is False
    ytm.set_rating("ytm:aaaaaaaaaaa", "dislike")
    ytm.set_rating("ytm:aaaaaaaaaaa", "none")
    assert [c[2].name for c in user.calls] == ["DISLIKE", "INDIFFERENT"]


class _LyricsYtm:
    """YTMusic as far as lyrics go: [timed] lyrics, untimed ones, or none."""

    def __init__(self, kind):
        self.kind = kind

    def get_watch_playlist(self, videoId, limit):
        return {"lyrics": None if self.kind == "none" else "MPLYt_x"}

    def get_lyrics(self, browse_id, timestamps=False):
        from types import SimpleNamespace as Line
        if self.kind == "timed":
            return {"lyrics": [Line(text="One more time", start_time=1200), Line(text="We're gonna celebrate", start_time=3400)],
                    "source": "Source: LyricFind", "hasTimestamps": True}
        return {"lyrics": "One more time\nWe're gonna celebrate", "source": "Source: Musixmatch", "hasTimestamps": False}


def test_lyrics_come_synced_when_youtube_music_has_them_timed():
    import pytest

    ytm = core.YtmCore.__new__(core.YtmCore)
    ytm._ytm = _LyricsYtm("timed")
    assert ytm.lyrics("ytm:abcdefghijk") == {
        "plain": "One more time\nWe're gonna celebrate",
        "synced": [{"timeMs": 1200, "text": "One more time"}, {"timeMs": 3400, "text": "We're gonna celebrate"}],
        "source": "YouTube Music (LyricFind)",
    }
    ytm._ytm = _LyricsYtm("plain")
    assert ytm.lyrics("ytm:abcdefghijk") == {"plain": "One more time\nWe're gonna celebrate", "synced": None, "source": "YouTube Music (Musixmatch)"}
    ytm._ytm = _LyricsYtm("none")
    with pytest.raises(core.NotFound):
        ytm.lyrics("ytm:abcdefghijk")


class FakeAccount:
    """A signed-in YouTube Music account with two podcasts' new episodes."""

    def __init__(self):
        self.rated = []

    def get_episodes_playlist(self, playlist_id):
        assert playlist_id == "RDPN"
        return {"episodes": [
            {"videoId": "ep1", "title": "Episode one", "duration": "25 min", "thumbnails": []},
            {"videoId": "ep2", "title": "Episode two", "duration": "1 hr 5 min", "thumbnails": []},
        ]}

    def get_home(self, limit):
        return [{"title": "Quick picks", "contents": [{"videoId": "song1", "title": "A song", "artists": [{"name": "Band", "id": None}], "thumbnails": []}]}]

    def get_podcast(self, podcast_id):
        return {"title": "My podcast", "author": {"name": "Me"}, "saved": True, "episodes": [], "thumbnails": []}

    def rate_playlist(self, playlist_id, rating):
        self.rated.append((playlist_id, rating))


class FakeAnonymous:
    lookups = 0

    def get_episode(self, video_id):
        FakeAnonymous.lookups += 1
        if video_id == "ep2":
            raise Exception("gone")
        return {"author": {"name": "Talk show", "id": "MPSPtalk"}}


def test_new_episodes_come_first_on_your_home_with_their_podcast():
    ytm = core.YtmCore()
    ytm._user = FakeAccount()
    ytm._ytm = FakeAnonymous()
    core._podcast_of_episode.clear()
    home = ytm.home(personal=True)
    assert [s["title"] for s in home["sections"]] == ["New episodes", "Quick picks"]
    one, two = [i["song"] for i in home["sections"][0]["items"]]
    assert one["podcast"] == {"id": "MPSPtalk", "name": "Talk show"}
    assert one["durationMs"] == 25 * 60_000
    # Its podcast couldn't be found: still an episode, so no music radio follows it.
    assert two["podcast"] == core._UNKNOWN_PODCAST
    # The podcast of an episode is looked up once.
    lookups = FakeAnonymous.lookups
    ytm.new_episodes()
    assert FakeAnonymous.lookups == lookups + 1  # only the one that failed is tried again


def test_subscribing_rates_the_podcasts_playlist():
    ytm = core.YtmCore()
    account = FakeAccount()
    ytm._user = account
    assert ytm.podcast("MPSPabc", personal=True)["saved"] is True
    ytm.set_podcast_saved("MPSPabc", True)
    ytm.set_podcast_saved("MPSPabc", False)
    assert account.rated == [("abc", core.LikeStatus.LIKE), ("abc", core.LikeStatus.INDIFFERENT)]


class _LinkYtm:
    """YouTube Music as far as links need it: a watch playlist per video, album IDs by playlist."""

    def get_watch_playlist(self, videoId, limit):
        if videoId == "gone":
            raise Exception("Video unavailable")
        kind = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" if videoId == "episode" else "MUSIC_VIDEO_TYPE_ATV"
        return {"tracks": [{"videoId": videoId, "title": f"Title {videoId}", "artists": [{"name": "Someone", "id": "UCx"}],
                            "album": {"name": "Album", "id": "MPREb_x"}, "length": "3:05", "thumbnail": [], "videoType": kind},
                           {"videoId": "other", "title": "Next"}]}

    def get_episode(self, video_id):
        return {"author": {"id": "MPSPtalk", "name": "Talk show"}}

    def get_album_browse_id(self, playlist_id):
        return "MPREb_fromlink" if playlist_id == "OLAK5uy_abc" else None

    def get_album(self, browse_id):
        return {"title": f"Album {browse_id}", "tracks": [], "artists": []}


def test_a_song_by_its_link():
    import pytest

    ytm = core.YtmCore.__new__(core.YtmCore)
    ytm._ytm = _LinkYtm()
    core._podcast_of_episode.clear()
    song = ytm.song("ytm:abc")
    assert song["id"] == "ytm:abc"
    assert song["title"] == "Title abc"
    assert song["durationMs"] == 185_000
    assert song["album"] == {"id": "MPREb_x", "name": "Album"}
    assert "podcast" not in song
    # An episode stays one, with its podcast.
    assert ytm.song("ytm:episode")["podcast"] == {"id": "MPSPtalk", "name": "Talk show"}
    with pytest.raises(core.NotFound):
        ytm.song("ytm:gone")


def test_an_album_by_its_playlist_id():
    ytm = core.YtmCore.__new__(core.YtmCore)
    ytm._ytm = _LinkYtm()
    album = ytm.album("OLAK5uy_abc")
    assert album["id"] == "MPREb_fromlink"
    assert album["title"] == "Album MPREb_fromlink"


def test_history_says_about_when_each_song_was_played(monkeypatch):
    ytm, user = _signed_in_core(monkeypatch)

    def get_history():
        return [
            {"videoId": "aaaaaaaaaaa", "title": "A", "artists": [], "duration_seconds": 100, "played": "Today"},
            {"videoId": "bbbbbbbbbbb", "title": "B", "artists": [], "duration_seconds": 100, "played": "Yesterday"},
            {"videoId": "ccccccccccc", "title": "C", "artists": [], "duration_seconds": 100, "played": "Hier"},
        ]

    monkeypatch.setattr(user, "get_history", get_history, raising=False)
    played = ytm.history()
    assert [(p["song"]["id"], p["daysAgo"]) for p in played] == [("ytm:aaaaaaaaaaa", 0), ("ytm:bbbbbbbbbbb", 1), ("ytm:ccccccccccc", None)]


def test_history_labels_become_days():
    # Thursday 23 October 2025 (tm_wday 3).
    today = time.strptime("2025-10-23", "%Y-%m-%d")
    assert parse.days_ago("Today", today) == 0
    assert parse.days_ago("Yesterday", today) == 1
    assert parse.days_ago("Monday", today) == 3
    assert parse.days_ago("Thursday", today) == 7
    assert parse.days_ago("This week", today) == 3
    assert parse.days_ago("Last week", today) == 7
    assert parse.days_ago("October", today) == 8
    assert parse.days_ago("September", today) == 38
    assert parse.days_ago("November", today) == 11 * 30 + 8
    assert parse.days_ago("March 2024", today) == 19 * 30 + 8
    assert parse.days_ago("Vandaag", today) is None
    assert parse.days_ago(None, today) is None


def test_artist_page_with_another_header(monkeypatch):
    """YouTube sends some visitors an artist page without musicImmersiveHeaderRenderer: ytmusicapi
    fails on it ('header'), the page is read from whichever header it has."""
    import json
    import pathlib

    page = json.loads((pathlib.Path(__file__).parent / "data" / "artist_other_header.json").read_text())
    c = core.YtmCore()

    def broken(_id):
        raise KeyError("header")

    monkeypatch.setattr(c._ytm, "get_artist", broken)
    monkeypatch.setattr(c._ytm, "_send_request", lambda endpoint, body, *a, **k: page)
    a = c.artist("UCqH54i8-ZP829SqRLGT7X-Q")
    assert a["name"] == "Alexandria"
    assert a["thumbnails"]
    assert a["songs"]
    assert a["albums"] or a["singles"]


def test_unreadable_pages_are_kept(monkeypatch, tmp_path):
    """A page that didn't look as expected is written to a file (the newest few), to make a test from."""
    import json

    from ytmp_ytm import keep

    keep.keep_in(str(tmp_path))
    page = {"contents": {"something": "new"}}
    monkeypatch.setattr(core.YTMusic, "_send_request", lambda self, endpoint, body, *a, **k: page, raising=False)
    c = core.YtmCore()
    with pytest.raises(core.NotFound):
        c.album("MPREb_x")
    kept = keep.kept()
    assert len(kept) == 1
    saved = json.loads(kept[0].read_text())
    assert saved["what"] == "album" and saved["id"] == "MPREb_x"
    assert saved["pages"][-1]["response"] == page
    # A genuine "not there" isn't kept.
    assert not keep.unreadable(core.NotFound("No such album"))
    keep.keep_in(None)
