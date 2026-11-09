from ytmp_ytm.recognize import best_song


def song(title, *artists):
    return {"id": f"ytm:{title}", "title": title, "artists": [{"id": None, "name": a} for a in artists]}


def test_same_title_and_artist_wins_over_the_first_result():
    found = {"title": "Digital World", "artist": "Amaranthe"}
    songs = [song("Digital World (Cover)", "Someone"), song("Digital World", "Amaranthe")]
    assert best_song(found, songs)["id"] == "ytm:Digital World"


def test_feat_and_accents_and_remaster_dont_matter():
    found = {"title": "Pilé (feat. Someone)", "artist": "Aya Nakamura"}
    assert best_song(found, [song("Pile - Remastered 2020", "Aya Nakamura")]) is not None


def test_a_song_with_another_title_is_not_the_one():
    found = {"title": "Rise of the Chaos Wizards", "artist": "Gloryhammer"}
    assert best_song(found, [song("Universe on Fire", "Gloryhammer")]) is None


def test_artist_named_like_shazam_names_several():
    found = {"title": "Lose Control", "artist": "Teddy Swims & Someone"}
    songs = [song("Lose Control", "Missy Elliott"), song("Lose Control", "Teddy Swims")]
    assert best_song(found, songs)["id"] == "ytm:Lose Control"
    # Both are called Lose Control: the one by the right artist, even second.
    assert best_song(found, songs) is songs[1]
