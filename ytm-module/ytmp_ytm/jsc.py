"""YouTube's JavaScript challenges, solved by a JavaScript engine the app hands us.

yt-dlp needs to run some of YouTube's own JavaScript to get working links (without it, it
falls back to a client with fewer formats, and warns). On a server that's Deno (see the
Dockerfile). The phone has no JavaScript runtime to start as a program, so the Android app
passes an engine it has (the WebView's) to `use_engine`, and yt-dlp solves the challenges
with it, using the same scripts as for Deno (yt-dlp-ejs).
"""

from __future__ import annotations

from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
from yt_dlp.extractor.youtube.jsc.provider import (
    JsChallengeProvider,
    JsChallengeProviderError,
    JsChallengeRequest,
    register_preference,
    register_provider,
)

# Something with run(code: str) -> str: runs a script and returns the value of its last line.
_engine = None

# The solver script prints its answer; the engine returns the last value instead.
_PRELUDE = (
    "var __ytmpOut = [];"
    "globalThis.console = {log: (...a) => __ytmpOut.push(a.join(' ')), info() {}, warn() {}, error() {}, debug() {}};\n"
)
_EPILOGUE = "\n;__ytmpOut.join('\\n')"


def use_engine(engine) -> None:
    """From now on, solve YouTube's challenges with this engine (see module doc)."""
    global _engine
    _engine = engine


@register_provider
class EmbeddedJCP(EJSBaseJCP):
    PROVIDER_VERSION = "1.0.0"
    JS_RUNTIME_NAME = "embedded"
    BUG_REPORT_LOCATION = "https://github.com/0TrashPanda/YTMP/issues"

    def is_available(self, /) -> bool:
        return _engine is not None and self._available

    def _run_js_runtime(self, stdin: str, /) -> str:
        try:
            return str(_engine.run(_PRELUDE + stdin + _EPILOGUE))
        except Exception as e:
            raise JsChallengeProviderError(f"JavaScript engine failed: {e}") from e


@register_preference(EmbeddedJCP)
def _preference(provider: JsChallengeProvider, requests: list[JsChallengeRequest]) -> int:
    # Only there when the app gave an engine; then it's the one to use.
    return 1000
