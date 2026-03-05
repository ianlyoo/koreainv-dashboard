import argparse
import ctypes
import logging
import os
import shutil
import subprocess
import tempfile
import time
import zipfile

from app import runtime_paths


def _configure_logging() -> logging.Logger:
    log_path = os.path.join(runtime_paths.get_logs_dir(), "updater.log")
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[logging.FileHandler(log_path, encoding="utf-8")],
    )
    return logging.getLogger("updater")


def _pid_alive_windows(pid: int) -> bool:
    cmd = ["tasklist", "/FI", f"PID eq {pid}"]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return str(pid) in result.stdout


def _wait_for_pid_exit(pid: int, timeout: int = 90) -> bool:
    start = time.time()
    while time.time() - start < timeout:
        if not _pid_alive_windows(pid):
            return True
        time.sleep(0.5)
    return False


def _copy_tree(src_dir: str, dst_dir: str) -> None:
    for entry in os.listdir(src_dir):
        src_path = os.path.join(src_dir, entry)
        dst_path = os.path.join(dst_dir, entry)
        if os.path.isdir(src_path):
            os.makedirs(dst_path, exist_ok=True)
            _copy_tree(src_path, dst_path)
        else:
            os.makedirs(os.path.dirname(dst_path), exist_ok=True)
            shutil.copy2(src_path, dst_path)


def _restart_app(install_dir: str, restart_exe: str) -> None:
    target = os.path.join(install_dir, restart_exe)
    if not os.path.exists(target):
        return
    creationflags = 0
    if os.name == "nt":
        creationflags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    subprocess.Popen([target], cwd=install_dir, creationflags=creationflags)


def _show_info_message(text: str, title: str = "KISDashboard 업데이트") -> None:
    try:
        mb_ok = 0x00000000
        mb_icon_info = 0x00000040
        mb_topmost = 0x00040000
        ctypes.windll.user32.MessageBoxW(0, text, title, mb_ok | mb_icon_info | mb_topmost)
    except Exception:
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description="KISDashboard self-updater")
    parser.add_argument("--zip", dest="zip_path", required=True)
    parser.add_argument("--install-dir", required=True)
    parser.add_argument("--wait-pid", type=int, required=True)
    parser.add_argument("--restart-exe", default="KISDashboard.exe")
    args = parser.parse_args()

    logger = _configure_logging()
    logger.info("Updater started")
    logger.info("zip=%s install_dir=%s pid=%s", args.zip_path, args.install_dir, args.wait_pid)

    if not os.path.exists(args.zip_path):
        logger.error("Update zip not found: %s", args.zip_path)
        return 1

    if not _wait_for_pid_exit(args.wait_pid):
        logger.error("Timed out waiting for app process to exit.")
        return 1

    with tempfile.TemporaryDirectory(prefix="kisdash_update_") as tmp:
        extract_dir = os.path.join(tmp, "extract")
        os.makedirs(extract_dir, exist_ok=True)
        with zipfile.ZipFile(args.zip_path, "r") as zf:
            zf.extractall(extract_dir)
        _copy_tree(extract_dir, args.install_dir)

    _show_info_message("업데이트 완료했습니다.\n앱을 다시 시작합니다.")
    _restart_app(args.install_dir, args.restart_exe)
    logger.info("Update completed and app restarted")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
