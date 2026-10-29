import os
import stat
import threading
import time
from types import SimpleNamespace

import pytest
from yt_dlp.extractor.youtube.jsc._builtin.deno import DenoJCP
from yt_dlp.extractor.youtube.jsc.provider import JsChallengeProviderError

from ytmp_ytm import core, deno


def fake_deno(tmp_path, script: str):
    """A 'deno' that runs [script] (sh) and writes its arguments to args.txt."""
    path = tmp_path / "deno"
    path.write_text(f'#!/bin/sh\necho "$@" >> {tmp_path}/args.txt\n{script}\n')
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return SimpleNamespace(
        runtime_info=SimpleNamespace(path=str(path)),
        _get_env_options=lambda: dict(os.environ),
        _clean_stderr=lambda s: s,
    )


def test_ytm_core_puts_the_limits_on_yt_dlps_deno():
    assert core.deno is deno
    assert DenoJCP._run_deno is deno.run


def test_answers_and_caps_the_heap(tmp_path):
    provider = fake_deno(tmp_path, "cat")
    assert deno.run(provider, "solved", ["--no-npm"]) == "solved"
    assert deno.run(provider, "solved", ["--v8-flags=--jitless"]) == "solved"
    first, second = (tmp_path / "args.txt").read_text().splitlines()
    assert first == f"run --no-npm --v8-flags=--max-old-space-size={deno.HEAP_MB} -"
    assert second == f"run --v8-flags=--jitless,--max-old-space-size={deno.HEAP_MB} -"


def test_a_stuck_deno_is_stopped(tmp_path, monkeypatch):
    monkeypatch.setattr(deno, "TIMEOUT_S", 0.5)
    provider = fake_deno(tmp_path, "sleep 30")
    started = time.monotonic()
    with pytest.raises(JsChallengeProviderError, match="longer than"):
        deno.run(provider, "", [])
    assert time.monotonic() - started < 5


def test_no_more_than_two_at_once(tmp_path, monkeypatch):
    monkeypatch.setattr(deno, "TIMEOUT_S", 1.0)
    # Each counts the running ones in a file while it runs.
    provider = fake_deno(tmp_path, f"echo + >> {tmp_path}/log; sleep 0.3; echo - >> {tmp_path}/log")
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
