"""Unit tests for the synchronous Rivian auth client (no network)."""

import json

import pytest

from rivian_auth import (
    GRAPHQL_GATEWAY,
    CsrfTokens,
    RivianAuthClient,
    RivianAuthError,
    SessionTokens,
)


class FakeResponse:
    def __init__(self, status_code=200, body=None):
        self.status_code = status_code
        self._body = body or {}

    def json(self):
        return self._body


class FakeSession:
    """Records posts and replays a queued list of responses."""

    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = []

    def post(self, url, json=None, headers=None, timeout=None):
        self.calls.append({"url": url, "json": json, "headers": headers})
        return self._responses.pop(0)


def client_with(*responses):
    session = FakeSession(responses)
    return RivianAuthClient(session=session), session


def test_create_csrf_token_parses_response():
    client, session = client_with(
        FakeResponse(
            body={"data": {"createCsrfToken": {"csrfToken": "csrf1", "appSessionToken": "app1"}}}
        )
    )
    csrf = client.create_csrf_token()
    assert csrf == CsrfTokens("csrf1", "app1")
    assert session.calls[0]["url"] == GRAPHQL_GATEWAY
    assert session.calls[0]["json"]["operationName"] == "CreateCSRFToken"


def test_login_without_mfa_returns_session_tokens():
    client, _ = client_with(
        FakeResponse(
            body={
                "data": {
                    "login": {
                        "accessToken": "a",
                        "refreshToken": "r",
                        "userSessionToken": "user1",
                    }
                }
            }
        )
    )
    result = client.login(CsrfTokens("csrf1", "app1"), "me@example.com", "pw")
    assert result == SessionTokens("csrf1", "app1", "user1")


def test_login_with_mfa_returns_otp_token():
    client, session = client_with(
        FakeResponse(body={"data": {"login": {"otpToken": "otp-token-xyz"}}})
    )
    result = client.login(CsrfTokens("csrf1", "app1"), "me@example.com", "pw")
    assert result == "otp-token-xyz"
    # Login must carry the csrf + app-session headers.
    headers = session.calls[0]["headers"]
    assert headers["Csrf-Token"] == "csrf1"
    assert headers["A-Sess"] == "app1"


def test_login_with_otp_returns_session_tokens():
    client, session = client_with(
        FakeResponse(
            body={
                "data": {
                    "loginWithOTP": {
                        "accessToken": "a",
                        "refreshToken": "r",
                        "userSessionToken": "user2",
                    }
                }
            }
        )
    )
    tokens = client.login_with_otp(
        CsrfTokens("csrf1", "app1"), "me@example.com", "123456", "otp-token-xyz"
    )
    assert tokens == SessionTokens("csrf1", "app1", "user2")
    variables = session.calls[0]["json"]["variables"]
    assert variables == {
        "email": "me@example.com",
        "otpCode": "123456",
        "otpToken": "otp-token-xyz",
    }


def test_graphql_errors_raise():
    client, _ = client_with(FakeResponse(body={"errors": [{"message": "bad creds"}]}))
    with pytest.raises(RivianAuthError):
        client.create_csrf_token()


def test_non_200_raises():
    client, _ = client_with(FakeResponse(status_code=500, body={}))
    with pytest.raises(RivianAuthError):
        client.create_csrf_token()
