import os
from dotenv import load_dotenv

load_dotenv()

APP_KEY = os.getenv("APP_KEY", "")
APP_SECRET = os.getenv("APP_SECRET", "")
URL_BASE = os.getenv("URL_BASE", "https://openapi.koreainvestment.com:9443")

CANO = os.getenv("CANO", "")
ACNT_PRDT_CD = os.getenv("ACNT_PRDT_CD", "01")
TRADE_MODE = os.getenv("TRADE_MODE", "live")  # "paper" or "live"
PAPER_URL_BASE = os.getenv("PAPER_URL_BASE", "https://openapivts.koreainvestment.com:29443")
