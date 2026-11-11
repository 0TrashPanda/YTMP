"""yt-dlp updating itself (update.py), against a fake PyPI on localhost: no real downloads."""

import hashlib
import http.server
import io
import json
import subprocess
import sys
import threading
import zipfile
from pathlib import Path

import pytest
import yt_dlp

from ytmp_ytm import update

BUNDLED = yt_dlp.version.__version__
NEWER = "2099.1.1"


def _wheel(files: dict[str, str]) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, text in files.items():
            archive.writestr(name, text)
    return out.getvalue()


@pytest.fixture(scope="session")
def real_yt_dlp_files() -> dict[str, str]:
    """The bundled yt-dlp's own files, to make a "newer" one that really works."""
    root = Path(yt_dlp.__file__).parent
    return {
        f"yt_dlp/{path.relative_to(root)}": path.read_text(encoding="utf-8")
        for path in root.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts and path.suffix in (".py", ".js")
    }


def _working(version: str, files: dict[str, str]) -> bytes:
    files = dict(files)
    files["yt_dlp/version.py"] = files["yt_dlp/version.py"].replace(f"'{BUNDLED}'", f"'{version}'")
    files[f"yt_dlp-{version}.dist-info/METADATA"] = f"Metadata-Version: 2.1\nName: yt-dlp\nVersion: {version}\n"
    return _wheel(files)


def _broken(version: str) -> bytes:
    return _wheel({
        "yt_dlp/__init__.py": "raise ImportError('broken on purpose')\n",
        f"yt_dlp-{version}.dist-info/METADATA": f"Metadata-Version: 2.1\nName: yt-dlp\nVersion: {version}\n",
    })


class FakePypi:
    """PyPI's JSON API and its files: yt-dlp's newest release, and yt-dlp-ejs by version."""

    def __init__(self):
        self.files: dict[str, bytes] = {}
        self.json: dict[str, dict] = {}
        self.requests: list[str] = []
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                fake.requests.append(self.path)
                if self.path in fake.json:
                    body = json.dumps(fake.json[self.path]).encode()
                elif self.path in fake.files:
                    body = fake.files[self.path]
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def release(self, package: str, version: str, wheel: bytes, requires: list[str] | None = None,
                sha256: str | None = None, path: str | None = None) -> None:
        filename = f"{package.replace('-', '_')}-{version}-py3-none-any.whl"
        self.files[f"/files/{filename}"] = wheel
        self.json[path or f"/pypi/{package}/json"] = {
            "info": {"name": package, "version": version, "requires_dist": requires or []},
            "urls": [
                {"packagetype": "sdist", "filename": f"{package}-{version}.tar.gz", "url": f"{self.url}/nope", "digests": {"sha256": "0"}},
                {"packagetype": "bdist_wheel", "filename": filename, "url": f"{self.url}/files/{filename}",
                 "digests": {"sha256": sha256 or hashlib.sha256(wheel).hexdigest()}},
            ],
        }

    def downloads(self) -> int:
        return sum(1 for r in self.requests if r.startswith("/files/"))


@pytest.fixture
def pypi():
    fake = FakePypi()
    yield fake
    fake.server.shutdown()


@pytest.fixture
def folder(tmp_path):
    update._reset_for_tests()
    # In these tests yt-dlp is already imported: activate only sets the folder.
    update.activate(tmp_path)
    yield tmp_path
    update._reset_for_tests()


def _state(folder: Path) -> dict:
    return json.loads((folder / "yt-dlp.json").read_text())


def _activate_in_new_python(folder: Path) -> dict:
    """activate() in a fresh Python (nothing imported yet), like the app's start."""
    code = (
        "import json, sys\n"
        "from ytmp_ytm import update\n"
        f"used = update.activate({str(folder)!r})\n"
        "import yt_dlp\n"
        "print(json.dumps({'used': used, 'version': yt_dlp.version.__version__, 'file': yt_dlp.__file__,"
        " 'describe': update.describe(), 'notes': [m for _, m in update.take_notes()]}))\n"
    )
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=120, check=True)
    return json.loads(out.stdout.strip().splitlines()[-1])


def test_downloads_a_newer_yt_dlp_once(pypi, folder):
    pypi.release("yt-dlp", NEWER, _broken(NEWER))
    assert update.check(pypi.url) == NEWER
    assert _state(folder)["ready"] == NEWER
    assert (folder / "yt-dlp" / NEWER / "yt_dlp" / "__init__.py").is_file()
    assert update.check(pypi.url) is None
    assert pypi.downloads() == 1


def test_nothing_newer(pypi, folder):
    pypi.release("yt-dlp", BUNDLED, _broken(BUNDLED))
    assert update.check(pypi.url) is None
    assert pypi.downloads() == 0
    assert _state(folder)["checked"] > 0


def test_pre_releases_are_skipped(pypi, folder):
    pypi.release("yt-dlp", "2099.1.1.dev0", _broken(NEWER))
    assert update.check(pypi.url) is None


def test_wrong_sha256_is_refused(pypi, folder):
    pypi.release("yt-dlp", NEWER, _broken(NEWER), sha256="ab" * 32)
    with pytest.raises(ValueError, match="sha256"):
        update.check(pypi.url)
    assert not _state(folder).get("ready")
    assert list((folder / "yt-dlp").iterdir()) == []


def test_a_wheel_with_files_outside_its_folder_is_refused(pypi, folder):
    pypi.release("yt-dlp", NEWER, _wheel({"../evil.py": "x = 1\n"}))
    with pytest.raises(ValueError, match="outside"):
        update.check(pypi.url)
    assert not (folder / "evil.py").exists()


def test_a_version_that_failed_is_not_downloaded_again(pypi, folder):
    (folder / "yt-dlp.json").write_text(json.dumps({"bad": [NEWER]}))
    pypi.release("yt-dlp", NEWER, _broken(NEWER))
    assert update.check(pypi.url) is None
    assert pypi.downloads() == 0


def test_brings_the_yt_dlp_ejs_it_pins(pypi, folder):
    pypi.release("yt-dlp", NEWER, _broken(NEWER), requires=["requests>=2", "yt-dlp-ejs==9.9.9; extra == 'default'"])
    pypi.release("yt-dlp-ejs", "9.9.9", _wheel({"yt_dlp_ejs/__init__.py": "version = '9.9.9'\n"}),
                 path="/pypi/yt-dlp-ejs/9.9.9/json")
    assert update.check(pypi.url) == NEWER
    assert (folder / "yt-dlp" / NEWER / "yt_dlp_ejs" / "__init__.py").is_file()


def test_the_same_yt_dlp_ejs_is_not_downloaded(pypi, folder):
    from importlib.metadata import version

    pypi.release("yt-dlp", NEWER, _broken(NEWER), requires=[f"yt-dlp-ejs=={version('yt-dlp-ejs')}"])
    assert update.check(pypi.url) == NEWER
    assert pypi.downloads() == 1


def test_the_downloaded_yt_dlp_is_used_at_the_next_start(pypi, folder, real_yt_dlp_files):
    pypi.release("yt-dlp", NEWER, _working(NEWER, real_yt_dlp_files))
    update.check(pypi.url)
    started = _activate_in_new_python(folder)
    assert started["used"] == NEWER
    assert started["version"] == NEWER
    assert started["file"].startswith(str(folder))
    assert started["describe"] == f"{NEWER} (downloaded; bundled {BUNDLED})"


def test_one_that_does_not_load_falls_back_and_is_not_tried_again(pypi, folder):
    pypi.release("yt-dlp", NEWER, _broken(NEWER))
    update.check(pypi.url)
    started = _activate_in_new_python(folder)
    assert started["used"] is None
    assert started["version"] == BUNDLED
    assert "doesn't load" in started["notes"][0]
    state = _state(folder)
    assert state["bad"] == [NEWER] and not state["ready"]
    assert not (folder / "yt-dlp" / NEWER).exists()
    assert update.check(pypi.url) is None


def test_one_the_module_cannot_use_falls_back_too(pypi, folder, real_yt_dlp_files):
    # yt-dlp itself imports, but jsc.py builds on a part it no longer has.
    files = {k: v for k, v in real_yt_dlp_files.items() if not k.endswith("jsc/_builtin/ejs.py")}
    pypi.release("yt-dlp", NEWER, _working(NEWER, files))
    update.check(pypi.url)
    started = _activate_in_new_python(folder)
    assert started["used"] is None and started["version"] == BUNDLED
    assert not started["file"].startswith(str(folder))
    assert _state(folder)["bad"] == [NEWER]


def test_a_newer_bundled_yt_dlp_wins(folder, real_yt_dlp_files):
    old = "2000.1.1"
    target = folder / "yt-dlp" / old
    with zipfile.ZipFile(io.BytesIO(_working(old, real_yt_dlp_files))) as archive:
        archive.extractall(target)
    (folder / "yt-dlp.json").write_text(json.dumps({"ready": old}))
    started = _activate_in_new_python(folder)
    assert started["used"] is None and started["version"] == BUNDLED
    assert not target.exists()


def test_first_lookups_failing_goes_back_to_the_bundled_one(folder):
    (folder / "yt-dlp" / NEWER).mkdir(parents=True)
    (folder / "yt-dlp.json").write_text(json.dumps({"ready": NEWER}))
    update._active = NEWER
    for _ in range(update.FIRST_FAILURES - 1):
        update.lookup_failed()
    assert not _state(folder).get("bad")
    update.lookup_failed()
    state = _state(folder)
    assert state["bad"] == [NEWER] and not state["ready"]
    # Still in use until the next start.
    assert (folder / "yt-dlp" / NEWER).exists()


def test_one_that_worked_is_trusted(folder):
    (folder / "yt-dlp.json").write_text(json.dumps({"ready": NEWER}))
    update._active = NEWER
    update.lookup_ok()
    assert _state(folder)["proven"] == [NEWER]
    for _ in range(update.FIRST_FAILURES * 2):
        update.lookup_failed()
    assert not _state(folder).get("bad")
    assert _state(folder)["ready"] == NEWER


def test_the_bundled_one_is_never_marked(folder):
    for _ in range(update.FIRST_FAILURES):
        update.lookup_failed()
    update.lookup_ok()
    assert not (folder / "yt-dlp.json").exists()
