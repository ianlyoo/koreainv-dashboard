import os
from dotenv import load_dotenv

load_dotenv()


def _as_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


APP_KEY = os.getenv("APP_KEY", "")
APP_SECRET = os.getenv("APP_SECRET", "")
URL_BASE = os.getenv("URL_BASE", "https://openapi.koreainvestment.com:9443")

CANO = os.getenv("CANO", "")
ACNT_PRDT_CD = os.getenv("ACNT_PRDT_CD", "01")
TRADE_MODE = os.getenv("TRADE_MODE", "live")  # "paper" or "live"
PAPER_URL_BASE = os.getenv("PAPER_URL_BASE", "https://openapivts.koreainvestment.com:29443")
TOSS_PROXY_SERVER_ENABLED = _as_bool("TOSS_PROXY_SERVER_ENABLED", False)
TOSS_PROXY_SERVER_TOKEN = os.getenv("TOSS_PROXY_SERVER_TOKEN", "").strip()
TOSS_PROXY_REMOTE_URL = os.getenv("TOSS_PROXY_REMOTE_URL", "").strip().rstrip("/")
TOSS_PROXY_REMOTE_TOKEN = os.getenv("TOSS_PROXY_REMOTE_TOKEN", "").strip()

# Server-only credentials. Session cookies are kept in process memory.
SAVETICKER_EMAIL = os.getenv("SAVETICKER_EMAIL", "").strip()
SAVETICKER_PASSWORD = os.getenv("SAVETICKER_PASSWORD", "")
SAVETICKER_ENABLED = _as_bool("SAVETICKER_ENABLED", True)
