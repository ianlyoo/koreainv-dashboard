import atexit
import logging
import os
import signal
import socket
import threading
import time
import urllib.request
import webbrowser

import uvicorn

import runtime_paths

HOST = "127.0.0.1"
PORT = 8000
HEALTH_ENDPOINT = f"http://{HOST}:{PORT}/api/status"
DASHBOARD_URL = f"http://{HOST}:{PORT}"

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


def _run_server() -> None:
    global _server
    config = uvicorn.Config(
        "main:app",
        host=HOST,
        port=PORT,
        log_level="info",
        access_log=False,
        reload=False,
    )
    _server = uvicorn.Server(config)
    _server.run()


def _shutdown_server(*_args) -> None:
    if _server is not None:
        _server.should_exit = True


def main() -> int:
    _configure_logging()
    logger = logging.getLogger("launcher")
    logger.info("Launching KIS Dashboard")

    if _is_port_in_use(HOST, PORT):
        logger.error("Port %d is already in use.", PORT)
        return 1

    server_thread = threading.Thread(target=_run_server, daemon=True)
    server_thread.start()

    if not _wait_until_ready():
        logger.error("Server did not become ready in time.")
        return 1

    logger.info("Server ready. Opening browser: %s", DASHBOARD_URL)
    webbrowser.open(DASHBOARD_URL)

    atexit.register(_shutdown_server)
    signal.signal(signal.SIGINT, _shutdown_server)
    signal.signal(signal.SIGTERM, _shutdown_server)

    try:
        while server_thread.is_alive():
            time.sleep(0.5)
    except KeyboardInterrupt:
        _shutdown_server()

    logger.info("Launcher stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
