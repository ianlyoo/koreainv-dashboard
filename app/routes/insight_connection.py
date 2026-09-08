"""Connection management is available only inside an unlocked dashboard session."""
from fastapi import APIRouter, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, SecretStr
from app.session_store import require_session
from app.credential_store import credential_store, CredentialStoreError
from app.services.saveticker_service import SaveTickerService

class SecretSafeRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()
        async def safe_handler(request):
            require_session(request)
            try:
                return await handler(request)
            except RequestValidationError:
                # FastAPI's default error includes the submitted input, including passwords.
                return JSONResponse({'detail': '연결 입력 형식을 확인하세요.'}, status_code=422)
        return safe_handler

router = APIRouter(route_class=SecretSafeRoute)

class ConnectionRequest(BaseModel):
    email: str = Field(min_length=3, max_length=320)
    password: SecretStr = Field(min_length=1, max_length=4096)
    remember: bool = False


def context_for(request):
    context = require_session(request).insight
    context.check()
    return context

@router.get('/api/saveticker/connection')
async def connection_status(request: Request):
    context = context_for(request)
    generation = context.generation
    stored = await context.run(credential_store.status, generation=generation)
    return {**stored, 'connected': context.service.enabled, 'warning': context.warning}

@router.post('/api/saveticker/connection')
async def connect(request: Request, payload: ConnectionRequest):
    context = context_for(request)
    async with context.connection_lock:
        context.check()
        service = SaveTickerService(payload.email.strip(), payload.password.get_secret_value())
        context.replace_service(service)
        generation = context.generation
        try:
            if not await context.run(service.authenticate, generation=generation):
                context.replace_service(SaveTickerService())
                raise HTTPException(400, 'SaveTicker 연결에 실패했습니다. 입력 정보를 확인하세요.')
            if payload.remember:
                # Store lock orders reset/delete against writes. Check the lease inside it.
                def save():
                    with credential_store.lock:
                        context.check(generation)
                        credential_store.write(payload.email.strip(), payload.password.get_secret_value())
                await context.run(save, generation=generation)
            else:
                # Session-only must not silently restore an older saved account at next unlock.
                def forget():
                    with credential_store.lock:
                        context.check(generation)
                        credential_store.delete()
                await context.run(forget, generation=generation)
            context.check(generation)
            context.warning = None
            return {'connected': True, 'saved': bool(payload.remember)}
        except CredentialStoreError:
            context.check(generation)
            context.warning = '현재 세션은 연결됐지만 저장 설정 변경에 실패했습니다. 저장 정보 삭제 또는 저장을 다시 시도하세요.'
            raise HTTPException(503, context.warning) from None

@router.delete('/api/saveticker/connection')
async def disconnect(request: Request):
    context = context_for(request)
    async with context.connection_lock:
        context.replace_service(SaveTickerService())
        generation = context.generation
        try:
            await context.run(credential_store.delete, generation=generation)
        except CredentialStoreError:
            context.warning = '현재 연결은 해제됐지만 저장 정보 삭제에 실패했습니다. 다시 시도하세요.'
            raise HTTPException(503, context.warning) from None
        context.warning = None
        return {'connected': False, 'saved': False}

@router.delete('/api/saveticker/cache')
async def clear_cache(request: Request):
    context = context_for(request)
    context.clear_cache()
    return {'status': 'success'}
