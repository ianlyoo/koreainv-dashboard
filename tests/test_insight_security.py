"""No network, OS keyring, or real credential access in security regression tests."""
import asyncio
import copy
import json
import os
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
import requests
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request
from app.routes import insight
from app.session_store import SessionData, create_session, active_sessions, destroy_session, clear_all_sessions
from app.services.saveticker_service import SaveTickerService, InsightRevoked, empty_snapshot
from app.services.insight_schema import normalize_section, SCHEMAS
from app.credential_store import CredentialStore, CredentialStoreError, os_backend


def new_session():
    sid = create_session(SessionData('test', 'test', '12345678', '01'))
    request = Request({'type': 'http', 'headers': [(b'cookie', ('session=' + sid).encode())]})
    return sid, request, active_sessions[sid].insight

class BoundaryTests(unittest.TestCase):
    def tearDown(self):
        clear_all_sessions()

    def test_unauthenticated_warm_and_cold_are_http_401_without_providers(self):
        app = FastAPI()
        app.include_router(insight.router)
        sid, _, context = new_session()
        with patch.object(context.service, 'fetch') as provider, patch.object(insight, '_resolve_yf_ticker') as yahoo, TestClient(app) as client:
            for warm in (False, True):
                if warm:
                    context.cache['USA:AVGO'] = {'ts': time.time(), 'data': {'secret': 'must not return'}}
                self.assertEqual(client.get('/api/asset-insight?ticker=AVGO').status_code, 401)
                self.assertEqual(client.get('/api/saveticker/connection').status_code, 401)
            provider.assert_not_called()
            yahoo.assert_not_called()

    def test_logout_cancels_waiter_without_yahoo_cache_or_resurrection(self):
        async def scenario():
            sid, request, context = new_session()
            started, release = threading.Event(), threading.Event()
            def slow():
                started.set()
                release.wait(2)
                return empty_snapshot('unavailable')
            with patch.object(context.service, 'fetch', side_effect=lambda *args: slow()), patch.object(insight, '_resolve_yf_ticker') as yahoo:
                pending = asyncio.create_task(insight.get_asset_insight(request, 'AVGO'))
                while not started.is_set():
                    await asyncio.sleep(.001)
                before = time.monotonic()
                destroy_session(sid)
                self.assertLess(time.monotonic() - before, .1)
                with self.assertRaises(HTTPException) as caught:
                    await pending
                self.assertEqual(caught.exception.status_code, 401)
                release.set()
                await asyncio.sleep(.02)
                self.assertFalse(context.cache)
                yahoo.assert_not_called()
        asyncio.run(scenario())

    def test_two_concurrent_sessions_do_not_share_results_and_logout_is_exact(self):
        async def scenario():
            one, req1, ctx1 = new_session()
            two, req2, ctx2 = new_session()
            first, second = empty_snapshot('partial'), empty_snapshot('partial')
            first['sections']['header'] = {'price': 11}
            second['sections']['header'] = {'price': 22}
            with patch.object(ctx1.service, 'fetch', return_value=first), patch.object(ctx2.service, 'fetch', return_value=second):
                a, b = await asyncio.gather(insight.get_asset_insight(req1, 'AVGO'), insight.get_asset_insight(req2, 'AVGO'))
                self.assertEqual(a['data']['financials']['currentPrice'], 11)
                self.assertEqual(b['data']['financials']['currentPrice'], 22)
                destroy_session(one)
                self.assertFalse(ctx1.cache)
                self.assertEqual((await insight.get_asset_insight(req2, 'AVGO')), b)
                self.assertFalse(ctx2.revoked)
        asyncio.run(scenario())

    def test_clear_cache_invalidates_pending_result_but_keeps_connection(self):
        async def scenario():
            _, request, context = new_session()
            service = SaveTickerService('sample@example.invalid', 'sample')
            context.replace_service(service)
            started, release = threading.Event(), threading.Event()
            def fetch(*args):
                started.set(); release.wait(2)
                return empty_snapshot('partial')
            with patch.object(service, 'fetch', side_effect=fetch):
                pending = asyncio.create_task(insight.get_asset_insight(request, 'AVGO'))
                while not started.is_set(): await asyncio.sleep(.001)
                context.clear_cache()
                release.set()
                with self.assertRaises(HTTPException): await pending
                self.assertTrue(context.service.enabled)
                self.assertFalse(context.cache)
        asyncio.run(scenario())

    def test_late_login_and_fetch_cannot_repopulate_cookies_or_cache(self):
        for phase in ('login', 'fetch'):
            started, release = threading.Event(), threading.Event()
            transport = Mock(headers={}, cookies=requests.cookies.RequestsCookieJar())
            def login(*args, **kwargs):
                if phase == 'login': started.set(); release.wait(2)
                transport.cookies.set('access_token', 'synthetic')
                return Mock(status_code=200, json=lambda: {'user_info': {}})
            def fetch(*args, **kwargs):
                started.set(); release.wait(2)
                transport.cookies.set('late_cookie', 'synthetic')
                return Mock(status_code=200, json=lambda: {'price': 1})
            transport.post.side_effect = login
            transport.get.side_effect = fetch
            service = SaveTickerService('example@example.invalid', 'test', session=transport)
            with ThreadPoolExecutor() as pool:
                pending = pool.submit(service.fetch, 'AVGO')
                self.assertTrue(started.wait(1))
                before = time.monotonic(); service.revoke()
                self.assertLess(time.monotonic() - before, .1)
                release.set()
                with self.assertRaises(InsightRevoked): pending.result(2)
            self.assertIsNone(service._credentials)
            self.assertIsNone(service._session)
            self.assertFalse(service._cache)
            self.assertFalse(transport.cookies)
            with self.assertRaises(InsightRevoked): service.fetch('AVGO')

    def test_nested_allowlists_strip_unknown_everywhere_and_validate_bounds(self):
        def sample(schema):
            if isinstance(schema, dict): return {**{k: sample(v) for k,v in schema.items()}, 'account': {'email': 'SECRET'}}
            if isinstance(schema, list): return [sample(schema[0])]
            return True if schema == 'boolean' else 0 if schema in ('number', 'timestamp') else 'safe'
        for name, schema in SCHEMAS.items():
            cleaned = normalize_section(name, sample(schema))
            self.assertNotIn('SECRET', json.dumps(cleaned))
            self.assertNotIn('account', json.dumps(cleaned))
        for value in (float('nan'), True, 1e100, 'SECRET'):
            with self.assertRaises(ValueError): normalize_section('header', {'price': value})
        with self.assertRaises(ValueError): normalize_section('insider', {'recent': [{}] * 1001})


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.items = {}
        self.backend = Mock()
        self.backend.set_password.side_effect = lambda s, k, v: self.items.__setitem__((s,k),v)
        self.backend.get_password.side_effect = lambda s, k: self.items.get((s,k))
        self.backend.delete_password.side_effect = lambda s,k: self.items.pop((s,k), None)
        self.store = CredentialStore(self.path/'profile.json', lambda: self.backend)
        self.env = self.path / 'sample.env'
        self.original = '# hello\r\nOTHER="a=b"\r\nSAVETICKER_EMAIL="sample@example.invalid"\r\nexport SAVETICKER_PASSWORD="sample-secret"\r\nMULTI="first\nsecond"\r\nTAIL=yes\r\n'
        self.env.write_bytes(self.original.encode())

    def test_write_read_delete_and_random_metadata_no_secrets(self):
        self.store.write('sample@example.invalid', 'sample-secret')
        self.assertEqual(self.store.read(), {'email':'sample@example.invalid', 'password':'sample-secret'})
        metadata = self.store.path.read_text()
        self.assertNotIn('sample', metadata)
        self.assertEqual(self.store.path.stat().st_mode & 0o777, 0o600)
        self.store.delete()
        self.assertIsNone(self.store.read())
        self.assertFalse(self.items)

    def test_windows_atomic_write_does_not_require_unix_fchmod(self):
        # A Windows-shaped os proxy deliberately has no fchmod or directory-open API.
        windows_os = SimpleNamespace(name='nt', fdopen=os.fdopen, fsync=os.fsync,
                                     replace=os.replace, path=os.path, unlink=os.unlink)
        with patch('app.credential_store.os', windows_os):
            self.store.write('sample@example.invalid', 'sample-secret')
        self.assertEqual(self.store.read()['password'], 'sample-secret')
        self.assertNotIn('sample-secret', self.store.path.read_text())
        self.assertEqual(sorted(p.name for p in self.path.iterdir()), ['profile.json', 'sample.env'])

    def test_successful_migration_preserves_other_bytes_and_is_idempotent(self):
        with patch.dict(os.environ, {'SAVETICKER_EMAIL':'sample', 'SAVETICKER_PASSWORD':'sample'}):
            self.store.migrate_env(self.env)
            self.assertNotIn('SAVETICKER_EMAIL', os.environ)
            self.assertNotIn('SAVETICKER_PASSWORD', os.environ)
        expected = '# hello\r\nOTHER="a=b"\r\nMULTI="first\nsecond"\r\nTAIL=yes\r\n'
        self.assertEqual(self.env.read_bytes().decode(), expected)
        self.assertEqual(self.env.stat().st_mode & 0o777, 0o600)
        restarted = CredentialStore(self.store.path, lambda: self.backend)
        restarted.migrate_env(self.env)
        self.assertEqual(len(self.items), 1)
        self.assertFalse(restarted.status()['migration_incomplete'])

    def test_write_denial_and_read_mismatch_preserve_original(self):
        for failure in ('write', 'read'):
            if failure == 'write': self.backend.set_password.side_effect = RuntimeError('SECRET')
            else:
                self.backend.set_password.side_effect = lambda *args: None
                self.backend.get_password.side_effect = lambda *args: 'wrong'
            with self.assertRaises(CredentialStoreError) as caught: self.store.migrate_env(self.env)
            self.assertNotIn('SECRET', str(caught.exception))
            self.assertEqual(self.env.read_bytes().decode(), self.original)
            self.assertTrue(self.store.status()['migration_incomplete'])

    def test_file_replace_failure_and_restart_retry_no_backup(self):
        real_replace = os.replace
        def fail_env(src, dest):
            if Path(dest) == self.env: raise PermissionError('SECRET')
            real_replace(src, dest)
        with patch('app.credential_store.os.replace', side_effect=fail_env):
            with self.assertRaises(CredentialStoreError): self.store.migrate_env(self.env)
        self.assertEqual(self.env.read_bytes().decode(), self.original)
        self.assertEqual(sorted(p.name for p in self.path.iterdir()), ['profile.json', 'sample.env'])
        restarted = CredentialStore(self.store.path, lambda: self.backend)
        restarted.migrate_env(self.env)
        self.assertNotIn('sample-secret', self.env.read_text())
        self.assertEqual(len(self.items), 1)

    def test_crash_after_env_replace_recovers_pending_marker(self):
        original_write = self.store._write_meta
        def fail_complete(meta):
            if meta.get('migration') == 'complete': raise OSError('simulated interruption')
            original_write(meta)
        with patch.object(self.store, '_write_meta', side_effect=fail_complete):
            with self.assertRaises(CredentialStoreError): self.store.migrate_env(self.env)
        self.assertNotIn('sample-secret', self.env.read_text())
        restarted = CredentialStore(self.store.path, lambda: self.backend)
        restarted.migrate_env(self.env)
        self.assertFalse(restarted.status()['migration_incomplete'])
        self.assertEqual(restarted.read()['password'], 'sample-secret')

    def test_no_unsupported_or_plaintext_fallback(self):
        with patch('app.credential_store.sys.platform', 'linux'):
            with self.assertRaises(CredentialStoreError): os_backend()

class ConnectionTests(unittest.TestCase):
    def setUp(self):
        self.app = FastAPI()
        self.app.include_router(insight.router)
        self.sid, _, self.context = new_session()
        self.client = TestClient(self.app)
        self.client.cookies.set('session', self.sid)
        self.addCleanup(self.client.close)
        self.addCleanup(clear_all_sessions)
        self.store = Mock(lock=threading.RLock())
        self.store.status.return_value = {'saved': False, 'migration_incomplete': False}
        self.store_patch = patch('app.routes.insight_connection.credential_store', self.store)
        self.store_patch.start()
        self.addCleanup(self.store_patch.stop)

    def test_connection_default_session_only_and_status_never_contains_credentials(self):
        with patch.object(SaveTickerService, 'authenticate', return_value=True):
            response = self.client.post('/api/saveticker/connection', json={'email':'sample@example.invalid','password':'sample-secret'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {'connected': True, 'saved': False})
        self.store.write.assert_not_called()
        self.store.delete.assert_called_once()
        status = self.client.get('/api/saveticker/connection')
        self.assertEqual(status.status_code, 200)
        self.assertNotIn('sample', status.text)
        destroy_session(self.sid)
        self.assertIsNone(self.context.service._credentials)

    def test_remember_write_disconnect_and_cache_delete(self):
        with patch.object(SaveTickerService, 'authenticate', return_value=True):
            response = self.client.post('/api/saveticker/connection', json={'email':'sample@example.invalid','password':'sample-secret','remember': True})
        self.assertEqual(response.status_code, 200)
        self.store.write.assert_called_once_with('sample@example.invalid','sample-secret')
        self.context.cache['example'] = {'ts': time.time(), 'data': {}}
        self.assertEqual(self.client.delete('/api/saveticker/cache').status_code, 200)
        self.assertFalse(self.context.cache)
        self.assertTrue(self.context.service.enabled)
        self.assertEqual(self.client.delete('/api/saveticker/connection').status_code, 200)
        self.assertFalse(self.context.service.enabled)
        self.store.delete.assert_called_once()

    def test_malformed_input_never_echoes_password(self):
        response = self.client.post('/api/saveticker/connection', json={'email':'x', 'password':'sample-secret', 'remember': {'private': 'sample-secret'}})
        self.assertEqual(response.status_code, 422)
        self.assertNotIn('sample-secret', response.text)
        response = self.client.post('/api/saveticker/connection', content='{"password":"sample-secret" bad', headers={'Content-Type':'application/json'})
        self.assertEqual(response.status_code, 422)
        self.assertNotIn('sample-secret', response.text)

    def test_denied_storage_preserves_only_current_connection_and_reports_failure(self):
        self.store.write.side_effect = CredentialStoreError()
        with patch.object(SaveTickerService, 'authenticate', return_value=True):
            response = self.client.post('/api/saveticker/connection', json={'email':'sample@example.invalid','password':'sample-secret','remember':True})
        self.assertEqual(response.status_code, 503)
        self.assertTrue(self.context.service.enabled)
        self.assertNotIn('sample', response.text)

    def test_restore_loads_only_into_new_lease_and_late_read_is_discarded(self):
        async def scenario():
            started, release = threading.Event(), threading.Event()
            def read():
                started.set(); release.wait(2)
                return {'email':'sample@example.invalid','password':'sample-secret'}
            with patch('app.insight_context.credential_store.read', side_effect=read):
                task = asyncio.create_task(self.context.restore())
                while not started.is_set(): await asyncio.sleep(.001)
                destroy_session(self.sid)
                release.set()
                with self.assertRaises(HTTPException): await task
                self.assertIsNone(self.context.service._credentials)
        asyncio.run(scenario())
