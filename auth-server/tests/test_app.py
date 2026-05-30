"""Unit tests for the Flask auth broker (no network; Rivian client is faked)."""

import pytest

import main
from rivian_auth import CsrfTokens, SessionTokens

HTTPS = {"X-Forwarded-Proto": "https"}  # simulate TLS terminated at the edge


@pytest.fixture
def app_client(monkeypatch):
    monkeypatch.setenv("PUBLIC_BASE_URL", "https://pair.example")
    main._SESSIONS.clear()
    main.app.config.update(TESTING=True)
    return main.app.test_client()


class FakeClient:
    """Stand-in for RivianAuthClient; behaviour controlled per test."""

    mfa = False

    def create_csrf_token(self):
        return CsrfTokens("csrf1", "app1")

    def login(self, csrf, email, password):
        if FakeClient.mfa:
            return "otp-token-xyz"
        return SessionTokens("csrf1", "app1", "user-direct")

    def login_with_otp(self, csrf, email, otp, otp_token):
        assert otp == "123456" and otp_token == "otp-token-xyz"
        return SessionTokens("csrf1", "app1", "user-after-otp")


@pytest.fixture(autouse=True)
def fake_rivian(monkeypatch):
    FakeClient.mfa = False
    monkeypatch.setattr(main, "RivianAuthClient", FakeClient)


def _new_session(client):
    resp = client.post("/session", headers=HTTPS)
    assert resp.status_code == 201
    return resp.get_json()


def test_health(app_client):
    body = app_client.get("/").get_json()
    assert body["service"] == "wearvian-auth" and body["ok"] is True


def test_create_session_returns_qr_url(app_client):
    data = _new_session(app_client)
    assert data["browser_url"].startswith("https://pair.example/a/")
    assert data["poll_url"].startswith("https://pair.example/session/")
    assert data["nonce"] in data["browser_url"]


def test_happy_path_no_mfa(app_client):
    data = _new_session(app_client)
    nonce = data["nonce"]

    # Before login, polling is pending.
    assert app_client.get(f"/session/{nonce}", headers=HTTPS).status_code == 202

    resp = app_client.post(
        f"/a/{nonce}",
        data={"email": "me@example.com", "password": "pw"},
        headers=HTTPS,
    )
    assert resp.status_code == 200
    assert b"Signed in" in resp.data

    poll = app_client.get(f"/session/{nonce}", headers=HTTPS)
    assert poll.status_code == 200
    assert poll.get_json() == {
        "csrfToken": "csrf1",
        "appSessionToken": "app1",
        "userSessionToken": "user-direct",
    }
    # Single-use: nonce is gone after the watch reads it.
    assert app_client.get(f"/session/{nonce}", headers=HTTPS).status_code == 404


def test_mfa_path(app_client):
    FakeClient.mfa = True
    nonce = _new_session(app_client)["nonce"]

    step1 = app_client.post(
        f"/a/{nonce}",
        data={"email": "me@example.com", "password": "pw"},
        headers=HTTPS,
    )
    assert step1.status_code == 200
    assert b"one-time code" in step1.data

    step2 = app_client.post(f"/a/{nonce}", data={"otp": "123456"}, headers=HTTPS)
    assert step2.status_code == 200
    assert b"Signed in" in step2.data

    poll = app_client.get(f"/session/{nonce}", headers=HTTPS)
    assert poll.get_json()["userSessionToken"] == "user-after-otp"


def test_missing_fields_rejected(app_client):
    nonce = _new_session(app_client)["nonce"]
    resp = app_client.post(f"/a/{nonce}", data={"email": ""}, headers=HTTPS)
    assert resp.status_code == 400


def test_unknown_nonce_is_404(app_client):
    assert app_client.get("/a/nope", headers=HTTPS).status_code == 404
    assert app_client.get("/session/nope", headers=HTTPS).status_code == 404


def test_insecure_http_blocked_by_default(app_client, monkeypatch):
    monkeypatch.delenv("ALLOW_INSECURE_HTTP", raising=False)
    nonce = _new_session(app_client)["nonce"]
    # No X-Forwarded-Proto -> treated as plain HTTP -> credential endpoints blocked.
    assert app_client.get(f"/a/{nonce}").status_code == 403
    assert app_client.get(f"/session/{nonce}").status_code == 403


def test_insecure_http_allowed_with_flag(app_client, monkeypatch):
    monkeypatch.setenv("ALLOW_INSECURE_HTTP", "true")
    nonce = _new_session(app_client)["nonce"]
    # Over plain HTTP now permitted (e.g. behind Tailscale).
    assert app_client.get(f"/a/{nonce}").status_code == 200
