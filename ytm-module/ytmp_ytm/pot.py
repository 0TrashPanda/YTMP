"""PO tokens on the phone, made by YouTube's BotGuard in a WebView the app hands us.

A PO token ("proof of origin") says a request comes from a real YouTube player: BotGuard, a
script of YouTube's, checks the browser it runs in and Google gives an integrity token for
that, from which a token per video (or visitor) is made in an instant. YouTube trusts lookups
that carry them more, so its bot check comes later.

On a server, the bgutil provider (a container of its own, see core.YtmCore's pot_url) makes
them. The phone can't run that, so the app passes `use_botguard` something that runs
BotGuard in a hidden WebView (BotGuard.kt), and this does the network part around it:
YouTube's page (for the challenge), BotGuard's script and Google's integrity token. The same
steps as bgutil's server (https://github.com/Brainicism/bgutil-ytdlp-pot-provider).
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time

from yt_dlp.extractor.youtube.pot.provider import (
    PoTokenContext,
    PoTokenProvider,
    PoTokenProviderError,
    PoTokenProviderRejectedRequest,
    PoTokenRequest,
    PoTokenResponse,
    register_preference,
    register_provider,
)
from yt_dlp.extractor.youtube.pot.utils import WEBPO_CLIENTS, get_webpo_content_binding
from yt_dlp.networking import Request
from yt_dlp.utils import js_to_json
from yt_dlp.utils.traversal import traverse_obj

log = logging.getLogger("ytmp_ytm.pot")

# The key and endpoint YouTube's own pages use for integrity tokens.
_REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
_GENERATE_IT = "https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT"
# Start over this long before Google says the integrity token expires.
_EXPIRY_MARGIN_S = 5 * 60
# After BotGuard failed, lookups go without tokens this long (each try fetches YouTube's page).
_RETRY_AFTER_S = 10 * 60

# Something with these, called on Python's thread (see BotGuard.kt):
#   snapshot(script, program, global_name, ytcfg) -> BotGuard's answer, on a fresh page
#   start(integrity_token) -> starts the minter
#   mint(content_binding) -> token; raises when the page is gone (then we start over)
_botguard = None
_lock = threading.Lock()
# The page's minter: until when it works, and for which IP address (tokens are bound to it).
_minter: tuple[float, str | None] | None = None
# BotGuard's script by its URL: it rarely changes.
_scripts: dict[str, str] = {}
_failed_at = 0.0


def use_botguard(botguard) -> None:
    """From now on, make PO tokens with this (see module doc)."""
    global _botguard, _minter, _failed_at
    with _lock:
        _botguard, _minter, _failed_at = botguard, None, 0.0


def available() -> bool:
    return _botguard is not None


@register_provider
class BotGuardPTP(PoTokenProvider):
    PROVIDER_NAME = "botguard"
    PROVIDER_VERSION = "1.0.0"
    BUG_REPORT_LOCATION = "https://github.com/0TrashPanda/YTMP/issues"
    _SUPPORTED_CLIENTS = WEBPO_CLIENTS
    _SUPPORTED_CONTEXTS = (PoTokenContext.GVS, PoTokenContext.PLAYER, PoTokenContext.SUBS)
    # All requests go through yt-dlp (_request_webpage).
    _SUPPORTED_EXTERNAL_REQUEST_FEATURES = None

    def is_available(self) -> bool:
        return _botguard is not None

    def _real_request_pot(self, request: PoTokenRequest) -> PoTokenResponse:
        binding = get_webpo_content_binding(request)[0]
        if not binding:
            raise PoTokenProviderRejectedRequest("Nothing to bind the token to")
        remote_host = traverse_obj(request.innertube_context, ("client", "remoteHost"))
        global _minter, _failed_at
        with _lock:
            if _minter and _minter[0] > time.time() and _minter[1] == remote_host:
                try:
                    return PoTokenResponse(str(_botguard.mint(binding)))
                except Exception as e:
                    log.info("PO token: the minter stopped working (%s), starting over", e)
            _minter = None
            if time.time() - _failed_at < _RETRY_AFTER_S:
                raise PoTokenProviderRejectedRequest("BotGuard failed a moment ago")
            started = time.monotonic()
            try:
                expires_at = self._start(request)
                token = str(_botguard.mint(binding))
            except Exception as e:
                _failed_at = time.time()
                log.warning("PO tokens: BotGuard failed, lookups go without for %d min: %s", _RETRY_AFTER_S // 60, e)
                if isinstance(e, PoTokenProviderError):
                    raise
                raise PoTokenProviderError(f"BotGuard couldn't make a token: {e}") from e
            _minter = expires_at, remote_host
            log.info("PO tokens: BotGuard ready in %.1f s, for %d min",
                     time.monotonic() - started, (expires_at - time.time()) // 60)
            return PoTokenResponse(token)

    def _start(self, request: PoTokenRequest) -> float:
        """Runs BotGuard on a fresh page and starts its minter; returns until when it works."""
        page = self._fetch(Request("https://www.youtube.com/"), request, "YouTube's page").read().decode()
        challenge = _challenge(page)
        program, global_name = challenge.get("program"), challenge.get("globalName")
        url = traverse_obj(challenge, ("interpreterUrl", "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"))
        if not (program and global_name and url):
            raise PoTokenProviderError("YouTube's page has no BotGuard challenge")
        url = f"https:{url}" if url.startswith("//") else url
        if url not in _scripts:
            _scripts.clear()
            _scripts[url] = self._fetch(Request(url), request, "BotGuard's script").read().decode()

        try:
            answer = str(_botguard.snapshot(_scripts[url], program, global_name, _ytcfg(page) or ""))
        except Exception as e:
            raise PoTokenProviderError(f"BotGuard failed: {e}") from e

        response = self._fetch(Request(_GENERATE_IT, data=json.dumps([_REQUEST_KEY, answer]).encode(), headers={
            "Content-Type": "application/json+protobuf",
            "x-goog-api-key": _API_KEY,
            "x-user-agent": "grpc-web-javascript/0.1",
        }), request, "an integrity token")
        integrity = json.load(response)
        token, ttl = (integrity + [None, None])[:2]
        if not token:
            raise PoTokenProviderError(f"Google gave no integrity token: {integrity}")
        try:
            _botguard.start(token)
        except Exception as e:
            raise PoTokenProviderError(f"BotGuard's minter failed: {e}") from e
        return time.time() + (ttl or 3600) - _EXPIRY_MARGIN_S

    def _fetch(self, req: Request, request: PoTokenRequest, what: str):
        try:
            return self._request_webpage(req, pot_request=request, note=f"PO token: fetching {what}")
        except Exception as e:
            raise PoTokenProviderError(f"Couldn't fetch {what}: {e}") from e


@register_preference(BotGuardPTP)
def _preference(provider: PoTokenProvider, request: PoTokenRequest) -> int:
    # Only there when the app gave a WebView; then it's the one to use.
    return 1000


def _challenge(page: str) -> dict:
    """BotGuard's challenge in YouTube's page (window.ytAtN({... R: '{"bgChallenge": ...}'}))."""
    match = re.search(r"window\s*\.\s*ytAtN\s*\(\s*(\{.+?})\s*\)\s*;", page, re.DOTALL)
    if not match:
        return {}
    try:
        return json.loads(json.loads(js_to_json(match.group(1)))["R"]).get("bgChallenge") or {}
    except (ValueError, KeyError, TypeError):
        return {}


def _ytcfg(page: str) -> str | None:
    """The page's settings: BotGuard reads its event ID from them."""
    match = re.search(r"ytcfg\.set\((\{.+?})\);", page, re.DOTALL)
    return match.group(1) if match else None
