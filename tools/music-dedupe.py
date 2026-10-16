#!/usr/bin/env python3
"""Finds copies of the same song in MPD's music folder and moves the extra ones out of it.

A copy has the same artist (album artist), album, disc, track number and title, and about
the same length (within 2 s), like YTMP's Local tab counts them. Of each song it keeps one:
the one outside nested folders (fewest folders deep), then the shortest path. The others
are moved, not deleted, to --to (keeping their paths), so you can check and bring any back.
Songs without a title in their tags are left alone (they can't be told apart).

Only needs Python and MPD (it reads the songs and tags from MPD). On the Pi:

    python3 music-dedupe.py                      # shows what it would move
    python3 music-dedupe.py --move               # moves them, then MPD updates its database

    --music  MPD's music_directory (default ~/Music)
    --to     where the copies go (default ~/Music-duplicates)
    --mpd    MPD's address (default localhost:6600)
"""
import argparse, collections, os, shutil, socket, sys


def mpd_songs(address):
    """Every song in MPD's database, with its tags (listallinfo)."""
    host, _, port = address.rpartition(":")
    with socket.create_connection((host or "localhost", int(port)), timeout=30) as s:
        f = s.makefile("rb")
        if not f.readline().startswith(b"OK MPD"):
            sys.exit(f"No MPD at {address}")
        s.sendall(b"listallinfo\n")
        songs, current = [], None
        while True:
            line = f.readline().decode("utf-8", "replace").rstrip("\n")
            if line == "OK":
                return songs
            if line.startswith("ACK") or not line:
                sys.exit(f"MPD said: {line or 'nothing'}")
            key, _, value = line.partition(": ")
            if key == "file":
                current = {"file": value}
                songs.append(current)
            elif key in ("directory", "playlist"):
                current = None
            elif current is not None:
                current.setdefault(key, value)


def mpd_update(address):
    host, _, port = address.rpartition(":")
    with socket.create_connection((host or "localhost", int(port)), timeout=30) as s:
        f = s.makefile("rb")
        f.readline()
        s.sendall(b"update\n")
        print("MPD:", f.readline().decode().strip(), f.readline().decode().strip())


def number(text):
    try:
        return int((text or "").split("/")[0])
    except ValueError:
        return None


def same_song(song):
    """What makes two files the same song; None when the tags can't tell."""
    title = (song.get("Title") or "").strip().lower()
    if not title or "://" in song["file"]:
        return None
    artist = (song.get("AlbumArtist") or song.get("Artist") or "").strip().lower()
    length = float(song.get("duration") or song.get("Time") or 0)
    return (artist, (song.get("Album") or "").strip().lower(), number(song.get("Disc")), number(song.get("Track")), title, round(length / 2))


def keep_first(paths):
    """The one to keep first: the fewest folders deep, then the shortest path."""
    return sorted(paths, key=lambda p: (p.count("/"), len(p), p))


def main():
    parser = argparse.ArgumentParser(description="Moves copies of the same song out of MPD's music folder.")
    parser.add_argument("--music", default=os.path.expanduser("~/Music"), help="MPD's music_directory")
    parser.add_argument("--to", default=os.path.expanduser("~/Music-duplicates"), help="where the copies go")
    parser.add_argument("--mpd", default="localhost:6600", help="MPD's address")
    parser.add_argument("--move", action="store_true", help="really move them (without it: only show what it would do)")
    args = parser.parse_args()

    songs = mpd_songs(args.mpd)
    groups = collections.defaultdict(list)
    for song in songs:
        key = same_song(song)
        if key:
            groups[key].append(song["file"])

    plan = []  # (keep, [copies])
    for paths in groups.values():
        if len(paths) > 1:
            keep, *copies = keep_first(paths)
            plan.append((keep, copies))
    plan.sort()
    copies = sum(len(c) for _, c in plan)
    print(f"{len(songs)} files, {len(groups)} different songs with titles; {copies} copies of {len(plan)} songs.\n")
    for keep, extra in plan:
        print(f"keep  {keep}")
        for path in extra:
            print(f"  move {path}")
    if not plan:
        return
    if not args.move:
        print(f"\nNothing moved. To move these {copies} copies to {args.to}: run again with --move")
        return

    moved = missing = 0
    for _, extra in plan:
        for path in extra:
            source = os.path.join(args.music, path)
            target = os.path.join(args.to, path)
            if not os.path.isfile(source):
                missing += 1
                print(f"not found (skipped): {source}")
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.move(source, target)
            moved += 1
    # Folders left empty (only pictures and playlists of the moved songs stay behind).
    for folder, _, _ in sorted(os.walk(args.music), key=lambda w: -w[0].count(os.sep)):
        if folder != args.music and not os.listdir(folder):
            os.rmdir(folder)
    print(f"\nMoved {moved} copies to {args.to}" + (f" ({missing} not found)" if missing else "") + ".")
    mpd_update(args.mpd)


if __name__ == "__main__":
    main()
