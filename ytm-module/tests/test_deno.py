import os
import sys
import threading
import time
from types import SimpleNamespace

import pytest
from yt_dlp.extractor.youtube.jsc._builtin.deno import DenoJCP
from yt_dlp.extractor.youtube.jsc.provider import JsChallengeProviderError

from ytmp_ytm import core, deno


def fake_deno(tmp_path, monkeypatch, script: str):
    """A 'deno' that runs [script] (Python) and writes its arguments to args.txt.

    It's Python itself, so it runs on every system (a shell script doesn't on Windows), and the
    real interpreter, not a venv's launcher (which a timeout couldn't stop on Windows): deno.run
    calls `<deno> run …`, so Python runs the file "run" in the current folder.
    """
    args = str(tmp_path / "args.txt")
    (tmp_path / "run").write_text(
        "import sys, time\n"
        f"open({args!r}, 'a').write(' '.join(['run'] + sys.argv[1:]) + chr(10))\n"
        f"{script}\n"
    )
    monkeypatch.chdir(tmp_path)
    return SimpleNamespace(
        runtime_info=SimpleNamespace(path=getattr(sys, "_base_executable", sys.executable)),
        _get_env_options=lambda: dict(os.environ),
        _clean_stderr=lambda s: s,
    )


def test_ytm_core_puts_the_limits_on_yt_dlps_deno():
    assert core.deno is deno
    assert DenoJCP._run_deno is deno.run


def test_answers_and_caps_the_heap(tmp_path, monkeypatch):
    provider = fake_deno(tmp_path, monkeypatch, "sys.stdout.write(sys.stdin.read())")
    assert deno.run(provider, "solved", ["--no-npm"]) == "solved"
    assert deno.run(provider, "solved", ["--v8-flags=--jitless"]) == "solved"
    first, second = (tmp_path / "args.txt").read_text().splitlines()
    assert first == f"run --no-npm --v8-flags=--max-old-space-size={deno.HEAP_MB} -"
    assert second == f"run --v8-flags=--jitless,--max-old-space-size={deno.HEAP_MB} -"


def test_a_stuck_deno_is_stopped(tmp_path, monkeypatch):
    monkeypatch.setattr(deno, "TIMEOUT_S", 0.5)
    provider = fake_deno(tmp_path, monkeypatch, "time.sleep(30)")
    started = time.monotonic()
    with pytest.raises(JsChallengeProviderError, match="longer than"):
        deno.run(provider, "", [])
    assert time.monotonic() - started < 5


def test_no_more_than_two_at_once(tmp_path, monkeypatch):
    monkeypatch.setattr(deno, "TIMEOUT_S", 2.0)
    # Each counts the running ones in a file while it runs.
    log = str(tmp_path / "log")
    provider = fake_deno(tmp_path, monkeypatch, f"open({log!r}, 'a').write('+ '); time.sleep(0.3); open({log!r}, 'a').write('- ')")
    threads = [threading.Thread(target=lambda: deno.run(provider, "", [])) for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    running = most = 0
    for line in (tmp_path / "log").read_text().split():
        running += 1 if line == "+" else -1
        most = max(most, running)
    assert most == deno.AT_ONCE
