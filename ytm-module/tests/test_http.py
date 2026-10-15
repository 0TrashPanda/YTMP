import httpx
from fastapi.testclient import TestClient

from ytmp_ytm import core
from ytmp_ytm.http import create_app


def _client(monkeypatch, accounts: list[str]) -> TestClient:
    ytm = core.YtmCore()

    def fake_sign_in(self, cookie: str) -> dict:
        if "__Secure-3PAPISID=" not in cookie:
            raise core.NotSignedIn("Not signed in to YouTube Music")
        accounts.append(cookie)
        self._user = object()
        return {"name": cookie}

    monkeypatch.setattr(core.YtmCore, "sign_in", fake_sign_in)
    monkeypatch.setattr(core.YtmCore, "account", lambda self: {"name": "Anna"} if self._user else None)
    return TestClient(create_app(ytm, key="k"))


def test_personal_pages_use_the_cookies_the_server_sends(monkeypatch):
    signed_in: list[str] = []
    client = _client(monkeypatch, signed_in)
    auth = {"Authorization": "Bearer k"}

    assert client.get("/me/account", headers=auth).status_code == 422  # no cookies at all
    wrong = client.get("/me/account", headers={**auth, "X-Ytm-Cookie": "SID=1"})
    assert wrong.status_code == 401 and wrong.json()["error"]["code"] == "not_signed_in"

    cookie = "SID=1; __Secure-3PAPISID=abc"
    assert client.get("/me/account", headers={**auth, "X-Ytm-Cookie": cookie}).json() == {"name": "Anna"}
    client.get("/me/account", headers={**auth, "X-Ytm-Cookie": cookie})
    # Signed in once, then kept for the next requests.
    assert signed_in == [cookie]
    # The module key is still needed.
    assert client.get("/me/account", headers={"X-Ytm-Cookie": cookie}).status_code == 401


def test_each_account_gets_its_own_view(monkeypatch):
    ytm = core.YtmCore()
    monkeypatch.setattr(core.YtmCore, "sign_in", lambda self, cookie: setattr(self, "_user", cookie) or {})
    anna = ytm.signed_in_as("anna")
    bob = ytm.signed_in_as("bob")
    assert (anna._user, bob._user, ytm._user) == ("anna", "bob", None)
    # The anonymous side and the stream cache are shared.
    assert anna._ytm is ytm._ytm and anna._streams is ytm._streams


def test_a_page_may_ask_with_the_key(monkeypatch):
    client = TestClient(create_app(core.YtmCore(), key="k"))
    preflight = client.options(
        "/info",
        headers={
            "Origin": "https://ytmp.example.com",
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "authorization",
            "Access-Control-Request-Private-Network": "true",
        },
    )
    assert preflight.status_code == 204
    assert preflight.headers["access-control-allow-origin"] == "https://ytmp.example.com"
    assert preflight.headers["access-control-allow-private-network"] == "true"

    page = {"Origin": "https://ytmp.example.com"}
    assert client.get("/info", headers=page).status_code == 401
    answer = client.get("/info", headers=page | {"Authorization": "Bearer k"})
    assert answer.status_code == 200
    assert answer.headers["access-control-allow-origin"] == "https://ytmp.example.com"


def test_without_a_key_pages_cant_use_it():
    client = TestClient(create_app(core.YtmCore(), key=None))
    answer = client.get("/info", headers={"Origin": "https://example.com"})
    assert "access-control-allow-origin" not in answer.headers


def test_audio_goes_on_with_a_fresh_link_when_youtube_stops_the_old_one(monkeypatch):
    # YouTube sometimes serves a link's first MB and then answers 403: the proxy resolves the
    # song again and goes on from where it was, instead of ending the stream there.
    monkeypatch.setattr(core, "CHUNK_SIZE", 100)
    data = bytes(i % 256 for i in range(250))

    def youtube(request: httpx.Request) -> httpx.Response:
        start, end = (int(x) for x in request.headers["range"].removeprefix("bytes=").split("-"))
        if request.url.host == "old" and start >= 100:
            return httpx.Response(403)
        end = min(end, len(data) - 1)

        async def body():
            yield data[start:end + 1]

        return httpx.Response(206, content=body(), headers={"content-range": f"bytes {start}-{end}/{len(data)}"})

    real_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: real_client(transport=httpx.MockTransport(youtube)))
    resolved: list[bool] = []

    def stream(self, song_id: str, fresh: bool = False) -> core.StreamInfo:
        resolved.append(fresh)
        return core.StreamInfo(f"https://{'new' if fresh else 'old'}/a", core.time.time() + 3600, "audio/mp4", None)

    monkeypatch.setattr(core.YtmCore, "stream", stream)
    client = TestClient(create_app(core.YtmCore(), key="k"))

    response = client.get("/songs/ytm:abc/audio", headers={"Authorization": "Bearer k", "Range": "bytes=0-"})
    assert response.status_code == 206
    assert response.content == data
    assert resolved == [False, True]
