"""Limits on the Deno processes yt-dlp starts for YouTube's JavaScript challenges (server only;
the phone has no Deno, see jsc.py).

yt-dlp starts one Deno per lookup and waits for it without a time limit. Many lookups at once
(each Deno ~220 MB) once filled the server's memory, and then none of them finished: 16 hung
for 21 minutes. So: at most [AT_ONCE] at a time, each with a capped heap, stopped after
[TIMEOUT_S]. A challenge that isn't solved isn't fatal: yt-dlp then takes a client that needs
none (visionos), as it did then.
"""

from __future__ import annotations

import subprocess
import threading

from yt_dlp.extractor.youtube.jsc._builtin.deno import DenoJCP
from yt_dlp.extractor.youtube.jsc.provider import JsChallengeProviderError
from yt_dlp.utils import Popen

AT_ONCE = 2
TIMEOUT_S = 30.0
HEAP_MB = 512

_slots = threading.BoundedSemaphore(AT_ONCE)


def _with_heap_cap(options: list[str]) -> list[str]:
    flag = f"--max-old-space-size={HEAP_MB}"
    for i, option in enumerate(options):
        if option.startswith("--v8-flags="):
            return [*options[:i], f"{option},{flag}", *options[i + 1:]]
    return [*options, f"--v8-flags={flag}"]


def run(provider, stdin: str, options: list[str]) -> str:
    """DenoJCP._run_deno with the limits above; [provider] is the DenoJCP."""
    # Waiting longer than a solve may take: the lookups before it are stuck too.
    if not _slots.acquire(timeout=TIMEOUT_S):
        raise JsChallengeProviderError(f"No free Deno after {TIMEOUT_S:.0f} s")
    try:
        cmd = [provider.runtime_info.path, "run", *_with_heap_cap(options), "-"]
        with Popen(cmd, text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                   env=provider._get_env_options()) as proc:
            try:
                stdout, stderr = proc.communicate_or_kill(stdin, timeout=TIMEOUT_S)
            except subprocess.TimeoutExpired:
                raise JsChallengeProviderError(f"Deno took longer than {TIMEOUT_S:.0f} s") from None
            stderr = provider._clean_stderr(stderr)
            if proc.returncode or stderr:
                message = f"Error running deno process (returncode: {proc.returncode})"
                raise JsChallengeProviderError(f"{message}: {stderr.strip()}" if stderr else message)
        return stdout
    finally:
        _slots.release()


def limit() -> None:
    """From now on, yt-dlp's Deno runs with the limits above."""
    DenoJCP._run_deno = run
