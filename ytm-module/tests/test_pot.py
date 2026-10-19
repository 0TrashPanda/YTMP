import io
import json

import pytest
from yt_dlp.extractor.youtube.pot.provider import (
    PoTokenContext,
    PoTokenProviderError,
    PoTokenProviderRejectedRequest,
    PoTokenRequest,
)

from ytmp_ytm import pot

# Like YouTube's page: the challenge is JSON in a JavaScript string, with \x escapes.
_CHALLENGE = json.dumps({"bgChallenge": {
    "program": "prog", "globalName": "bg", "interpreterHash": "h",
    "interpreterUrl": {"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue": "//www.google.com/js/th/x.js"},
}}).replace('"', "\\x22").replace("{", "\\x7b").replace("}", "\\x7d")
_PAGE = f"""<script>ytcfg.set({{"EVENT_ID": "ev1", "VISITOR_DATA": "vd"}});</script>
<script>window.ytAtN();</script><script>window.ytAtN({{'R': '{_CHALLENGE}'}});</script>"""


class FakeBotGuard:
    """The app's WebView: remembers what it ran, and mints "token-<binding>"."""

    def __init__(self):
        self.snapshots = []
        self.minter = None

    def snapshot(self, script, program, global_name, ytcfg):
        self.snapshots.append((script, program, global_name, json.loads(ytcfg)["EVENT_ID"]))
        self.minter = None
        return "bg-answer"

    def start(self, integrity_token):
        self.minter = integrity_token

    def mint(self, binding):
        if not self.minter:
            raise RuntimeError("No BotGuard page")
        return f"token-{binding}"


@pytest.fixture
def botguard(monkeypatch):
    fake = FakeBotGuard()
    pot.use_botguard(fake)
    pot._scripts.clear()
    yield fake
    pot.use_botguard(None)


def _provider(fetched):
    """The provider, with yt-dlp's network replaced: answers per URL, and keeps what was asked."""
    provider = pot.BotGuardPTP(ie=None, logger=None, settings={})

    def fetch(req, pot_request=None, note=None):
        fetched.append(req.url)
        if req.url == "https://www.youtube.com/":
            return io.BytesIO(_PAGE.encode())
        if req.url == "https://www.google.com/js/th/x.js":
            return io.BytesIO(b"var bg = {};")
        assert json.loads(req.data) == [pot._REQUEST_KEY, "bg-answer"]
        return io.BytesIO(json.dumps(["integrity", 43200, 100, "fallback"]).encode())

    provider._request_webpage = fetch
    return provider


def _request(video_id="abc", remote_host="1.2.3.4"):
    return PoTokenRequest(
        context=PoTokenContext.PLAYER,
        innertube_context={"client": {"clientName": "MWEB", "remoteHost": remote_host}},
        video_id=video_id,
    )


def test_botguard_runs_once_then_mints_per_video(botguard):
    fetched = []
    provider = _provider(fetched)
    assert provider.request_pot(_request("abc")).po_token == "token-abc"
    assert botguard.snapshots == [("var bg = {};", "prog", "bg", "ev1")]
    assert len(fetched) == 3  # the page, BotGuard's script, the integrity token

    assert provider.request_pot(_request("def")).po_token == "token-def"
    assert len(botguard.snapshots) == 1 and len(fetched) == 3


def test_a_new_ip_address_or_a_closed_page_starts_over(botguard):
    fetched = []
    provider = _provider(fetched)
    provider.request_pot(_request(remote_host="1.2.3.4"))
    provider.request_pot(_request(remote_host="5.6.7.8"))
    assert len(botguard.snapshots) == 2

    botguard.minter = None  # the app closed the page after a while
    assert provider.request_pot(_request("ghi", remote_host="5.6.7.8")).po_token == "token-ghi"
    assert len(botguard.snapshots) == 3
    # BotGuard's script is only fetched once.
    assert fetched.count("https://www.google.com/js/th/x.js") == 1


def test_after_a_failure_it_waits_before_trying_again(botguard, monkeypatch):
    fetched = []
    provider = _provider(fetched)
    real_fetch = provider._request_webpage
    provider._request_webpage = lambda req, pot_request=None, note=None: io.BytesIO(b"<html></html>")
    with pytest.raises(PoTokenProviderError, match="no BotGuard challenge"):
        provider.request_pot(_request())
    # The next lookups go without a token, and without fetching YouTube's page again.
    provider._request_webpage = real_fetch
    with pytest.raises(PoTokenProviderRejectedRequest):
        provider.request_pot(_request("def"))
    assert fetched == []

    now = pot.time.time()
    monkeypatch.setattr(pot.time, "time", lambda: now + pot._RETRY_AFTER_S + 1)
    assert provider.request_pot(_request("def")).po_token == "token-def"


def test_only_there_with_a_webview():
    pot.use_botguard(None)
    assert not pot.available()
    assert not pot.BotGuardPTP(ie=None, logger=None, settings={}).is_available()
