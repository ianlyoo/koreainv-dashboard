#!/usr/bin/env python3
"""Local UI preview with fictional data; never imports brokerage clients or workers.

Run with Python 3.11: python scripts/preview_web_dashboard.py --port 8770
An optional output/playwright/web-baseline/app snapshot is served at /baseline/.
"""
from __future__ import annotations

import argparse
import html
import json
import math
import mimetypes
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
INSIGHT_SNAPSHOTS = {}
ACCOUNTS = [
    {"account_id": "preview-kis", "label": "장기 투자", "broker": "kis", "broker_name": "한국투자증권", "cano_masked": "0000****", "cano": "00000000", "acnt_prdt_cd": "01"},
    {"account_id": "preview-toss", "label": "해외 투자", "broker": "toss", "broker_name": "토스증권", "cano_masked": "1111****", "cano": "11111111", "acnt_prdt_cd": "01"},
]


def holding(ticker, name, qty, avg, now, account=0, rate=1):
    return {"ticker": ticker, "name": name, "qty": qty, "avg_price": avg, "now_price": now,
            "bass_exrt": rate, "profit_rt": (now / avg - 1) * 100, "account_id": ACCOUNTS[account]["account_id"],
            "account_label": ACCOUNTS[account]["label"], "broker": ACCOUNTS[account]["broker"]}


DOMESTIC = [holding("005930", "삼성전자", 180, 68000, 73500), holding("000660", "SK하이닉스", 70, 175000, 162000), holding("035420", "NAVER", 45, 190000, 214000)]
US = [holding("AAPL", "Apple Inc.", 70, 190, 225, 1, 1350), holding("NVDA", "NVIDIA Corporation", 90, 130, 120, 1, 1350), holding("MSFT", "Microsoft Corporation", 40, 380, 425, 1, 1350)]
JP = [holding("7203", "Toyota Motor", 160, 2450, 2700, 0, 905)]
SYNC = {"status": "success", "accounts": ACCOUNTS, "account_errors": [],
        "domestic": {"summary": {"cash_balance": 12840000}, "items": DOMESTIC},
        "overseas": {"us_summary": {"usd_cash_balance": 2400, "usd_exrt": 1350},
                     "jp_summary": {"jpy_cash_balance": 50000, "jpy_exrt": 905},
                     "us_items": US, "jp_items": JP, "us_market_status": {"session": "closed", "label": "휴장", "is_trading_day": False}}}


WIDE_US = [
    holding("AVGO", "Broadcom Inc.", 80, 390, 368.78, 1, 1350),
    holding("KLAC", "KLA Corporation", 100, 210, 188, 1, 1350),
    holding("QCOM", "Qualcomm Inc.", 100, 196, 178, 1, 1350),
    holding("AMZN", "Amazon.com Inc.", 70, 208, 221, 1, 1350),
    holding("GOOGL", "Alphabet Inc.", 75, 181, 173, 1, 1350),
    holding("META", "Meta Platforms Inc.", 24, 530, 559, 1, 1350),
    holding("CRDO", "Credo Technology Group", 120, 182, 168, 1, 1350),
    holding("IBM", "International Business Machines", 60, 253, 231, 1, 1350),
    holding("TSM", "Taiwan Semiconductor", 85, 197, 189, 1, 1350),
    holding("COST", "Costco Wholesale", 12, 920, 947, 1, 1350),
]
SYNC_WIDE = {**SYNC, "overseas": {**SYNC["overseas"], "us_items": US + WIDE_US}}


def history_payload(query):
    start = query.get("start", ["2026-09-01"])[0]
    end = query.get("end", ["2026-09-08"])[0]
    trades = [
        {"date": "2026-09-07", "side": "매수", "ticker": "005930", "symbol": "005930", "name": "삼성전자", "market": "KOR", "quantity": 20, "unit_price": 71000, "amount": 1420000, "amount_krw": 1420000, "currency": "KRW", "account_id": "preview-kis", "account_label": "장기 투자", "realized_profit_krw": None, "realized_return_rate": None},
        {"date": "2026-09-04", "side": "매도", "ticker": "AAPL", "symbol": "AAPL", "name": "Apple Inc.", "market": "USA", "quantity": 10, "unit_price": 225, "amount": 2250, "amount_krw": 3037500, "currency": "USD", "account_id": "preview-toss", "account_label": "해외 투자", "realized_profit_krw": 472500, "realized_return_rate": 18.42},
        {"date": "2026-09-03", "side": "매도", "ticker": "000660", "symbol": "000660", "name": "SK하이닉스", "market": "KOR", "quantity": 5, "unit_price": 162000, "amount": 810000, "amount_krw": 810000, "currency": "KRW", "account_id": "preview-kis", "account_label": "장기 투자", "realized_profit_krw": -65000, "realized_return_rate": -7.43},
    ]
    side = query.get("side", ["buy"])[0]
    visible = [t for t in trades if (t["side"] == "매도") == (side == "sell")]
    return {"status": "success", "filters": {"side": side}, "period": {"start": start, "end": end}, "profit_available": True, "profit_complete": True,
            "summary": {"total_realized_profit_krw": 407500, "domestic_realized_profit_krw": -65000, "overseas_realized_profit_krw": 472500, "total_realized_return_rate": 9.3},
            "items": visible, "trades": visible, "buy_trades": [trades[0]], "sell_trades": trades[1:],
            "buy_items": [trades[0]], "sell_items": trades[1:], "pagination": {"page": 1, "page_size": 10, "total_items": len(visible), "total_pages": 1}}


def insight_payload(ticker):
    if ticker.upper() in INSIGHT_SNAPSHOTS:
        return INSIGHT_SNAPSHOTS[ticker.upper()]
    stock = next((i for i in DOMESTIC + US + JP + WIDE_US if i["ticker"] == ticker), US[0])
    price = stock["now_price"]
    history = []
    for i in range(80):
        close = price * (0.89 + i / 700 + math.sin(i * .6) * .022)
        history.append({"time": str(date(2026, 6, 1) + timedelta(days=i)), "open": close * .995,
                        "high": close * 1.015, "low": close * .985, "close": close, "volume": 1200000 + i * 17000})
    return {"status": "success", "data": {"financials": {"shortName": stock["name"], "currentPrice": price,
             "forwardPE": 18.4, "returnOnEquity": .164, "debtToEquity": 31.8, "dividendYield": .012,
             "marketCap": 432000000000, "revenueGrowth": .094, "earningsGrowth": .126, "profitMargins": .218,
             "fiftyTwoWeekHigh": price * 1.18, "fiftyTwoWeekLow": price * .71, "targetMeanPrice": price * 1.12, "currency": "KRW" if stock in DOMESTIC else "JPY" if stock in JP else "USD", "beta": 1.12, "shortPercentOfFloat": .026, "recommendation": "hold"},
             "history": history, "options": None if stock in DOMESTIC + JP else {
                 "date": "2026-09-18", "atm_iv": 48.6, "calls_volume": 68000, "puts_volume": 28000,
                 "pcr": 28000 / 68000, "pcr_basis": "Volume", "calls_oi": 31000, "puts_oi": 25000,
                 "oi_available": True, "max_pain_available": True, "max_pain": round(price / 5) * 5,
                 "max_call_oi_strike": round(price * 1.08 / 5) * 5, "max_put_oi_strike": round(price * .92 / 5) * 5,
                 "oi_confidence": {"level": "none", "label": "예시", "reason": "디자인 확인용 합성 옵션 데이터입니다."},
                 "max_pain_confidence": {"level": "none", "label": "예시", "reason": "실제 투자 데이터가 아닙니다."}},
             "news": [{"title": "분기 실적과 시장 동향 살펴보기", "publisher": "미리보기 뉴스", "link": "https://example.com", "providerPublishTime": 1788840000}]}}


class PreviewHandler(BaseHTTPRequestHandler):
    def payload(self, value, status=200):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        request = urlsplit(self.path)
        path = unquote(request.path)
        query = parse_qs(request.query)
        if path == "/api/status":
            setup = urlsplit(self.headers.get("Referer", "")).path.endswith("/setup")
            return self.payload({"setup_complete": not setup, "authenticated": False})
        if path == "/api/sync":
            wide = parse_qs(urlsplit(self.headers.get("Referer", "")).query).get("fixture") == ["wide"]
            return self.payload(SYNC_WIDE if wide else SYNC)
        if path == "/api/accounts": return self.payload({"status": "success", "accounts": ACCOUNTS})
        if path.startswith("/api/realized-profit/"): return self.payload(history_payload(query))
        if path == "/api/market-calendar":
            wide = parse_qs(urlsplit(self.headers.get("Referer", "")).query).get("fixture") == ["wide"]
            names = ["미국 소비자물가지수 (CPI)", "유럽중앙은행 금리 결정", "미국 신규 실업수당 청구건수"]
            if wide:
                names += ["MBA 모기지 지수", "ADP 민간 고용", "주간 원유 재고", "미국 소비자신용", "일본 경상수지", "독일 무역수지", "미국 생산자물가지수", "주간 천연가스 재고", "소비자 기대지수"]
            events = [{"event": name, "currency": "USD", "time": f"09.10 {20 + i // 4:02d}:{i % 4 * 15:02d}", "importance": 3 if i % 3 == 0 else 2, "actual": "-", "forecast": "-", "previous": "-"} for i, name in enumerate(names)]
            return self.payload({"status": "success", "data": events})
        if path == "/api/stock-search":
            items = [{"ticker": i["ticker"], "symbol": i["ticker"], "name": i["name"], "market": "USA" if i in US else "JPN" if i in JP else "KOR"} for i in DOMESTIC + US + JP]
            return self.payload({"status": "success", "results": items, "data": items})
        if path == "/api/asset-insight": return self.payload(insight_payload(query.get("ticker", ["005930"])[0]))
        if path.startswith("/api/"): return self.payload({"detail": "Preview endpoint not available"}, 404)
        baseline = path.startswith("/baseline/")
        base = ROOT / "output/playwright/web-baseline/app" if baseline else ROOT / "app"
        if baseline: path = path[len("/baseline"):]
        if path in ("/", "/login", "/setup"):
            file = base / "templates" / ("index.html" if path == "/" else "login.html")
        elif path.startswith(("/static/", "/img/")):
            file = (base / path.lstrip("/")).resolve()
            if not file.is_relative_to(base.resolve()): return self.send_error(404)
        else: return self.send_error(404)
        if not file.is_file(): return self.send_error(404)
        data = file.read_bytes()
        if file.suffix == ".html":
            data = data.replace(b"__ASSET_VERSION__", b"web-preview")
            if baseline: data = data.replace(b'"/static/', b'"/baseline/static/')
            caption = '디자인 미리보기 · 합성 데이터'
            if INSIGHT_SNAPSHOTS:
                symbols = html.escape(', '.join(sorted(INSIGHT_SNAPSHOTS)))
                caption = f'계좌: 합성 데이터 · {symbols} 종목정보: SaveTicker 조회 스냅샷'
            notice = f'<div role="note" style="position:fixed;bottom:12px;left:16px;font:11px system-ui;color:var(--text-muted);z-index:99999;pointer-events:none">{caption}</div>'
            data = data.replace(b"</body>", notice.encode() + b"</body>")
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(file.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        # Preview never forwards an action or loads credentials. Retired endpoints stay absent.
        if "scheduled-order" in self.path or "/orders" in self.path:
            return self.payload({"detail": "Not Found"}, 404)
        return self.payload({"status": "success", "message": "미리보기 작업 완료"})

    do_PATCH = do_POST
    do_DELETE = do_POST


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--insight-snapshot", type=Path, help="Optional ticker-to-response JSON captured from the insight API; no credentials or live requests.")
    args = parser.parse_args()
    if args.insight_snapshot:
        snapshots = json.loads(args.insight_snapshot.read_text())
        if not isinstance(snapshots, dict) or any(
            not isinstance(ticker, str) or not isinstance(value, dict)
            or value.get('status') != 'success'
            or not isinstance(value.get('data'), dict)
            or value['data'].get('source') != 'saveticker'
            for ticker, value in snapshots.items()
        ):
            parser.error('Insight snapshot must map tickers to successful SaveTicker insight responses.')
        INSIGHT_SNAPSHOTS.update({ticker.upper(): value for ticker, value in snapshots.items()})
    print(f"Fictional dashboard preview: http://127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), PreviewHandler).serve_forever()
