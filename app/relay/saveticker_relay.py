"""Two authenticated routes wrapping the existing SaveTicker market service."""
from __future__ import annotations

import hashlib
import logging
import re
import secrets
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from starlette.responses import JSONResponse

from app.services.saveticker_service import SaveTickerService
from .access_log import LOGGER_NAME
from .token_store import valid_token

SCHEMA = "koreainv.saveticker-relay.v1"
HOST = "127.0.0.1"
PORT = 8766
TICKER_PATTERN = re.compile(r"[A-Z][A-Z0-9.\-]{0,9}")
logger = logging.getLogger(LOGGER_NAME)


class RelayStartupError(RuntimeError):
    def __init__(self):
        super().__init__("Relay startup refused")


def validate_host(host):
    if host != HOST:
        raise RelayStartupError()


def path_template(path):
    if path == "/v1/health":
        return "/v1/health"
    if path.startswith("/v1/saveticker/") and "/" not in path[len("/v1/saveticker/"):]:
        return "/v1/saveticker/{ticker}"
    return "unmatched"


class AuthAccessMiddleware:
    def __init__(self, app, token_digest):
        self.app = app
        self.token_digest = token_digest

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        started = time.monotonic()
        status = 500
        async def safe_send(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                message = {**message, "headers": [*message.get("headers", []), (b"cache-control", b"no-store")]}
            await send(message)
        try:
            auth = [value for name, value in scope.get("headers", []) if name.lower() == b"authorization"]
            candidate = auth[0][7:] if len(auth) == 1 and auth[0].startswith(b"Bearer ") else b""
            authorized = secrets.compare_digest(hashlib.sha256(candidate).digest(), self.token_digest)
            if not candidate or not authorized:
                return await JSONResponse({"detail": "Unauthorized"}, status_code=401,
                    headers={"WWW-Authenticate": "Bearer"})(scope, receive, safe_send)
            return await self.app(scope, receive, safe_send)
        finally:
            method = scope.get("method", "")
            if method not in {"GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE"}:
                method = "OTHER"
            logger.info("%s %s %d %.3fms", method, path_template(scope.get("path", "")),
                        status, (time.monotonic() - started) * 1000)


def create_app(token: str, service: SaveTickerService, *, host=HOST):
    validate_host(host)
    if not valid_token(token):
        raise RelayStartupError()

    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            transport = service._session
            service.revoke()
            if transport is not None:
                transport.close()

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None,
                  redirect_slashes=False, lifespan=lifespan)
    app.add_middleware(AuthAccessMiddleware, token_digest=hashlib.sha256(token.encode()).digest())

    @app.get("/v1/health")
    def health():
        return {"schema": SCHEMA, "status": "ok", "saveticker_configured": bool(service.enabled)}

    @app.get("/v1/saveticker/{ticker}")
    def fetch(ticker: str):
        if not TICKER_PATTERN.fullmatch(ticker):
            return JSONResponse({"detail": "Invalid US ticker"}, status_code=400)
        try:
            # The service's own reentrant lock makes cache attribution exact for
            # concurrent callers, without another cache or duplicated TTL policy.
            with service._lock:
                with service._state_lock:
                    before = service._cache.get(ticker)
                snapshot = service.fetch(ticker, "USA")
                with service._state_lock:
                    after = service._cache.get(ticker)
                    # A failed login can leave an expired entry untouched. It is
                    # a hit only when fetch actually returned that snapshot.
                    cache_hit = before is not None and before is after and snapshot == before[1]
                    upstream_snapshot = after is not None and snapshot == after[1]
            return {"schema": SCHEMA, "ticker": ticker, "status": snapshot["status"],
                    "fetched_at": snapshot["fetched_at"] if upstream_snapshot else None, "cache_hit": cache_hit,
                    "sections": snapshot["sections"]}
        except Exception:
            # Never log or serialize arbitrary provider exception text.
            return JSONResponse({"schema": SCHEMA, "ticker": ticker, "status": "unavailable",
                                 "fetched_at": None, "cache_hit": False, "sections": {}}, status_code=503)

    return app
