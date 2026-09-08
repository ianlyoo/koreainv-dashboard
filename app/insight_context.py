"""A dashboard session owns one revocable insight context, never a global login."""
from __future__ import annotations
import asyncio
import copy
import threading
import time
from fastapi import HTTPException
from app.services.saveticker_service import SaveTickerService, InsightRevoked
from app.credential_store import credential_store, CredentialStoreError


class InsightContext:
    def __init__(self):
        self.service = SaveTickerService()
        self.cache = {}
        self.generation = 0
        self.revoked = False
        self.warning = None
        self._tasks = set()
        self._connection_lock = None
        self._lock = threading.RLock()

    @property
    def connection_lock(self):
        if self._connection_lock is None:
            self._connection_lock = asyncio.Lock()
        return self._connection_lock

    def check(self, generation=None):
        if self.revoked or (generation is not None and generation != self.generation):
            raise HTTPException(401, 'Insight session revoked')

    def revoke(self):
        with self._lock:
            self.revoked = True
            self.generation += 1
            self.service.revoke()
            self.cache.clear()
            for task in tuple(self._tasks):
                if not task.done() and not task.get_loop().is_closed():
                    task.get_loop().call_soon_threadsafe(task.cancel)
            self._tasks.clear()

    def replace_service(self, service):
        with self._lock:
            self.check()
            self.service.revoke()
            self.service = service
            self.generation += 1
            self.cache.clear()

    def clear_cache(self):
        with self._lock:
            self.check()
            self.generation += 1
            self.cache.clear()
            self.service.clear_cache()

    async def run(self, function, *args, generation=None):
        with self._lock:
            self.check(generation)
            task = asyncio.create_task(asyncio.to_thread(function, *args))
            self._tasks.add(task)
        try:
            result = await task
            self.check(generation)
            return result
        except (asyncio.CancelledError, InsightRevoked):
            raise HTTPException(401, 'Insight session revoked') from None
        finally:
            self._tasks.discard(task)

    async def restore(self):
        generation = self.generation
        try:
            credentials = await self.run(credential_store.read, generation=generation)
            with self._lock:
                self.check(generation)
                if credentials:
                    self.replace_service(SaveTickerService(**credentials))
        except CredentialStoreError:
            self.warning = '저장된 연결을 열 수 없습니다. 설정에서 다시 연결하세요.'
        finally:
            credentials = None

    def cached(self, key):
        with self._lock:
            self.check()
            cached = self.cache.get(key)
            if cached:
                provider = cached['data'].get('data', {}).get('saveticker') or {}
                ttl = 60 if provider.get('status') == 'unavailable' or 'error' in provider.get('section_status', {}).values() else 300
                if time.time() - cached['ts'] < ttl:
                    return copy.deepcopy(cached['data'])

    def save(self, key, payload, generation):
        with self._lock:
            self.check(generation)
            self.cache[key] = {'ts': time.time(), 'data': copy.deepcopy(payload)}
            while len(self.cache) > 128:
                self.cache.pop(next(iter(self.cache)))
