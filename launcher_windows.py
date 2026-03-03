import atexit
import ctypes
import logging
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
import tempfile
import shutil
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


def _configure_logging() -> None:
    log_file = os.path.join(runtime_paths.get_logs_dir(), "launcher.log")
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[
            logging.FileHandler(log_file, encoding="utf-8"),
        ],
    )


def _is_port_in_use(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        return sock.connect_ex((host, port)) == 0


def _wait_until_ready(timeout_seconds: int = 30) -> bool:
    start = time.time()
    while time.time() - start < timeout_seconds:
        try:
            with urllib.request.urlopen(HEALTH_ENDPOINT, timeout=2) as response:
                if response.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(0.4)
    return False


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


def _find_tray_icon_path() -> str | None:
    img_dir = Path(runtime_paths.get_app_base_dir()) / "img"
    if not img_dir.is_dir():
        return None
    icons = sorted(img_dir.glob("*.ico"))
    if not icons:
        return None
    return str(icons[0])


def _run_tray(server_thread: threading.Thread, logger: logging.Logger) -> None:
    try:
        import pystray
        from PIL import Image
    except Exception as e:
        logger.warning("Tray modules unavailable (%s). Running without tray UI.", e)
        while server_thread.is_alive():
            time.sleep(0.5)
        return

    icon_path = _find_tray_icon_path()
    if not icon_path:
        logger.warning("Tray icon not found in app/img; running without tray UI.")
        while server_thread.is_alive():
            time.sleep(0.5)
        return

    try:
        tray_image = Image.open(icon_path)
    except Exception as e:
        logger.error("Failed to load tray icon: %s", e)
        while server_thread.is_alive():
            time.sleep(0.5)
        return

    def on_open(icon, item):
        webbrowser.open(DASHBOARD_URL)

    def on_exit(icon, item):
        logger.info("Tray exit requested")
        _shutdown_server()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem("대시보드 열기", on_open, default=True),
        pystray.MenuItem("종료", on_exit),
    )
    icon = pystray.Icon("KISDashboard", tray_image, "KISDashboard", menu)

    def watch_server():
        while server_thread.is_alive():
            time.sleep(0.5)
        try:
            icon.stop()
        except Exception:
            pass

    threading.Thread(target=watch_server, daemon=True).start()
    icon.run()


def _normalize_version(v: str) -> tuple:
    cleaned = (v or "").strip().lower().lstrip("v")
    parts = re.findall(r"\d+", cleaned)
    if not parts:
        return (0, 0, 0)
    nums = tuple(int(x) for x in parts[:3])
    return nums + (0,) * (3 - len(nums))


def _confirm_update(message: str) -> bool:
    try:
        mb_yesno = 0x00000004
        mb_icon_question = 0x00000020
        res = ctypes.windll.user32.MessageBoxW(0, message, "KISDashboard 업데이트", mb_yesno | mb_icon_question)
        return res == 6  # IDYES
    except Exception:
        return False


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


def _find_zip_asset(release: dict) -> dict | None:
    assets = release.get("assets", []) or []
    preferred = None
    fallback = None
    for a in assets:
        name = str(a.get("name", "")).lower()
        if name.endswith(".zip"):
            fallback = fallback or a
            if "win64" in name:
                preferred = a
                break
    return preferred or fallback


def _download_update_zip(asset: dict, logger: logging.Logger) -> str | None:
    url = asset.get("browser_download_url")
    name = asset.get("name") or "update.zip"
    if not url:
        return None
    updates_dir = os.path.join(runtime_paths.get_user_data_dir(), "updates")
    os.makedirs(updates_dir, exist_ok=True)
    zip_path = os.path.join(updates_dir, name)
    try:
        with requests.get(url, stream=True, timeout=30) as res:
            res.raise_for_status()
            with open(zip_path, "wb") as f:
                for chunk in res.iter_content(chunk_size=1024 * 128):
                    if chunk:
                        f.write(chunk)
        return zip_path
    except Exception as e:
        logger.error("Failed to download update: %s", e)
        return None


def _launch_updater(zip_path: str, logger: logging.Logger) -> bool:
    install_dir = os.path.dirname(sys.executable) if getattr(sys, "frozen", False) else os.path.dirname(os.path.abspath(__file__))

    if getattr(sys, "frozen", False):
        updater_src = os.path.join(install_dir, "KISDashboardUpdater.exe")
        if not os.path.exists(updater_src):
            logger.error("Updater executable not found: %s", updater_src)
            return False
        runtime_dir = tempfile.mkdtemp(prefix="kisdash_updater_")
        updater_bin = os.path.join(runtime_dir, "KISDashboardUpdater.exe")
        shutil.copy2(updater_src, updater_bin)
        cmd = [
            updater_bin,
            "--zip", zip_path,
            "--install-dir", install_dir,
            "--wait-pid", str(os.getpid()),
            "--restart-exe", os.path.basename(sys.executable),
        ]
    else:
        updater_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "updater_windows.py")
        cmd = [
            sys.executable,
            updater_script,
            "--zip", zip_path,
            "--install-dir", install_dir,
            "--wait-pid", str(os.getpid()),
            "--restart-exe", "KISDashboard.exe",
        ]

    try:
        creationflags = 0
        if os.name == "nt":
            creationflags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
        subprocess.Popen(cmd, cwd=install_dir, creationflags=creationflags)
        return True
    except Exception as e:
        logger.error("Failed to start updater: %s", e)
        return False


def _maybe_run_auto_update(logger: logging.Logger) -> bool:
    if not CHECK_UPDATE_ON_START:
        return False

    release = _latest_release_info(logger)
    if not release:
        return False

    latest_tag = release.get("tag_name", "")
    if _normalize_version(latest_tag) <= _normalize_version(APP_VERSION):
        logger.debug("No update required. latest=%s current=%s", latest_tag, APP_VERSION)
        return False

    msg = f"새 버전({latest_tag})이 있습니다.\n지금 업데이트할까요?"
    if not _confirm_update(msg):
        logger.info("Update declined by user. Continuing current version.")
        return False

    asset = _find_zip_asset(release)
    if not asset:
        logger.warning("No zip asset found in latest release.")
        return False

    zip_path = _download_update_zip(asset, logger)
    if not zip_path:
        return False

    if _launch_updater(zip_path, logger):
        logger.info("Updater launched. Exiting launcher for update.")
        return True

    return False


def main() -> int:
    _configure_logging()
    logger = logging.getLogger("launcher")
    logger.info("Launching KIS Dashboard (version=%s)", APP_VERSION)

    if _maybe_run_auto_update(logger):
        return 0

    if _is_port_in_use(HOST, PORT):
        if _wait_until_ready(timeout_seconds=3):
            logger.info("Existing server detected on port %d. Opening browser.", PORT)
            webbrowser.open(DASHBOARD_URL)
            return 0
        logger.error("Port %d is already in use.", PORT)
        return 1

    server_thread = threading.Thread(target=_run_server, args=(logger,), daemon=True)
    server_thread.start()

    if not _wait_until_ready_or_dead(server_thread):
        if not server_thread.is_alive():
            logger.error("Server thread exited unexpectedly. Check traceback above.")
        logger.error("Server did not become ready in time.")
        return 1

    logger.info("Server ready. Opening browser: %s", DASHBOARD_URL)
    webbrowser.open(DASHBOARD_URL)

    atexit.register(_shutdown_server)
    signal.signal(signal.SIGINT, _shutdown_server)
    signal.signal(signal.SIGTERM, _shutdown_server)

    try:
        _run_tray(server_thread, logger)
    except KeyboardInterrupt:
        _shutdown_server()

    logger.info("Launcher stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

