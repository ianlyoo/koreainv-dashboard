"""SaveTicker secrets in explicit OS stores only. Metadata contains no secrets.

Migration is explicit (never triggered by module import). Its durable pending marker
and deterministic OS item make retry safe after any interrupted step.
"""
from __future__ import annotations
import io
import json
import os
import sys
import tempfile
import threading
import uuid
from pathlib import Path
from dotenv.parser import parse_stream
from app import runtime_paths

SERVICE = 'KoreaInvDashboard.SaveTicker.v1'
ENV_KEYS = {'SAVETICKER_EMAIL', 'SAVETICKER_PASSWORD'}

class CredentialStoreError(Exception):
    def __init__(self):
        super().__init__('OS 보안 저장소를 사용할 수 없습니다. 연결 정보 이전/저장이 미완료 상태입니다.')


def atomic_write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='') as stream:
            if os.name != 'nt':
                os.fchmod(stream.fileno(), 0o600)
            # Windows uses the containing user-profile directory's ACL. The
            # same-directory temporary file preserves the atomic replace boundary.
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
        if os.name != 'nt':
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def os_backend():
    # Do not call keyring.get_keyring(): third-party/plaintext fallback is forbidden.
    try:
        if sys.platform == 'darwin':
            from keyring.backends.macOS import Keyring
        elif sys.platform == 'win32':
            from keyring.backends.Windows import WinVaultKeyring as Keyring
        else:
            raise CredentialStoreError()
        backend = Keyring()
        if sys.platform == 'win32':
            backend.persist = 'local machine'
        if backend.priority <= 0:
            raise CredentialStoreError()
        return backend
    except Exception:
        raise CredentialStoreError() from None


class CredentialStore:
    def __init__(self, metadata_path=None, backend_factory=None):
        self.path = Path(metadata_path or Path(runtime_paths.get_user_data_dir()) / 'insight-profile.json')
        self.backend_factory = backend_factory or os_backend
        self.lock = threading.RLock()

    def _meta(self, create=False):
        if self.path.exists():
            meta = json.loads(self.path.read_text(encoding='utf-8'))
            uuid.UUID(meta['profile_id'])
            return meta
        if not create:
            return None
        meta = {'profile_id': str(uuid.uuid4()), 'saved': False, 'migration': None}
        self._write_meta(meta)
        return meta

    def _write_meta(self, meta):
        atomic_write(self.path, json.dumps(meta))

    def status(self):
        try:
            with self.lock:
                meta = self._meta()
                return {'saved': bool(meta and meta.get('saved')), 'migration_incomplete': bool(meta and meta.get('migration') == 'pending')}
        except Exception:
            return {'saved': False, 'migration_incomplete': True}

    def read(self):
        try:
            with self.lock:
                meta = self._meta()
                if not meta or not meta.get('saved'):
                    return None
                raw = self.backend_factory().get_password(SERVICE, meta['profile_id'])
                if raw is None:
                    raise CredentialStoreError()
                value = json.loads(raw)
                if set(value) != {'email', 'password'} or not all(isinstance(v, str) and v and len(v) <= 4096 for v in value.values()):
                    raise CredentialStoreError()
                return value
        except Exception:
            raise CredentialStoreError() from None

    def write(self, email, password):
        try:
            with self.lock:
                if not all(isinstance(v, str) and v and len(v) <= 4096 for v in (email, password)):
                    raise CredentialStoreError()
                meta = self._meta(create=True)
                raw = json.dumps({'email': email, 'password': password})
                backend = self.backend_factory()
                backend.set_password(SERVICE, meta['profile_id'], raw)
                if backend.get_password(SERVICE, meta['profile_id']) != raw:
                    raise CredentialStoreError()
                meta['saved'] = True
                self._write_meta(meta)
        except Exception:
            raise CredentialStoreError() from None

    def delete(self):
        try:
            with self.lock:
                meta = self._meta()
                if not meta:
                    return
                backend = self.backend_factory()
                if backend.get_password(SERVICE, meta['profile_id']) is not None:
                    backend.delete_password(SERVICE, meta['profile_id'])
                if backend.get_password(SERVICE, meta['profile_id']) is not None:
                    raise CredentialStoreError()
                meta['saved'] = False
                self._write_meta(meta)
        except Exception:
            raise CredentialStoreError() from None

    def migrate_env(self, env_path):
        """Call only with user authorization; never prints/backs up source secrets."""
        try:
            with self.lock:
                path = Path(env_path)
                # Preserve exact unrelated bytes/newlines, including multiline dotenv values.
                original = path.read_bytes().decode('utf-8')
                bindings = list(parse_stream(io.StringIO(original)))
                if any(b.error for b in bindings):
                    raise CredentialStoreError()
                values = {b.key: b.value for b in bindings if b.key in ENV_KEYS}
                if not values:
                    meta = self._meta()
                    if meta and meta.get('migration') == 'pending':
                        if self.read() is None:
                            raise CredentialStoreError()
                        meta['migration'] = 'complete'
                        self._write_meta(meta)
                    self._clear_process_copies()
                    return {'status': 'complete'}
                meta = self._meta(create=True)
                meta['migration'] = 'pending'
                self._write_meta(meta)
                if not all(values.get(key) for key in ENV_KEYS):
                    raise CredentialStoreError()
                self.write(values['SAVETICKER_EMAIL'], values['SAVETICKER_PASSWORD'])
                verified = self.read()
                if verified != {'email': values['SAVETICKER_EMAIL'], 'password': values['SAVETICKER_PASSWORD']}:
                    raise CredentialStoreError()
                cleaned = ''.join(b.original.string for b in bindings if b.key not in ENV_KEYS)
                # Do not overwrite a concurrently edited settings file.
                if path.read_bytes().decode('utf-8') != original:
                    raise CredentialStoreError()
                atomic_write(path, cleaned)
                self._clear_process_copies()
                meta = self._meta()
                meta['migration'] = 'complete'
                self._write_meta(meta)
                return {'status': 'complete'}
        except Exception:
            raise CredentialStoreError() from None

    @staticmethod
    def _clear_process_copies():
        for key in ENV_KEYS:
            os.environ.pop(key, None)
        config = sys.modules.get('app.config')
        if config:
            for key in ENV_KEYS:
                if hasattr(config, key):
                    delattr(config, key)

credential_store = CredentialStore()
