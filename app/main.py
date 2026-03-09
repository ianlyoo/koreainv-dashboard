from __future__ import annotations

import os

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app import runtime_paths
from app.routes.auth_pages import router as auth_pages_router
from app.routes.insight import router as insight_router
from app.routes.market import router as market_router
from app.routes.portfolio import router as portfolio_router


BASE_DIR = runtime_paths.get_app_base_dir()

app = FastAPI(title="Korea Investment Dashboard")

app.mount(
    "/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static"
)

if os.path.isdir(os.path.join(BASE_DIR, "img")):
    app.mount("/img", StaticFiles(directory=os.path.join(BASE_DIR, "img")), name="img")

app.include_router(auth_pages_router)
app.include_router(portfolio_router)
app.include_router(market_router)
app.include_router(insight_router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
