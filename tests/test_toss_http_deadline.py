from __future__ import annotations

import socket
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from app import toss_api_client as toss


class SlowPeer:
    """Real HTTP peer; records only timings/counters, never request credentials."""
    def __init__(self, mode="body"):
        self.mode = mode
        self.started = threading.Event()
        self.closed = threading.Event()
        self.stop = threading.Event()
        self.count = 0
        self.threads = []
        peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                self.do_GET()

            def do_GET(self):
                peer.threads.append(threading.current_thread())
                peer.count += 1
                if self.path == "/api/v1/accounts":
                    body = b'{"result": [{"accountSeq": 9}]}'
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                try:
                    if peer.mode == "headers":
                        self.connection.sendall(b"HTTP/1.1 200 OK\r\nX-Slow: ")
                    else:
                        self.send_response(200)
                        self.send_header("Content-Length", "100000")
                        self.end_headers()
                    peer.started.set()
                    while not peer.stop.wait(0.02):
                        self.connection.sendall(b" " if peer.mode == "body" else b"a")
                except (BrokenPipeError, ConnectionResetError, OSError):
                    peer.closed.set()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)

    def __enter__(self):
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        return self

    def __exit__(self, *args):
        self.stop.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        for thread in self.threads:
            thread.join(2)
        assert not self.thread.is_alive()
        assert all(not thread.is_alive() for thread in self.threads)


class TossHttpDeadlineTests(unittest.TestCase):
    def setUp(self):
        toss.clear_token_cache()
        self.scope = toss._scope_key("fake-client", "fake-secret")
        with toss._token_lock:
            toss._token_cache[self.scope] = ("cached-fake", time.time() + 3600)

    def quote(self, work):
        return toss._get_historical_usd_mid_rate(
            "fake-client", "fake-secret", "2026-06-01T10:00:00Z", work=work)

    def assert_body_deadline(self, *, overall):
        with SlowPeer() as peer, patch.object(toss, "BASE_URL", peer.url), patch.object(toss, "TAX_FX_REQUEST_SECONDS", 5 if overall else 0.4):
            start = time.monotonic()
            work = toss.FxBudget(seconds=0.8 if overall else 5)
            self.assertEqual(self.quote(work), 0)
            elapsed = time.monotonic() - start
            limit = 0.8 if overall else 0.25 + 0.4  # includes quote throttling
            self.assertTrue(peer.started.is_set())
            self.assertLess(elapsed, limit + 0.7)
            self.assertTrue(peer.closed.wait(0.7), "cancelled body socket remained open")
            self.assertEqual(work.requests, 1)
            print(f"HTTP {'hydration' if overall else 'request'} body deadline: {elapsed:.3f}s (limit {limit:.2f}s)")

    def test_slow_drip_whole_body_obeys_request_deadline(self):
        self.assert_body_deadline(overall=False)

    def test_slow_drip_cannot_exceed_overall_hydration_deadline(self):
        self.assert_body_deadline(overall=True)

    def test_slow_headers_are_also_inside_total_deadline(self):
        with SlowPeer("headers") as peer, patch.object(toss, "BASE_URL", peer.url):
            start = time.monotonic()
            self.assertEqual(self.quote(toss.FxBudget(seconds=0.8)), 0)
            elapsed = time.monotonic() - start
            self.assertTrue(peer.started.is_set())
            self.assertLess(elapsed, 1.5)
            self.assertTrue(peer.closed.wait(0.7))
            print(f"HTTP headers deadline: {elapsed:.3f}s (limit 0.80s)")

    def cancel_peer(self, *, token=False, headers=False):
        event = threading.Event()
        finished = threading.Event()
        failures = []
        with SlowPeer("headers" if headers else "body") as peer, patch.object(toss, "BASE_URL", peer.url):
            work = toss.FxBudget(event)
            def load():
                try:
                    if token:
                        toss.get_access_token("fake-client", "fake-secret", force=True, work=work)
                    else:
                        self.quote(work)
                except Exception as exc:
                    failures.append(exc)
                finally:
                    finished.set()
            worker = threading.Thread(target=load)
            worker.start()
            try:
                self.assertTrue(peer.started.wait(2))
                start = time.monotonic()
                event.set()
                self.assertTrue(finished.wait(0.7), "HTTP worker continued after cancellation")
                elapsed = time.monotonic() - start
                self.assertLess(elapsed, 0.7)
                self.assertTrue(peer.closed.wait(0.7))
                self.assertEqual(len(failures), 1)
                self.assertIsInstance(failures[0], toss.TaxWorkCancelled)
                print(f"HTTP {'token' if token else 'headers' if headers else 'body'} cancel drain: {elapsed:.3f}s")
            finally:
                event.set()
                worker.join(2)
            self.assertFalse(worker.is_alive())

    def test_body_cancellation_closes_transport_promptly(self):
        self.cancel_peer()

    def test_headers_cancellation_closes_transport_promptly(self):
        self.cancel_peer(headers=True)

    def test_token_body_cancellation_closes_transport_promptly(self):
        self.cancel_peer(token=True)

    def test_cached_ordinary_request_does_not_wait_for_hanging_refresh(self):
        event = threading.Event()
        errors = []
        with SlowPeer() as peer, patch.object(toss, "BASE_URL", peer.url):
            def refresh():
                try:
                    toss.get_access_token("fake-client", "fake-secret", force=True, work=toss.FxBudget(event))
                except toss.TaxWorkCancelled:
                    pass
                except Exception as exc:
                    errors.append(exc)
            worker = threading.Thread(target=refresh)
            worker.start()
            try:
                self.assertTrue(peer.started.wait(2))
                start = time.monotonic()
                self.assertEqual(toss.get_accounts("fake-client", "fake-secret"), [{"accountSeq": 9}])
                elapsed = time.monotonic() - start
                self.assertLess(elapsed, 0.5)
                self.assertTrue(worker.is_alive(), "refresh must still be in flight during ordinary lookup")
                print(f"HTTP cached-token ordinary request during refresh: {elapsed:.3f}s")
            finally:
                event.set()
                worker.join(0.7)
            self.assertFalse(worker.is_alive())
            self.assertEqual(errors, [])

    def test_late_refresh_does_not_overwrite_new_valid_cached_token(self):
        entered = threading.Event()
        release = threading.Event()
        response = type("FakeTokenResponse", (), {"status_code": 200, "json": lambda self: {"access_token": "late-fake", "expires_in": 3600}})()
        def pending(*args, **kwargs):
            entered.set()
            if not release.wait(2):
                raise AssertionError("refresh was not released")
            return response
        results = []
        with patch.object(toss.requests, "post", side_effect=pending):
            worker = threading.Thread(target=lambda: results.append(toss.get_access_token("fake-client", "fake-secret", force=True)))
            worker.start()
            try:
                self.assertTrue(entered.wait(1))
                with toss._token_lock:
                    toss._token_cache[self.scope] = ("new-fake", time.time() + 3600)
            finally:
                release.set()
                worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(results, ["new-fake"])
        self.assertEqual(toss._token_cache[self.scope][0], "new-fake")

    def test_dns_wait_is_cancelled_without_a_later_http_request(self):
        entered = threading.Event()
        release = threading.Event()
        dns_finished = threading.Event()
        real_lookup = socket.getaddrinfo
        def slow_dns(*args, **kwargs):
            entered.set()
            try:
                if not release.wait(2):
                    raise AssertionError("DNS was not released")
                return real_lookup(*args, **kwargs)
            finally:
                dns_finished.set()
        with SlowPeer() as peer, patch.object(socket, "getaddrinfo", side_effect=slow_dns):
            url = peer.url.replace("127.0.0.1", "localhost") + "/api/v1/accounts"
            start = time.monotonic()
            try:
                with self.assertRaises(TimeoutError):
                    toss._bounded_request("GET", url, work=toss.FxBudget(seconds=0.4))
                self.assertTrue(entered.is_set())
                self.assertLess(time.monotonic() - start, 1.1)
            finally:
                release.set()
                self.assertTrue(dns_finished.wait(1))
            time.sleep(0.05)
            self.assertEqual(peer.count, 0)
