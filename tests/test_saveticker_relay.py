"""Offline relay regressions: in-memory Keychain and HTTP transport only."""
from __future__ import annotations

import base64
import copy
import importlib.util
import io
import logging
import os
import plistlib
import stat
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock

import pytest
import requests
from fastapi.testclient import TestClient

from app.relay import __main__ as entrypoint
from app.relay.access_log import LOGGER_NAME, LOG_BACKUPS, MAX_LOG_BYTES, configure_access_log
from app.relay.saveticker_relay import SCHEMA, RelayStartupError, create_app
from app.relay.token_store import ACCOUNT, SERVICE, RelayTokenError, export_token, read_token, store_token
from app.services.saveticker_service import SECTIONS, SaveTickerService

ROOT = Path(__file__).resolve().parents[1]
TOKEN = base64.urlsafe_b64encode(bytes(range(32))).rstrip(b"=").decode()
PASSWORD = "synthetic-upstream-password"
COOKIE = "synthetic-upstream-cookie"
EMAIL = "synthetic@example.invalid"
PAYLOADS = {
    "header": {"price": 123.4, "marketCap": 1000},
    "key_metrics": {"per": {"value": 20}},
    "revenue": {"quarters": []},
    "analyst": {"analystCount": 0, "recent": []},
    "insider": {"buyCount": 0, "sellCount": 0, "recent": []},
    "options": {"optionable": False, "volume": 0},
    "news": {"news_list": [{"id": 1, "title": "Synthetic market headline", "content": PASSWORD}]},
}


@pytest.fixture
def upstream():
    session = Mock(headers={}, cookies=requests.cookies.RequestsCookieJar())
    def login(*args, **kwargs):
        session.cookies.set("access_token", COOKIE)
        return Mock(status_code=200, json=lambda: {"user_info": {"email": EMAIL}})
    def fetch(url, **kwargs):
        section = next(k for k, suffix in SECTIONS.items() if url.endswith("/" + suffix) or k == "news" and "/api/news/company?" in url)
        raw = copy.deepcopy(PAYLOADS[section])
        raw.update(email=EMAIL, password=PASSWORD, cookie=COOKIE, headers={"Authorization": TOKEN})
        return Mock(status_code=200, json=lambda: raw)
    session.post.side_effect = login
    session.get.side_effect = fetch
    return SaveTickerService(EMAIL, PASSWORD, session=session), session


def headers(token=TOKEN):
    return {"Authorization": "Bearer " + token}


@pytest.mark.parametrize("path", ["/v1/health", "/v1/saveticker/AAPL", "/unknown"])
@pytest.mark.parametrize("auth", [None, "Bearer wrong", "Basic " + TOKEN, "Bearer " + TOKEN + "extra"])
def test_all_requests_require_exact_bearer_token(upstream, path, auth):
    service, session = upstream
    with TestClient(create_app(TOKEN, service)) as client:
        result = client.get(path, headers={} if auth is None else {"Authorization": auth})
        assert result.status_code == 401 and result.headers["www-authenticate"] == "Bearer"
        assert result.headers["cache-control"] == "no-store"
        session.post.assert_not_called()
        session.get.assert_not_called()


def test_only_two_get_routes_health_is_passive_and_duplicates_fail(upstream):
    service, session = upstream
    app = create_app(TOKEN, service)
    assert {route.path for route in app.routes} == {"/v1/health", "/v1/saveticker/{ticker}"}
    assert all(route.methods == {"GET"} for route in app.routes)
    with TestClient(app) as client:
        assert client.get("/v1/health", headers=headers()).json() == {
            "schema": SCHEMA, "status": "ok", "saveticker_configured": True}
        assert client.get("/v1/health", headers=[("Authorization", "Bearer " + TOKEN)] * 2).status_code == 401
        for path in ("/unknown", "/docs", "/redoc", "/openapi.json", "/static/a", "/v1/health/"):
            assert client.get(path, headers=headers()).status_code == 404
        assert client.post("/v1/health", headers=headers()).status_code == 405
        session.post.assert_not_called()
        session.get.assert_not_called()


@pytest.mark.parametrize("ticker", ["aapl", "1AAPL", "A_A", "A$", "A" * 11, "A B", "한글"])
def test_bad_tickers_are_rejected_before_the_provider(upstream, ticker):
    service, session = upstream
    with TestClient(create_app(TOKEN, service)) as client:
        assert client.get("/v1/saveticker/" + ticker, headers=headers()).status_code == 400
        session.post.assert_not_called()
        session.get.assert_not_called()


@pytest.mark.parametrize("ticker", ["AAPL", "BRK.B", "BRK-B", "A123456789"])
def test_us_ticker_pattern_and_sanitized_contract(upstream, ticker):
    service, session = upstream
    with TestClient(create_app(TOKEN, service)) as client:
        result = client.get("/v1/saveticker/" + ticker, headers=headers())
        assert result.status_code == 200
        data = result.json()
        assert set(data) == {"schema", "ticker", "status", "fetched_at", "cache_hit", "sections", "section_status"}
        assert data["schema"] == SCHEMA and data["ticker"] == ticker and data["status"] == "available"
        assert data["cache_hit"] is False
        assert data["fetched_at"].endswith("+00:00")
        assert data["sections"] == service.fetch(ticker, "USA")["sections"]
        assert data["section_status"] == dict.fromkeys(data["sections"], "available")
        assert session.get.call_count == 7 and session.post.call_count == 1
        assert all(value not in result.text for value in (EMAIL, PASSWORD, COOKIE, TOKEN))


def test_cache_hit_keeps_upstream_timestamp_and_expiry_refreshes(upstream, monkeypatch):
    service, session = upstream
    clock = [10.0]
    monkeypatch.setattr("app.services.saveticker_service.time.monotonic", lambda: clock[0])
    with TestClient(create_app(TOKEN, service)) as client:
        first = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        clock[0] += 299
        second = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        assert first["cache_hit"] is False and second["cache_hit"] is True
        assert first["fetched_at"] == second["fetched_at"] == service._cache["AAPL"][1]["fetched_at"]
        assert first["sections"] == second["sections"] and session.get.call_count == 7
        assert first["section_status"] == second["section_status"]
        clock[0] += 1
        assert client.get("/v1/saveticker/AAPL", headers=headers()).json()["cache_hit"] is False
        assert session.get.call_count == 14


def test_concurrent_calls_reuse_existing_singleflight_and_report_one_miss(upstream):
    service, session = upstream
    with TestClient(create_app(TOKEN, service)) as client:
        with ThreadPoolExecutor(max_workers=8) as pool:
            outputs = list(pool.map(lambda _: client.get("/v1/saveticker/AAPL", headers=headers()).json(), range(8)))
        assert sum(not result["cache_hit"] for result in outputs) == 1
        assert len({result["fetched_at"] for result in outputs}) == 1
        assert session.get.call_count == 7 and session.post.call_count == 1


def test_expired_entry_is_not_a_cache_hit_when_refresh_login_fails(upstream, monkeypatch):
    service, session = upstream
    with TestClient(create_app(TOKEN, service)) as client:
        client.get("/v1/saveticker/AAPL", headers=headers())
        previous = service._cache["AAPL"]
        service._cache["AAPL"] = (previous[0] - 301, previous[1])
        service._authenticated = False
        session.post.side_effect = lambda *args, **kwargs: Mock(status_code=403)
        result = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        assert result["status"] == "unavailable" and result["cache_hit"] is False
        assert result["fetched_at"] is None
        assert session.get.call_count == 7 and session.post.call_count == 2


def test_section_status_preserves_partial_errors_and_missing_coverage_on_cache_hit(upstream):
    service, session = upstream
    original = session.get.side_effect
    def partial(url, **kwargs):
        if url.endswith("/key-metrics"):
            return Mock(status_code=500)
        if url.endswith("/revenue-trend"):
            return Mock(status_code=404)
        return original(url, **kwargs)
    session.get.side_effect = partial
    with TestClient(create_app(TOKEN, service)) as client:
        first = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        cached = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        expected = {**dict.fromkeys(SECTIONS, "available"), "key_metrics": "error", "revenue": "unavailable"}
        assert first["status"] == cached["status"] == "partial"
        assert first["section_status"] == cached["section_status"] == expected
        assert set(first["section_status"]) == set(first["sections"])
        assert first["sections"]["key_metrics"] is None and first["sections"]["revenue"] is None
        assert first["cache_hit"] is False and cached["cache_hit"] is True
        assert session.get.call_count == 7


def test_section_status_refuses_non_enum_values_without_echo(upstream, monkeypatch):
    service, session = upstream
    monkeypatch.setattr(service, "fetch", Mock(return_value={"status": "available", "fetched_at": None,
        "sections": {"header": {"price": 1}}, "section_status": {"header": PASSWORD}}))
    with TestClient(create_app(TOKEN, service)) as client:
        response = client.get("/v1/saveticker/AAPL", headers=headers())
        assert response.status_code == 503
        assert response.json()["sections"] == response.json()["section_status"] == {}
        assert PASSWORD not in response.text


@pytest.mark.parametrize("code", [401, 403, 429])
def test_upstream_failures_cooldown_and_reauthentication_are_unchanged(upstream, code):
    service, session = upstream
    session.get.side_effect = lambda *args, **kwargs: Mock(status_code=code)
    with TestClient(create_app(TOKEN, service)) as client:
        first = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        cached = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        other = client.get("/v1/saveticker/MSFT", headers=headers()).json()
        assert first["status"] == cached["status"] == other["status"] == "unavailable"
        assert first["cache_hit"] is False and cached["cache_hit"] is True and other["cache_hit"] is False
        assert first["fetched_at"] == cached["fetched_at"]
        assert first["section_status"] == cached["section_status"] == {
            **dict.fromkeys(SECTIONS, "unavailable"), "header": "error"}
        assert other["section_status"] == dict.fromkeys(other["sections"], "unavailable")
        assert other["fetched_at"] is None
        assert session.post.call_count == (2 if code == 401 else 1)
        assert session.get.call_count == (2 if code == 401 else 1)


def test_logs_never_include_raw_paths_queries_tokens_or_provider_errors(upstream, caplog, monkeypatch):
    service, session = upstream
    log = logging.getLogger(LOGGER_NAME)
    monkeypatch.setattr(log, "propagate", True)
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    with TestClient(create_app(TOKEN, service)) as client:
        response = client.get("/v1/saveticker/AAPL", headers=headers(), params={"private": TOKEN})
        assert response.status_code == 200
        client.get("/" + PASSWORD, headers=headers(), params={"private": COOKIE})
        monkeypatch.setattr(service, "fetch", Mock(side_effect=RuntimeError(PASSWORD + COOKIE + TOKEN)))
        failed = client.get("/v1/saveticker/MSFT", headers=headers())
        assert failed.status_code == 503 and failed.json()["fetched_at"] is None
        assert failed.json()["sections"] == failed.json()["section_status"] == {}
    records = [r for r in caplog.records if r.name == LOGGER_NAME]
    assert len(records) == 3
    assert all(len(r.getMessage().splitlines()) == 1 for r in records)
    assert "/v1/saveticker/{ticker}" in records[0].getMessage()
    assert "unmatched" in records[1].getMessage()
    assert "503" in records[2].getMessage()
    captured = response.text + failed.text + "\n".join(r.getMessage() for r in records)
    assert all(secret not in captured for secret in (EMAIL, PASSWORD, COOKIE, TOKEN))


def test_health_unconfigured_and_shutdown_clears_memory():
    service = SaveTickerService(enabled=False)
    with TestClient(create_app(TOKEN, service)) as client:
        assert client.get("/v1/health", headers=headers()).json()["saveticker_configured"] is False
        result = client.get("/v1/saveticker/AAPL", headers=headers()).json()
        assert result["status"] == "disabled" and result["fetched_at"] is None
        assert result["section_status"] == dict.fromkeys(result["sections"], "unavailable")
    assert service._credentials is None and service._session is None and service.enabled is False


@pytest.mark.parametrize("token", [None, "", "short", "x" * 44])
def test_missing_or_invalid_token_refuses_startup(token, upstream):
    with pytest.raises(RelayStartupError):
        create_app(token, upstream[0])


@pytest.mark.parametrize("host", ["0.0.0.0", "::", "::1", "localhost", "127.0.0.2"])
def test_non_pinned_host_refuses_before_loading_secrets(host):
    reader = Mock()
    with pytest.raises(RelayStartupError):
        entrypoint.run(host=host, token_reader=reader)
    reader.assert_not_called()


@pytest.mark.parametrize("item", ["token", "saveticker"])
def test_startup_keychain_refusal_exits_zero_without_relaunch_or_server(monkeypatch, capsys, item):
    reader = Mock(return_value=TOKEN)
    store = Mock()
    store.read.side_effect = RuntimeError(PASSWORD + TOKEN)
    if item == "token":
        reader.side_effect = RelayTokenError()
    monkeypatch.setattr(entrypoint, "read_token", reader)
    monkeypatch.setattr(entrypoint, "credential_store", store)
    server = Mock()
    monkeypatch.setattr(entrypoint.uvicorn, "run", server)
    assert entrypoint.main([]) == 0
    server.assert_not_called()
    assert reader.call_count == 1
    assert store.read.call_count == (0 if item == "token" else 1)
    store.write.assert_not_called()
    store.migrate_env.assert_not_called()
    captured = capsys.readouterr()
    assert captured.err == "Relay startup refused\n" and captured.out == ""


def test_headless_entrypoint_import_isolation_in_fresh_process(tmp_path):
    script = '''
import runpy, sys
from types import SimpleNamespace
import app.credential_store as credentials
import app.relay.token_store as tokens
import app.relay.access_log as access_log
import uvicorn
tokens.read_token = lambda: "a" * 43
credentials.credential_store = SimpleNamespace(read=lambda: None)
access_log.configure_access_log = lambda *args: None
def serve(app, **kwargs):
    assert kwargs["host"] == "127.0.0.1" and kwargs["port"] == 8766
    assert kwargs["access_log"] is False and kwargs["log_config"] is None
    allowed = {"app", "app.credential_store", "app.relay", "app.relay.access_log",
               "app.relay.saveticker_relay", "app.relay.token_store", "app.runtime_paths",
               "app.services", "app.services.insight_schema", "app.services.saveticker_service", "app.version"}
    loaded = {name for name in sys.modules if name == "app" or name.startswith("app.")}
    assert loaded == allowed, (sorted(loaded - allowed), sorted(allowed - loaded))
    assert "AppKit" not in sys.modules and "pystray" not in sys.modules and "central_server" not in sys.modules
    print("isolation_ok")
uvicorn.run = serve
sys.argv = ["app.relay"]
runpy.run_module("app.relay", run_name="__main__")
'''
    env = {**os.environ, "XDG_DATA_HOME": str(tmp_path)}
    result = subprocess.run([sys.executable, "-c", script], cwd=ROOT, env=env, capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr
    assert result.stdout == "isolation_ok\n"


def backend():
    items = {}
    store = Mock()
    store.get_password.side_effect = lambda service, account: items.get((service, account))
    store.set_password.side_effect = lambda service, account, value: items.__setitem__((service, account), value)
    return store, items


def test_token_generation_rotation_uses_only_explicit_os_item(tmp_path):
    store, items = backend()
    with pytest.raises(RelayTokenError):
        read_token(lambda: store)
    store_token(backend_factory=lambda: store)
    first = read_token(lambda: store)
    assert len(base64.urlsafe_b64decode(first + "=")) == 32
    assert set(items) == {(SERVICE, ACCOUNT)}
    with pytest.raises(RelayTokenError):
        store_token(backend_factory=lambda: store)
    store_token(rotate=True, backend_factory=lambda: store)
    assert read_token(lambda: store) != first
    export = tmp_path / "relay-export"
    export_token(export, backend_factory=lambda: store)
    assert stat.S_IMODE(export.stat().st_mode) == 0o600
    assert export.read_text().strip() == read_token(lambda: store)
    with pytest.raises(RelayTokenError):
        export_token(export, backend_factory=lambda: store)
    target = tmp_path / "target"
    target.write_text("keep")
    link = tmp_path / "link"
    link.symlink_to(target)
    with pytest.raises(RelayTokenError):
        export_token(link, backend_factory=lambda: store)
    assert target.read_text() == "keep"


def test_token_backend_error_does_not_expose_secret():
    factory = Mock(side_effect=RuntimeError(PASSWORD + TOKEN))
    with pytest.raises(RelayTokenError) as caught:
        read_token(factory)
    assert PASSWORD not in str(caught.value) and TOKEN not in str(caught.value)


def test_token_cli_prints_only_stored_and_accepts_no_token_argument(tmp_path, capsys):
    spec = importlib.util.spec_from_file_location("relay_token_cli", ROOT / "scripts/mac/relay_token.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    store, items = backend()
    assert module.main(["create"], backend_factory=lambda: store) == 0
    assert capsys.readouterr().out == "stored\n"
    assert module.main(["export", str(tmp_path / "export")], backend_factory=lambda: store) == 0
    assert capsys.readouterr().out == "stored\n"
    assert module.main(["rotate"], backend_factory=lambda: store) == 0
    assert capsys.readouterr().out == "stored\n"
    assert set(items) == {(SERVICE, ACCOUNT)}
    with pytest.raises(SystemExit) as caught:
        module.main(["create", "--token", TOKEN], backend_factory=lambda: store)
    assert caught.value.code == 2
    assert capsys.readouterr().err == "Relay token arguments refused\n"


def test_relay_cli_refuses_token_or_host_arguments_without_echo(capsys):
    with pytest.raises(SystemExit) as caught:
        entrypoint.main(["--token", TOKEN, "--host", "0.0.0.0"])
    assert caught.value.code == 2
    assert capsys.readouterr().err == "Relay arguments refused\n"


def test_private_logs_rotate_at_cap_and_mirror_one_line_to_stderr(tmp_path, monkeypatch):
    directory = tmp_path / "private"
    logfile = directory / "access.log"
    stderr = io.StringIO()
    monkeypatch.setattr(sys, "stderr", stderr)
    log = logging.getLogger(LOGGER_NAME)
    old_handlers, old_propagate, old_level = log.handlers[:], log.propagate, log.level
    # Preserve any test-owned handlers while exercising the production setup.
    log.handlers = []
    logger = configure_access_log(logfile)
    handler = logger.handlers[0]
    assert handler.maxBytes == MAX_LOG_BYTES and handler.backupCount == LOG_BACKUPS
    handler.maxBytes = 180
    try:
        for _ in range(80):
            logger.info("GET /v1/saveticker/{ticker} 200 1.000ms")
        handler.flush()
        files = sorted(directory.iterdir())
        assert len(files) == 3
        assert all(stat.S_IMODE(path.stat().st_mode) == 0o600 and path.stat().st_size <= 180 for path in files)
        assert len(stderr.getvalue().splitlines()) == 80
    finally:
        for current in logger.handlers:
            current.close()
        logger.handlers = old_handlers
        logger.propagate = old_propagate
        logger.setLevel(old_level)


def test_logging_refuses_symlink_and_public_directory(tmp_path):
    public = tmp_path / "public"
    public.mkdir()
    public.chmod(0o755)
    with pytest.raises(ValueError):
        configure_access_log(public / "log")
    private = tmp_path / "private"
    private.mkdir(mode=0o700)
    victim = tmp_path / "victim"
    victim.write_text("keep")
    (private / "log").symlink_to(victim)
    with pytest.raises(ValueError):
        configure_access_log(private / "log")
    assert victim.read_text() == "keep"


def test_launchagent_and_release_operations_contract():
    template = (ROOT / "scripts/mac/company.koreainv.saveticker-relay.plist.template").read_bytes()
    data = plistlib.loads(template)
    assert data["ProgramArguments"] == ["__PINNED_VENV_PYTHON__", "-m", "app.relay"]
    assert data["WorkingDirectory"] == "__PINNED_CHECKOUT__"
    assert data["RunAtLoad"] and data["KeepAlive"] == {"SuccessfulExit": False} and data["Umask"] == 0o077
    assert data["ThrottleInterval"] == 30
    assert data["StandardOutPath"] == data["StandardErrorPath"] == "/dev/null"
    docs = (ROOT / "docs/saveticker-relay.md").read_text()
    for text in ("--copies", "resolve(strict=True)", "launchctl bootout gui/$UID/company.koreainv.saveticker-relay",
                 "tailscale serve --https=8766 off", "6767", "lsof -nP -iTCP -sTCP:LISTEN", "rotate",
                 "allow access", "opt-in", "GUI is unchanged"):
        assert text in docs


def test_runbook_stops_on_both_keychain_prompts_and_vets_before_bootstrap():
    docs = (ROOT / "docs/saveticker-relay.md").read_text()
    stop = docs.split("### Keychain prompt rule", 1)[1].split("\nPin the release", 1)[0]
    for text in ("KoreaInvDashboard.SaveTicker.v1", "KoreaInvDashboard.SaveTickerRelay.v1",
                 "**Deny**", "launchctl bootout gui/$UID/company.koreainv.saveticker-relay", "Report to the PM",
                 "Always Allow", "Keychain Access", "`security`", "re-enter", "re-save", "Re-vet access"):
        assert text in stop
    # Exactly one owner-authorized grant: the pinned interpreter reading the SaveTicker item.
    assert "$relay_checkout/.venv/bin/python" in stop and "new owner decision" in stop
    foreground = docs.index('"$relay_python" -m app.relay')
    assert foreground < docs.index("launchctl bootstrap")
    assert "Ctrl-C" in docs and "refused start exits with code 0" in docs
    assert "one GUI insight fetch and one relay fetch back to back" in " ".join(docs.split())
    assert "confirm both stay logged in" in docs and "do not use them concurrently" in docs
    rotation = docs.split("To rotate,", 1)[1].split("## Uninstall", 1)[0]
    assert "install the new credential on Oracle and restart `ticker-research`" in rotation
