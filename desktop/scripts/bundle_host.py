"""Puts this computer's host in the desktop app (docs/implementation/desktop-app.md#this-computers-host):
the host's libraries (desktop/host, built with Gradle) and a Java runtime made for them with
jlink, in src-tauri/resources/host/lib and src-tauri/resources/java.

    python desktop/scripts/bundle_host.py      # needs a JDK 21 (JAVA_HOME, or java on PATH)

jlink takes only the Java modules the host uses (jdeps finds them), plus the ones it loads by
name, and makes a class data archive so it starts faster.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "desktop" / "src-tauri" / "resources"
BUILT = ROOT / "desktop" / "host" / "build" / "install" / "desktop-host" / "lib"
# Loaded by name, so jdeps doesn't see them: TLS to the server (elliptic curves), DNS for
# addresses, and zip for jaudiotagger's M4A reading.
ALSO = ["jdk.crypto.ec", "jdk.naming.dns", "jdk.zipfs", "jdk.charsets"]


def jdk_tool(name: str) -> str:
    exe = name + (".exe" if os.name == "nt" else "")
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / exe).exists():
        return str(Path(home) / "bin" / exe)
    found = shutil.which(name)
    if not found:
        sys.exit(f"{name} not found: set JAVA_HOME to a JDK 21")
    return found


def main() -> None:
    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    subprocess.run([gradle, ":desktop-host:installDist", "-q"], cwd=ROOT, check=True)

    lib = OUT / "host" / "lib"
    shutil.rmtree(lib, ignore_errors=True)
    shutil.copytree(BUILT, lib)
    print(f"host: {len(list(lib.glob('*.jar')))} libraries, {sum(f.stat().st_size for f in lib.glob('*.jar')) // 1024 // 1024} MB")

    # From inside the folder, by name: the whole paths are too long for a Windows command line.
    jars = sorted(j.name for j in lib.glob("*.jar"))
    modules = subprocess.run(
        [jdk_tool("jdeps"), "--ignore-missing-deps", "--multi-release", "21", "--print-module-deps", "--class-path", "*", *jars],
        cwd=lib, check=True, capture_output=True, text=True,
    ).stdout.strip().splitlines()[-1].split(",")
    modules = sorted(set(m for m in modules if m) | set(ALSO))
    print("java modules:", ",".join(modules))

    java = OUT / "java"
    shutil.rmtree(java, ignore_errors=True)
    subprocess.run(
        [jdk_tool("jlink"), "--add-modules", ",".join(modules), "--strip-debug", "--no-header-files", "--no-man-pages",
         "--compress", "zip-6", "--generate-cds-archive", "--output", str(java)],
        check=True,
    )
    # jlink makes some files read-only (the licences): the app's build couldn't copy over them again.
    for f in java.rglob("*"):
        os.chmod(f, 0o755 if f.is_dir() else 0o644 | (0o111 if f.parent.name == "bin" else 0))
    size = sum(f.stat().st_size for f in java.rglob("*") if f.is_file())
    print(f"java runtime: {size // 1024 // 1024} MB in {java}")


if __name__ == "__main__":
    main()
