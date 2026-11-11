"""Puts the YouTube Music module in the desktop app: a standalone Python with ytm-module
installed, and Deno (yt-dlp's JavaScript runtime), in src-tauri/resources/.

    python desktop/scripts/bundle_module.py            # for this computer (Linux or Windows)

A plain Python, not PyInstaller: yt-dlp updates itself into a folder in front on sys.path
(ytmp_ytm/update.py), which a frozen archive would win from, and PyInstaller's starter is often
taken for a virus on Windows. Downloads are pinned and checked against their SHA-256.
"""

from __future__ import annotations

import hashlib
import io
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "desktop" / "src-tauri" / "resources"

# python-build-standalone (https://github.com/astral-sh/python-build-standalone), CPython 3.13.
PYTHON_RELEASE = "20261021"
PYTHON_VERSION = "3.13.17"
PYTHON = {
    "linux": ("x86_64-unknown-linux-gnu", "f829adc9d888e76c8801b4799ed444188d35239fcae3a9808a99ec5cb0d8d92c"),
    "windows": ("x86_64-pc-windows-msvc", "56cde3b1a1dcc36576753cb408ee0c3568d211baafa0eee40423adc147c480f2"),
}
DENO_VERSION = "2.9.8"
DENO = {
    "linux": ("x86_64-unknown-linux-gnu", "e890975bc2549807bbcc0b584ffafda72cc7c93349dd99c80483dd784f6c4609"),
    "windows": ("x86_64-pc-windows-msvc", "cba84b26e0df5ae7f74fc66b86a95b866872a86292ebd84fa1e959927edd93bf"),
}
# Parts of the standard library the module never uses: smaller app.
DROP = ["test", "idlelib", "tkinter", "turtledemo", "ensurepip", "lib2to3", "pydoc_data"]


def download(url: str, sha256: str) -> bytes:
    print(f"downloading {url}", flush=True)
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    got = hashlib.sha256(data).hexdigest()
    if got != sha256:
        sys.exit(f"{url}: SHA-256 is {got}, expected {sha256}")
    return data


def trim(python: Path, system: str) -> None:
    """Leaves out what only an interactive Python needs: Tk, headers, man pages, scripts."""
    if system == "windows":
        gone = ["tcl", "include", "Scripts", "libs", "DLLs/_tkinter.pyd", "DLLs/tcl86t.dll", "DLLs/tk86t.dll"]
    else:
        # bin/python3.13 has Python built in; extensions don't link libpython on Linux.
        gone = ["include", "share", "lib/pkgconfig", "lib/libpython3.13.so", "lib/libpython3.13.so.1.0", "lib/libpython3.so"]
        gone += [str(p.relative_to(python)) for p in (python / "lib").glob("*")
                 if p.name.startswith(("tcl", "tk", "itcl", "thread", "libtcl", "libtk"))]
        gone += [str(p.relative_to(python)) for p in (python / "bin").iterdir()
                 if p.name != "python3.13"]
        gone += [str(p.relative_to(python)) for p in python.glob("lib/python3.*/lib-dynload/_tkinter*")]
    for part in gone:
        path = python / part
        if path.is_dir() and not path.is_symlink():
            shutil.rmtree(path)
        elif path.exists() or path.is_symlink():
            path.unlink()


def main() -> None:
    system = {"Linux": "linux", "Windows": "windows"}.get(platform.system())
    if system is None or platform.machine().lower() not in ("x86_64", "amd64"):
        sys.exit(f"Only x86-64 Linux and Windows: {platform.system()} {platform.machine()}")
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True)

    target, sha = PYTHON[system]
    name = f"cpython-{PYTHON_VERSION}+{PYTHON_RELEASE}-{target}-install_only_stripped.tar.gz"
    data = download(f"https://github.com/astral-sh/python-build-standalone/releases/download/{PYTHON_RELEASE}/{name}", sha)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        archive.extractall(OUT, filter="data")  # → resources/python/
    python = OUT / "python" / ("python.exe" if system == "windows" else "bin/python3.13")
    lib = OUT / "python" / ("Lib" if system == "windows" else f"lib/python{PYTHON_VERSION.rsplit('.', 1)[0]}")
    for part in DROP:
        shutil.rmtree(lib / part, ignore_errors=True)

    subprocess.run([str(python), "-m", "pip", "install", "--no-cache-dir", "--disable-pip-version-check",
                    "--no-warn-script-location", str(ROOT / "ytm-module")], check=True)
    subprocess.run([str(python), "-m", "pip", "uninstall", "-y", "pip"], check=True)
    for cache in (OUT / "python").rglob("__pycache__"):
        shutil.rmtree(cache)
    trim(OUT / "python", system)

    target, sha = DENO[system]
    data = download(f"https://github.com/denoland/deno/releases/download/v{DENO_VERSION}/deno-{target}.zip", sha)
    (OUT / "deno").mkdir()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        archive.extractall(OUT / "deno")
    if system == "linux":
        (OUT / "deno" / "deno").chmod(0o755)

    size = sum(f.stat().st_size for f in OUT.rglob("*") if f.is_file())
    print(f"module bundled in {OUT}: {size / 1e6:.0f} MB", flush=True)


if __name__ == "__main__":
    main()
