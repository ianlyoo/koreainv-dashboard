import argparse
import errno
import logging
import os
import shutil
import subprocess
import tempfile
import time
import zipfile
from pathlib import Path

APP_NAME = "KISDashboard"


def _logs_dir() -> str:
    base = os.path.join(os.path.expanduser("~"), "Library", "Application Support", APP_NAME, "logs")
    os.makedirs(base, exist_ok=True)
    return base


def _configure_logging() -> logging.Logger:
    log_path = os.path.join(_logs_dir(), "updater-mac.log")
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[logging.FileHandler(log_path, encoding="utf-8")],
    )
    return logging.getLogger("updater-mac")


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except OSError as e:
        if e.errno == errno.ESRCH:
            return False
        return True
    return True


def _wait_for_pid_exit(pid: int, timeout: int = 90) -> bool:
    start = time.time()
    while time.time() - start < timeout:
        if not _pid_alive(pid):
            return True
        time.sleep(0.5)
    return False


def _show_info_message(message: str, title: str = "KISDashboard 업데이트") -> None:
    safe_msg = message.replace('"', '\\"')
    safe_title = title.replace('"', '\\"')
    script = f'display dialog "{safe_msg}" with title "{safe_title}" buttons {{"확인"}} default button "확인"'
    try:
        subprocess.run(["osascript", "-e", script], check=False)
    except Exception:
        pass


def _find_extracted_app(extract_dir: str) -> str | None:
    root = Path(extract_dir)
    preferred = root / "KISDashboard.app"
    if preferred.exists():
        return str(preferred)

    for candidate in root.rglob("*.app"):
        if candidate.name.lower() == "kisdashboard.app":
            return str(candidate)

    for candidate in root.rglob("*.app"):
        return str(candidate)

    return None


def _replace_app(new_app_path: str, target_app_path: str, logger: logging.Logger) -> None:
    target = Path(target_app_path)
    backup = target.with_suffix(".app.old")

    if backup.exists():
        shutil.rmtree(backup, ignore_errors=True)

    if target.exists():
        shutil.move(str(target), str(backup))

    try:
        shutil.move(new_app_path, target_app_path)
        shutil.rmtree(backup, ignore_errors=True)
    except Exception:
        if target.exists():
            shutil.rmtree(target, ignore_errors=True)
        if backup.exists():
            shutil.move(str(backup), str(target))
        logger.exception("Failed to replace app and rolled back")
        raise


def _restart_app(app_path: str) -> None:
    subprocess.Popen(["open", app_path], start_new_session=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="KISDashboard macOS self-updater")
    parser.add_argument("--zip", dest="zip_path", required=True)
    parser.add_argument("--app-path", required=True)
    parser.add_argument("--wait-pid", type=int, required=True)
    args = parser.parse_args()

    logger = _configure_logging()
    logger.info("Updater start zip=%s app=%s pid=%s", args.zip_path, args.app_path, args.wait_pid)

    if not os.path.exists(args.zip_path):
        logger.error("Update zip not found: %s", args.zip_path)
        _show_info_message("업데이트 파일을 찾지 못했습니다.")
        return 1

    if not _wait_for_pid_exit(args.wait_pid):
        logger.error("Timed out waiting for app process to exit")
        _show_info_message("앱 종료 대기 시간이 초과되었습니다.")
        return 1

    try:
        with tempfile.TemporaryDirectory(prefix="kisdash_mac_update_") as tmp:
            extract_dir = os.path.join(tmp, "extract")
            os.makedirs(extract_dir, exist_ok=True)

            with zipfile.ZipFile(args.zip_path, "r") as zf:
                zf.extractall(extract_dir)

            extracted_app = _find_extracted_app(extract_dir)
            if not extracted_app:
                logger.error("No .app bundle found in update zip")
                _show_info_message("업데이트 파일에 앱 번들이 없습니다.")
                return 1

            _replace_app(extracted_app, args.app_path, logger)

        _show_info_message("업데이트가 완료되었습니다. 앱을 다시 시작합니다.")
        _restart_app(args.app_path)
        logger.info("Update completed and app restarted")
        return 0
    except PermissionError:
        logger.exception("Permission denied during update")
        _show_info_message("업데이트 권한이 없습니다. 앱 위치 권한을 확인해주세요.")
        return 1
    except Exception:
        logger.exception("Updater failed")
        _show_info_message("업데이트 중 오류가 발생했습니다.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
