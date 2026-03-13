from __future__ import annotations

import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app import runtime_paths
from app.routes.auth_pages import router as auth_pages_router
from app.routes.insight import router as insight_router
from app.routes.market import router as market_router
from app.routes.mobile import router as mobile_router
from app.routes.portfolio import router as portfolio_router
from app.services.kis_us_quote_service import KISUSQuoteService


BASE_DIR = runtime_paths.get_app_base_dir()


@asynccontextmanager
async def lifespan(app: FastAPI):
    quote_service = KISUSQuoteService()
    quote_service.start()
    app.state.us_quote_service = quote_service
    try:
        yield
    finally:
        quote_service.stop()


app = FastAPI(title="Korea Investment Dashboard", lifespan=lifespan)

app.mount(
    "/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static"
)

if os.path.isdir(os.path.join(BASE_DIR, "img")):
    app.mount("/img", StaticFiles(directory=os.path.join(BASE_DIR, "img")), name="img")

app.include_router(auth_pages_router)
app.include_router(mobile_router)
app.include_router(portfolio_router)
app.include_router(market_router)
app.include_router(insight_router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
