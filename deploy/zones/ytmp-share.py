#!/usr/bin/env python3
"""What the Raspberry Pi next to an amp shares with YTMP, on one port (see docs/implementation/zones.md):

    /ytmp-zones.json      the zones file (and any other file in --dir)
    /music/<path>         MPD's music folder, with ranges (so players can seek)
    /art/<path>?size=N    a song's cover, from MPD (embedded picture or cover file), shrunk to N px
                          when python3-pil is installed

Only Python's standard library is needed (and python3-pil for small covers).

    ytmp-share.py --dir /etc/ytmp-zones --music /home/jonah/Music --port 8766
"""
import argparse, collections, io, mimetypes, os, re, socket, threading, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from PIL import Image
except ImportError:
    Image = None

ARGS = None
ART_CACHE = collections.OrderedDict()
ART_CACHE_SIZE = 400
ART_LOCK = threading.Lock()


def mpd_picture(uri):
    """The song's cover as (bytes, type) from MPD: its embedded picture, else a cover file in its folder."""
    host, _, port = ARGS.mpd.rpartition(":")
    quoted = '"' + uri.replace("\\", "\\\\").replace('"', '\\"') + '"'
    with socket.create_connection((host, int(port)), timeout=10) as s:
        f = s.makefile("rb")
        f.readline()  # OK MPD …
        for command in ("readpicture", "albumart"):
            data, offset, kind = bytearray(), 0, None
            while True:
                s.sendall(f"{command} {quoted} {offset}\n".encode())
                size = binary = None
                while True:
                    line = f.readline().decode("utf-8", "replace").rstrip("\n")
                    if line.startswith("ACK") or line == "OK":
                        break
                    key, _, value = line.partition(": ")
                    if key == "size": size = int(value)
                    elif key == "type": kind = value
                    elif key == "binary":
                        binary = int(value)
                        data += f.read(binary)
                        f.readline()  # the newline after the data
                if not binary or size is None or len(data) >= size:
                    break
                offset = len(data)
            if data:
                return bytes(data), kind or mimetypes.guess_type("x.jpg")[0]
    return None


def cover(uri, size):
    key = (uri, size)
    with ART_LOCK:
        if key in ART_CACHE:
            ART_CACHE.move_to_end(key)
            return ART_CACHE[key]
    found = mpd_picture(uri)
    if found and Image and size:
        try:
            image = Image.open(io.BytesIO(found[0])).convert("RGB")
            image.thumbnail((size, size))
            out = io.BytesIO()
            image.save(out, "JPEG", quality=85)
            found = (out.getvalue(), "image/jpeg")
        except Exception:
            pass
    with ART_LOCK:
        ART_CACHE[key] = found
        while len(ART_CACHE) > ART_CACHE_SIZE:
            ART_CACHE.popitem(last=False)
    return found


class Handler(BaseHTTPRequestHandler):
    server_version = "ytmp-share"

    def do_HEAD(self):
        self.do_GET(head=True)

    def do_GET(self, head=False):
        url = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(url.path)
        try:
            if path.startswith("/music/") and ARGS.music:
                return self.send_file(ARGS.music, path[len("/music/"):], head)
            if path.startswith("/art/") and ARGS.mpd:
                size = urllib.parse.parse_qs(url.query).get("size", ["0"])[0]
                found = cover(path[len("/art/"):], int(size) if size.isdigit() else 0)
                if not found:
                    return self.send_error(404)
                return self.send_bytes(found[0], found[1], head, cache=True)
            return self.send_file(ARGS.dir, path.lstrip("/"), head)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except OSError as e:
            self.send_error(502, str(e))

    def send_bytes(self, data, kind, head, cache=False):
        self.send_response(200)
        self.send_header("Content-Type", kind or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        if cache:
            self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        if not head:
            self.wfile.write(data)

    def send_file(self, root, relative, head):
        root = os.path.realpath(root)
        full = os.path.realpath(os.path.join(root, relative))
        if not full.startswith(root + os.sep) or not os.path.isfile(full):
            return self.send_error(404)
        total = os.path.getsize(full)
        start, end = 0, total - 1
        match = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", "").strip())
        if match and total:
            if match[1]:
                start, end = int(match[1]), int(match[2]) if match[2] else total - 1
            elif match[2]:
                start = max(0, total - int(match[2]))
            end = min(end, total - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total}")
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(full)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        if head:
            return
        with open(full, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(64 * 1024, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def log_message(self, format, *args):
        pass


def main():
    global ARGS
    parser = argparse.ArgumentParser(description="Shares the zones file, the music folder and covers with YTMP.")
    parser.add_argument("--dir", default="/etc/ytmp-zones", help="folder with ytmp-zones.json")
    parser.add_argument("--music", help="MPD's music_directory")
    parser.add_argument("--mpd", default="localhost:6600", help="MPD, for the covers")
    parser.add_argument("--port", type=int, default=8766)
    ARGS = parser.parse_args()
    mimetypes.add_type("audio/mp4", ".m4a")
    mimetypes.add_type("audio/flac", ".flac")
    mimetypes.add_type("audio/ogg", ".ogg")
    ThreadingHTTPServer(("", ARGS.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
