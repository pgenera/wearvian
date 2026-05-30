"""Minimal synchronous Rivian authentication client.

This implements only the three GraphQL calls needed to turn a Rivian email +
password (+ MFA OTP) into the session tokens the watch needs:

  CreateCSRFToken  ->  Login  ->  (if MFA) LoginWithOTP

The GraphQL operation names, query strings and headers are kept byte-identical
to the reference implementation in ``bretterer/rivian-python-client``
(``src/rivian/rivian.py``) so behaviour matches the community-tested client.
We use ``requests`` (sync) instead of the reference's ``aiohttp`` (async)
because the broker is a small synchronous Flask app and this keeps it trivially
unit-testable by mocking ``requests``.

No credentials are ever persisted by this module; callers hold the returned
tokens only for as long as the pairing handshake needs them.
"""

from __future__ import annotations

from dataclasses import dataclass

import requests

GRAPHQL_GATEWAY = "https://rivian.com/api/gql/gateway/graphql"
APOLLO_CLIENT_NAME = "com.rivian.ios.consumer-apollo-ios"

BASE_HEADERS = {
    "User-Agent": "RivianApp/707 CFNetwork/1237 Darwin/20.4.0",
    "Accept": "application/json",
    "Content-Type": "application/json",
    "Apollographql-Client-Name": APOLLO_CLIENT_NAME,
}

# GraphQL documents, copied verbatim from rivian-python-client.
_CSRF_QUERY = (
    "mutation CreateCSRFToken {\n  createCsrfToken {\n    __typename\n"
    "    csrfToken\n    appSessionToken\n  }\n}"
)
_LOGIN_QUERY = (
    "mutation Login($email: String!, $password: String!) {\n  login(email: $email, "
    "password: $password) {\n    __typename\n    ... on MobileLoginResponse {\n"
    "      __typename\n      accessToken\n      refreshToken\n      userSessionToken\n"
    "    }\n    ... on MobileMFALoginResponse {\n      __typename\n      otpToken\n"
    "    }\n  }\n}"
)
_OTP_QUERY = (
    "mutation LoginWithOTP($email: String!, $otpCode: String!, $otpToken: String!) {\n"
    "  loginWithOTP(email: $email, otpCode: $otpCode, otpToken: $otpToken) {\n"
    "    __typename\n    ... on MobileLoginResponse {\n      __typename\n"
    "      accessToken\n      refreshToken\n      userSessionToken\n    }\n  }\n}"
)


class RivianAuthError(Exception):
    """Raised when Rivian returns a GraphQL error or an unexpected response."""


@dataclass
class CsrfTokens:
    csrf_token: str
    app_session_token: str


@dataclass
class SessionTokens:
    """The tokens the watch needs to call getUserInfo / EnrollPhone itself."""

    csrf_token: str
    app_session_token: str
    user_session_token: str


class RivianAuthClient:
    """Drives the create-csrf -> login -> (otp) sequence with one HTTP session."""

    def __init__(self, timeout: float = 30.0, session: requests.Session | None = None):
        self._timeout = timeout
        self._http = session or requests.Session()

    def _post(self, headers: dict, payload: dict) -> dict:
        resp = self._http.post(
            GRAPHQL_GATEWAY, json=payload, headers=headers, timeout=self._timeout
        )
        if resp.status_code != 200:
            raise RivianAuthError(f"HTTP {resp.status_code} from Rivian gateway")
        body = resp.json()
        if body.get("errors"):
            raise RivianAuthError(str(body["errors"]))
        data = body.get("data")
        if not data:
            raise RivianAuthError("Missing 'data' in Rivian response")
        return data

    def create_csrf_token(self) -> CsrfTokens:
        data = self._post(
            dict(BASE_HEADERS),
            {"operationName": "CreateCSRFToken", "query": _CSRF_QUERY, "variables": None},
        )
        csrf = data["createCsrfToken"]
        return CsrfTokens(csrf["csrfToken"], csrf["appSessionToken"])

    def login(self, csrf: CsrfTokens, email: str, password: str):
        """Returns SessionTokens on success, or an MFA otpToken string if MFA is required."""
        headers = {
            **BASE_HEADERS,
            "Csrf-Token": csrf.csrf_token,
            "A-Sess": csrf.app_session_token,
        }
        data = self._post(
            headers,
            {
                "operationName": "Login",
                "query": _LOGIN_QUERY,
                "variables": {"email": email, "password": password},
            },
        )
        login = data["login"]
        if "otpToken" in login and login["otpToken"]:
            return login["otpToken"]
        return SessionTokens(
            csrf.csrf_token, csrf.app_session_token, login["userSessionToken"]
        )

    def login_with_otp(
        self, csrf: CsrfTokens, email: str, otp_code: str, otp_token: str
    ) -> SessionTokens:
        headers = {
            **BASE_HEADERS,
            "Csrf-Token": csrf.csrf_token,
            "A-Sess": csrf.app_session_token,
        }
        data = self._post(
            headers,
            {
                "operationName": "LoginWithOTP",
                "query": _OTP_QUERY,
                "variables": {
                    "email": email,
                    "otpCode": otp_code,
                    "otpToken": otp_token,
                },
            },
        )
        login = data["loginWithOTP"]
        return SessionTokens(
            csrf.csrf_token, csrf.app_session_token, login["userSessionToken"]
        )
