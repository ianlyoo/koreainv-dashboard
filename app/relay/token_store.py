"""One explicit OS Keychain item; no environment or plaintext fallback."""
from __future__ import annotations

import os
import re
import secrets
from pathlib import Path

from app.credential_store import os_backend

SERVICE = "KoreaInvDashboard.SaveTickerRelay.v1"
ACCOUNT = "relay"


class RelayTokenError(RuntimeError):
    def __init__(self):
        super().__init__("Relay token unavailable or operation refused")


def valid_token(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", value) is not None


def read_token(backend_factory=None):
    try:
        token = (backend_factory or os_backend)().get_password(SERVICE, ACCOUNT)
        if not valid_token(token):
            raise RelayTokenError()
        return token
    except Exception:
        raise RelayTokenError() from None


def store_token(*, rotate=False, backend_factory=None):
    try:
        backend = (backend_factory or os_backend)()
        existing = backend.get_password(SERVICE, ACCOUNT)
        if existing is not None and not rotate:
            raise RelayTokenError()
        token = secrets.token_urlsafe(32)
        backend.set_password(SERVICE, ACCOUNT, token)
        verified = backend.get_password(SERVICE, ACCOUNT)
        if not isinstance(verified, str) or not secrets.compare_digest(verified.encode(), token.encode()):
            raise RelayTokenError()
    except Exception:
        raise RelayTokenError() from None


def export_token(path: Path, *, backend_factory=None):
    """Create a private export without overwriting a file or following a symlink."""
    try:
        token = read_token(backend_factory)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w", encoding="ascii") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(token + "\n")
            output.flush()
            os.fsync(output.fileno())
    except Exception:
        raise RelayTokenError() from None
