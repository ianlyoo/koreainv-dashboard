import os
import sys

APP_NAME = "KISDashboard"


def get_app_base_dir() -> str:
    """Return directory that contains bundled runtime assets."""
    if getattr(sys, "frozen", False):
        meipass = getattr(sys, "_MEIPASS", None)
        if meipass:
            app_dir = os.path.join(meipass, "app")
            if os.path.isdir(app_dir):
                return app_dir
            return meipass
    return os.path.dirname(os.path.abspath(__file__))


def get_user_data_dir() -> str:
    """Return writable per-user application data directory."""
    if sys.platform == "darwin":
        base = os.path.join(os.path.expanduser("~"), "Library", "Application Support")
    else:
        appdata = os.getenv("APPDATA")
        if appdata:
            base = appdata
        else:
            base = os.path.join(os.path.expanduser("~"), "AppData", "Roaming")

    path = os.path.join(base, APP_NAME)
    os.makedirs(path, exist_ok=True)
    return path


def get_logs_dir() -> str:
    """Return writable log directory under user app data."""
    logs_dir = os.path.join(get_user_data_dir(), "logs")
    os.makedirs(logs_dir, exist_ok=True)
    return logs_dir
