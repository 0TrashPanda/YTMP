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
