import atexit
import logging
import os
import platform
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

import requests
import uvicorn

from app import runtime_paths
from app.version import APP_VERSION

HOST = "127.0.0.1"
PORT = 8000
HEALTH_ENDPOINT = f"http://{HOST}:{PORT}/api/status"
DASHBOARD_URL = f"http://{HOST}:{PORT}"
RELEASE_REPO = os.getenv("UPDATE_REPO", "ianlyoo/koreainv-dashboard")
CHECK_UPDATE_ON_START = os.getenv("CHECK_UPDATE_ON_START", "true").lower() == "true"

_server = None


def _configure_logging() -> logging.Logger:
    log_file = os.path.join(runtime_paths.get_logs_dir(), "launcher-mac.log")
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[logging.FileHandler(log_file, encoding="utf-8")],
    )
    return logging.getLogger("launcher-mac")


def _is_port_in_use(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        return sock.connect_ex((host, port)) == 0


def _wait_until_ready_or_dead(server_thread: threading.Thread, timeout_seconds: int = 30) -> bool:
    start = time.time()
    while time.time() - start < timeout_seconds:
        if not server_thread.is_alive():
            return False
        try:
            with urllib.request.urlopen(HEALTH_ENDPOINT, timeout=2) as response:
                if response.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(0.4)
    return False


def _run_server(logger: logging.Logger) -> None:
    global _server
    try:
        from app.main import app as fastapi_app

        config = uvicorn.Config(
            fastapi_app,
            host=HOST,
            port=PORT,
            log_level="info",
            access_log=False,
            reload=False,
            log_config=None,
        )
        _server = uvicorn.Server(config)
        _server.run()
    except Exception:
        logger.exception("Server thread crashed before startup")


def _shutdown_server(*_args) -> None:
    if _server is not None:
        _server.should_exit = True


def _normalize_version(v: str) -> tuple[int, int, int]:
    cleaned = (v or "").strip().lower().lstrip("v")
    parts = re.findall(r"\d+", cleaned)
    if not parts:
        return (0, 0, 0)
    nums = tuple(int(x) for x in parts[:3])
    return nums + (0,) * (3 - len(nums))


def _osascript(script: str) -> tuple[bool, str]:
    try:
        res = subprocess.run(["osascript", "-e", script], check=False, capture_output=True, text=True)
        return (res.returncode == 0, (res.stdout or "").strip())
    except Exception:
        return (False, "")


def _show_info_message(message: str, title: str = "KISDashboard") -> None:
    safe_msg = message.replace('"', '\\"')
    safe_title = title.replace('"', '\\"')
    script = f'display dialog "{safe_msg}" with title "{safe_title}" buttons {{"확인"}} default button "확인"'
    _osascript(script)


def _confirm_update(message: str) -> bool:
    safe_msg = message.replace('"', '\\"')
    script = (
        'display dialog "'
        + safe_msg
        + '" with title "KISDashboard 업데이트" '
        + 'buttons {"나중에", "업데이트"} default button "업데이트"'
    )
    ok, out = _osascript(script)
    return ok and "업데이트" in out


def _latest_release_info(logger: logging.Logger) -> dict | None:
    url = f"https://api.github.com/repos/{RELEASE_REPO}/releases/latest"
    try:
        res = requests.get(url, timeout=8)
        if res.status_code != 200:
            logger.warning("Release API status=%s", res.status_code)
            return None
        return res.json()
    except Exception as e:
        logger.warning("Update check failed: %s", e)
        return None


def _mac_arch_tokens() -> set[str]:
    machine = platform.machine().lower()
    if machine in {"arm64", "aarch64"}:
        return {"arm64", "aarch64", "apple-silicon"}
    if machine in {"x86_64", "amd64"}:
        return {"x86_64", "amd64", "intel"}
    return {machine}


def _find_mac_zip_asset(release: dict) -> dict | None:
    assets = release.get("assets", []) or []
    arch_tokens = _mac_arch_tokens()
    best: tuple[int, dict] | None = None

    for asset in assets:
        name = str(asset.get("name", "")).lower()
        if not name.endswith(".zip"):
            continue
        if "mac" not in name and "darwin" not in name and "osx" not in name:
            continue

        score = 0
        if "kisdashboard" in name:
            score += 1
        if any(tok in name for tok in arch_tokens):
            score += 3
        if "universal" in name:
            score += 2

        if best is None or score > best[0]:
            best = (score, asset)

    return best[1] if best else None


def _download_update_zip(asset: dict, logger: logging.Logger) -> str | None:
    url = asset.get("browser_download_url")
    name = asset.get("name") or "kisdashboard-mac-update.zip"
    if not url:
        return None

    updates_dir = os.path.join(runtime_paths.get_user_data_dir(), "updates")
    os.makedirs(updates_dir, exist_ok=True)
    zip_path = os.path.join(updates_dir, name)

    try:
        with requests.get(url, stream=True, timeout=40) as res:
            res.raise_for_status()
            with open(zip_path, "wb") as f:
                for chunk in res.iter_content(chunk_size=1024 * 128):
                    if chunk:
                        f.write(chunk)
        return zip_path
    except Exception as e:
        logger.error("Failed to download update: %s", e)
        return None


def _get_app_bundle_path() -> str | None:
    if not getattr(sys, "frozen", False):
        return None

    exe = Path(sys.executable).resolve()
    # .../KISDashboard.app/Contents/MacOS/KISDashboard
    if exe.parent.name != "MacOS":
        return None

    contents = exe.parent.parent
    if contents.name != "Contents":
        return None

    app_path = contents.parent
    if app_path.suffix != ".app":
        return None

    return str(app_path)


def _launch_updater(zip_path: str, logger: logging.Logger) -> bool:
    app_path = _get_app_bundle_path()
    if not app_path:
        logger.error("Could not resolve app bundle path for updater")
        return False

    if getattr(sys, "frozen", False):
        exe_dir = os.path.dirname(sys.executable)
        candidates = [
            os.path.join(exe_dir, "KISDashboardUpdater"),
            os.path.join(os.path.dirname(exe_dir), "Frameworks", "KISDashboardUpdater"),
            os.path.join(os.path.dirname(exe_dir), "Resources", "KISDashboardUpdater"),
        ]
        updater_bin = next((p for p in candidates if os.path.exists(p)), "")
        if not updater_bin:
            logger.error("Updater binary not found in app bundle candidates: %s", candidates)
            return False
        cmd = [
            updater_bin,
            "--zip",
            zip_path,
            "--app-path",
            app_path,
            "--wait-pid",
            str(os.getpid()),
        ]
    else:
        updater_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "updater_mac.py")
        cmd = [
            sys.executable,
            updater_script,
            "--zip",
            zip_path,
            "--app-path",
            app_path,
            "--wait-pid",
            str(os.getpid()),
        ]

    try:
        subprocess.Popen(cmd, cwd=os.path.dirname(app_path), start_new_session=True)
        return True
    except Exception as e:
        logger.error("Failed to start updater: %s", e)
        return False


def _maybe_run_auto_update(logger: logging.Logger, manual: bool = False) -> bool:
    if not manual and not CHECK_UPDATE_ON_START:
        return False

    release = _latest_release_info(logger)
    if not release:
        if manual:
            _show_info_message("최신 버전 확인에 실패했습니다.")
        return False

    latest_tag = release.get("tag_name", "")
    if _normalize_version(latest_tag) <= _normalize_version(APP_VERSION):
        if manual:
            _show_info_message(f"이미 최신 버전입니다. (v{APP_VERSION})")
        return False

    msg = f"새 버전({latest_tag})이 있습니다.\n지금 업데이트할까요?"
    if not _confirm_update(msg):
        logger.info("Update declined by user.")
        return False

    asset = _find_mac_zip_asset(release)
    if not asset:
        logger.warning("No mac zip asset found in latest release")
        _show_info_message("macOS 업데이트 파일을 찾지 못했습니다.")
        return False

    zip_path = _download_update_zip(asset, logger)
    if not zip_path:
        _show_info_message("업데이트 파일 다운로드에 실패했습니다.")
        return False

    if _launch_updater(zip_path, logger):
        logger.info("Updater launched. Exiting for update.")
        return True

    _show_info_message("업데이트 실행에 실패했습니다.")
    return False


def main() -> int:
    logger = _configure_logging()
    logger.info("Launcher start (version=%s)", APP_VERSION)

    if _maybe_run_auto_update(logger, manual=False):
        return 0

    if _is_port_in_use(HOST, PORT):
        logger.info("Port %s already in use, opening dashboard only", PORT)
        webbrowser.open(DASHBOARD_URL)
        return 0

    server_thread = threading.Thread(target=_run_server, args=(logger,), daemon=True)
    server_thread.start()

    if not _wait_until_ready_or_dead(server_thread):
        logger.error("Server failed to start or crashed during startup")
        return 1

    webbrowser.open(DASHBOARD_URL)

    atexit.register(_shutdown_server)
    signal.signal(signal.SIGTERM, _shutdown_server)
    signal.signal(signal.SIGINT, _shutdown_server)

    try:
        while server_thread.is_alive():
            time.sleep(0.5)
    except KeyboardInterrupt:
        _shutdown_server()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
