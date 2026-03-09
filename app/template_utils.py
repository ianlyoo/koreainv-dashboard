from __future__ import annotations

import os

from fastapi.responses import HTMLResponse

from app import runtime_paths


BASE_DIR = runtime_paths.get_app_base_dir()
NO_STORE_HEADERS = {
    "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
    "Pragma": "no-cache",
    "Expires": "0",
}


def render_template_html(template_name: str) -> HTMLResponse:
    template_path = os.path.join(BASE_DIR, "templates", template_name)
    with open(template_path, "r", encoding="utf-8") as file:
        return HTMLResponse(content=file.read(), headers=NO_STORE_HEADERS)
