"""wearvian auth broker.

A tiny stateless-ish web service that turns a Rivian login (email + password +
MFA OTP) into the session tokens the watch needs, handed back over a one-time
``nonce`` channel:

  1. Watch  POST /session            -> {nonce, browser_url, poll_url}
  2. User   GET/POST /a/<nonce>       -> login form, then (if needed) OTP form
  3. Watch  GET  /session/<nonce>     -> polls; gets tokens once, then nonce dies

The watch displays ``browser_url`` as a QR code. The user opens it in any
browser (on any device), authenticates, and the tokens flow back to the watch.
The watch's EC private key is never involved here.

Transport: by default the credential endpoints refuse to run over plain HTTP.
Set ``ALLOW_INSECURE_HTTP=true`` to permit HTTP for development behind a trusted
network (e.g. a Tailscale VPN). On App Engine, TLS is terminated by the platform
(seen via ``X-Forwarded-Proto``) so the default is fine there.
"""

from __future__ import annotations

import os
import secrets
import threading
import time

from flask import Flask, abort, jsonify, render_template_string, request, url_for

from rivian_auth import RivianAuthClient, RivianAuthError, SessionTokens

app = Flask(__name__)

SESSION_TTL = 300  # seconds a pairing session stays valid
_NONCE_BYTES = 24

# nonce -> session dict. Guarded by _LOCK. A single instance / worker is assumed
# (see app.yaml: automatic scaling capped at one instance).
_SESSIONS: dict[str, dict] = {}
_LOCK = threading.Lock()


def _allow_insecure() -> bool:
    return os.environ.get("ALLOW_INSECURE_HTTP", "false").strip().lower() in (
        "1",
        "true",
        "yes",
    )


def _is_secure() -> bool:
    """True if the *external* request reached us over TLS."""
    if request.is_secure:
        return True
    return request.headers.get("X-Forwarded-Proto", "").lower() == "https"


def _require_secure() -> None:
    """Block credential traffic over plain HTTP unless explicitly allowed."""
    if _is_secure() or _allow_insecure():
        return
    abort(
        403,
        "Refusing to handle credentials over plain HTTP. Use HTTPS, or set "
        "ALLOW_INSECURE_HTTP=true to run over a trusted network (e.g. Tailscale).",
    )


def _base_url() -> str:
    """Public base URL used to build the QR link the user will open."""
    explicit = os.environ.get("PUBLIC_BASE_URL")
    if explicit:
        return explicit.rstrip("/")
    root = request.url_root.rstrip("/")
    # Prefer https in the advertised link unless we're intentionally insecure.
    if not _allow_insecure() and root.startswith("http://"):
        root = "https://" + root[len("http://") :]
    return root


def _purge_expired() -> None:
    cutoff = time.time() - SESSION_TTL
    dead = [n for n, s in _SESSIONS.items() if s["created"] < cutoff]
    for n in dead:
        _SESSIONS.pop(n, None)


def _get(nonce: str) -> dict | None:
    with _LOCK:
        _purge_expired()
        return _SESSIONS.get(nonce)


@app.get("/")
def health():
    return jsonify(
        {
            "service": "wearvian-auth",
            "ok": True,
            "insecure_http_allowed": _allow_insecure(),
        }
    )


@app.post("/session")
def create_session():
    """Watch starts a pairing session and gets a URL to render as a QR code."""
    nonce = secrets.token_urlsafe(_NONCE_BYTES)
    with _LOCK:
        _purge_expired()
        _SESSIONS[nonce] = {
            "created": time.time(),
            "state": "awaiting_login",  # -> awaiting_otp -> ready | error
            "csrf": None,
            "email": None,
            "otp_token": None,
            "tokens": None,
            "error": None,
        }
    base = _base_url()
    return (
        jsonify(
            {
                "nonce": nonce,
                "browser_url": f"{base}{url_for('auth_page', nonce=nonce)}",
                "poll_url": f"{base}{url_for('poll_session', nonce=nonce)}",
                "expires_in": SESSION_TTL,
            }
        ),
        201,
    )


_PAGE = """
<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<title>wearvian sign-in</title><style>
body{font-family:system-ui,sans-serif;max-width:24rem;margin:2rem auto;padding:0 1rem}
input{width:100%;padding:.6rem;margin:.3rem 0 1rem;font-size:1rem;box-sizing:border-box}
button{width:100%;padding:.7rem;font-size:1rem;border:0;border-radius:.4rem;background:#1769ff;color:#fff}
.err{color:#b00020}.ok{color:#0a7d28}small{color:#666}
</style></head><body>
<h2>Sign in to Rivian</h2>
{% if error %}<p class=err>{{ error }}</p>{% endif %}
{% if state == 'ready' %}
  <p class=ok>Signed in. Return to your watch &mdash; it will finish pairing automatically.</p>
{% elif state == 'awaiting_otp' %}
  <form method=post><p>Enter the one-time code Rivian sent you.</p>
  <input name=otp inputmode=numeric autocomplete=one-time-code placeholder="123456" autofocus required>
  <button>Verify</button></form>
{% else %}
  <form method=post>
  <input name=email type=email placeholder=Email autocomplete=username autofocus required>
  <input name=password type=password placeholder=Password autocomplete=current-password required>
  <button>Sign in</button></form>
{% endif %}
<p><small>This page hands a temporary session token back to your watch. Your password is
sent only to Rivian and is never stored here.</small></p>
</body></html>
"""


def _render(session: dict, error: str | None = None):
    return render_template_string(
        _PAGE, state=session["state"], error=error or session.get("error")
    )


@app.route("/a/<nonce>", methods=["GET", "POST"])
def auth_page(nonce: str):
    _require_secure()
    session = _get(nonce)
    if session is None:
        abort(404, "This pairing link has expired. Start again from your watch.")

    if request.method == "GET" or session["state"] == "ready":
        return _render(session)

    client = RivianAuthClient()
    try:
        if session["state"] == "awaiting_login":
            email = (request.form.get("email") or "").strip()
            password = request.form.get("password") or ""
            if not email or not password:
                return _render(session, "Email and password are required."), 400
            csrf = client.create_csrf_token()
            result = client.login(csrf, email, password)
            with _LOCK:
                session["csrf"] = csrf
                session["email"] = email
                if isinstance(result, SessionTokens):
                    session["tokens"] = result
                    session["state"] = "ready"
                else:  # MFA challenge: result is the otpToken
                    session["otp_token"] = result
                    session["state"] = "awaiting_otp"
            return _render(session)

        if session["state"] == "awaiting_otp":
            otp = (request.form.get("otp") or "").strip()
            if not otp:
                return _render(session, "Enter the one-time code."), 400
            tokens = client.login_with_otp(
                session["csrf"], session["email"], otp, session["otp_token"]
            )
            with _LOCK:
                session["tokens"] = tokens
                session["state"] = "ready"
            return _render(session)
    except RivianAuthError as exc:
        return _render(session, f"Sign-in failed: {exc}"), 400

    abort(409, "Unexpected session state.")


@app.get("/session/<nonce>")
def poll_session(nonce: str):
    """Watch polls here. Returns tokens exactly once, then invalidates the nonce."""
    _require_secure()
    with _LOCK:
        _purge_expired()
        session = _SESSIONS.get(nonce)
        if session is None:
            abort(404, "Unknown or expired pairing session.")
        if session["state"] == "ready":
            tokens: SessionTokens = session["tokens"]
            _SESSIONS.pop(nonce, None)  # single-use
            return jsonify(
                {
                    "csrfToken": tokens.csrf_token,
                    "appSessionToken": tokens.app_session_token,
                    "userSessionToken": tokens.user_session_token,
                }
            )
        if session["error"]:
            return jsonify({"status": "error", "error": session["error"]}), 400
        return jsonify({"status": session["state"]}), 202


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    app.run(host="0.0.0.0", port=port, threaded=True)
