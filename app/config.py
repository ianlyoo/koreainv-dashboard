import os
from dotenv import load_dotenv

load_dotenv()


def _as_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _as_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default

APP_KEY = os.getenv("APP_KEY", "")
APP_SECRET = os.getenv("APP_SECRET", "")
URL_BASE = os.getenv("URL_BASE", "https://openapi.koreainvestment.com:9443")

CANO = os.getenv("CANO", "")
ACNT_PRDT_CD = os.getenv("ACNT_PRDT_CD", "01")
TRADE_MODE = os.getenv("TRADE_MODE", "live")  # "paper" or "live"
PAPER_URL_BASE = os.getenv("PAPER_URL_BASE", "https://openapivts.koreainvestment.com:29443")
CENTRAL_ORDER_SERVER_MODE = _as_bool("CENTRAL_ORDER_SERVER_MODE", False)
CENTRAL_ORDER_EXECUTION_ENABLED = _as_bool("CENTRAL_ORDER_EXECUTION_ENABLED", False)
CENTRAL_ORDER_SERVER_TOKEN = os.getenv("CENTRAL_ORDER_SERVER_TOKEN", "").strip()
CENTRAL_ORDER_MASTER_KEY = os.getenv("CENTRAL_ORDER_MASTER_KEY", "").strip()
CENTRAL_ORDER_REMOTE_URL = os.getenv("CENTRAL_ORDER_REMOTE_URL", "").strip().rstrip("/")
CENTRAL_ORDER_REMOTE_TOKEN = os.getenv("CENTRAL_ORDER_REMOTE_TOKEN", "").strip()
CENTRAL_ORDER_POLL_INTERVAL_SECONDS = _as_int("CENTRAL_ORDER_POLL_INTERVAL_SECONDS", 5)
