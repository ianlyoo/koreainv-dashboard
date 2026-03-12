from __future__ import annotations

import asyncio
import calendar
import datetime
import logging
import re
import threading
import time
from typing import Optional, Sequence

import yfinance as yf
from fastapi import APIRouter, HTTPException, Request

from app import api_client
from app.session_store import has_active_session, require_session


router = APIRouter()
_STOCK_SEARCH_CACHE_TTL_SECONDS = 120
_stock_search_cache: dict[str, dict[str, object]] = {}
_stock_search_cache_lock = threading.RLock()


def _coerce_cache_ts(value: object) -> float:
    if isinstance(value, int):
        return float(value)
    if isinstance(value, float):
        return value
    if isinstance(value, str):
        try:
            parsed = float(value)
            return parsed
        except ValueError:
            return 0.0
    return 0.0


def _get_stock_search_cache(query: str):
    with _stock_search_cache_lock:
        cached = _stock_search_cache.get(query)
        if cached and (time.time() - _coerce_cache_ts(cached.get("ts"))) < _STOCK_SEARCH_CACHE_TTL_SECONDS:
            return cached.get("data")
    return None


def _set_stock_search_cache(query: str, data):
    with _stock_search_cache_lock:
        _stock_search_cache[query] = {"ts": time.time(), "data": data}


def _today_kst() -> datetime.date:
    return datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date()


def _parse_month_value(month: Optional[str]) -> tuple[datetime.date, datetime.date]:
    today = _today_kst()
    if month:
        try:
            year, mon = month.split("-")
            first_day = datetime.date(int(year), int(mon), 1)
        except Exception as exc:
            raise HTTPException(
                status_code=400, detail="Invalid month format. Use YYYY-MM."
            ) from exc
    else:
        first_day = today.replace(day=1)

    last_day_num = calendar.monthrange(first_day.year, first_day.month)[1]
    month_last_day = datetime.date(first_day.year, first_day.month, last_day_num)
    end_day = min(today, month_last_day)
    return first_day, end_day


def _parse_date_value(raw_value: str, field_name: str) -> datetime.date:
    try:
        return datetime.date.fromisoformat(raw_value)
    except Exception as exc:
        raise HTTPException(
            status_code=400, detail=f"Invalid {field_name}. Use YYYY-MM-DD."
        ) from exc


def _float_value(value: object) -> float:
    if isinstance(value, int):
        return float(value)
    if isinstance(value, float):
        return value
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return 0.0
    return 0.0


def _int_value(value: object) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        try:
            return int(float(value))
        except ValueError:
            return 0
    return 0


def _serialize_realized_profit_payload(
    start_day: datetime.date, end_day: datetime.date, payload: object
) -> dict[str, object]:
    payload_dict: dict[str, object] = payload if isinstance(payload, dict) else {}
    summary = payload_dict.get("summary", {})
    summary_dict: dict[str, object] = summary if isinstance(summary, dict) else {}
    daily_source = payload_dict.get("daily", [])
    daily_source_list = daily_source if isinstance(daily_source, list) else []
    daily_rows = []
    for row_value in daily_source_list:
        row: dict[str, object] = row_value if isinstance(row_value, dict) else {}
        if not isinstance(row, dict):
            continue
        trade_date = str(row.get("date", ""))
        iso_date = trade_date
        if len(trade_date) == 8 and trade_date.isdigit():
            iso_date = f"{trade_date[:4]}-{trade_date[4:6]}-{trade_date[6:8]}"
        daily_rows.append(
            {
                "date": iso_date,
                "domestic_realized_profit_krw": round(
                    _float_value(row.get("domestic_realized_profit_krw", 0.0))
                ),
                "overseas_realized_profit_krw": round(
                    _float_value(row.get("overseas_realized_profit_krw", 0.0))
                ),
                "total_realized_profit_krw": round(
                    _float_value(row.get("total_realized_profit_krw", 0.0))
                ),
                "domestic_fee_krw": round(_float_value(row.get("domestic_fee_krw", 0.0))),
                "domestic_tax_krw": round(_float_value(row.get("domestic_tax_krw", 0.0))),
                "overseas_fee_krw": round(_float_value(row.get("overseas_fee_krw", 0.0))),
            }
        )

    return {
        "status": "success",
        "period": {
            "start": start_day.isoformat(),
            "end": end_day.isoformat(),
        },
        "summary": {
            "domestic_realized_profit_krw": round(
                _float_value(summary_dict.get("domestic_realized_profit_krw", 0.0))
            ),
            "overseas_realized_profit_krw": round(
                _float_value(summary_dict.get("overseas_realized_profit_krw", 0.0))
            ),
            "total_realized_profit_krw": round(
                _float_value(summary_dict.get("total_realized_profit_krw", 0.0))
            ),
            "total_realized_return_rate": round(
                _float_value(summary_dict.get("total_realized_return_rate", 0.0)), 2
            ),
            "trade_days": _int_value(summary_dict.get("trade_days", 0)),
        },
        "daily": daily_rows,
        "trades": payload_dict.get("trades", []),
    }


def _with_realized_profit_trades(payload: object, trades: Sequence[object]) -> dict[str, object]:
    payload_dict = payload if isinstance(payload, dict) else {}
    result = dict(payload_dict)
    result["trades"] = trades
    return result


@router.get("/api/sync")
async def sync_data(request: Request):
    session = require_session(request)
    session_id = request.cookies.get("session")
    try:
        token = await asyncio.to_thread(
            api_client.get_access_token, session.app_key, session.app_secret
        )
        if not token:
            raise HTTPException(
                status_code=500, detail="Failed to get access token from API"
            )

        domestic_task = asyncio.to_thread(
            api_client.get_domestic_balance,
            token,
            session.app_key,
            session.app_secret,
            session.cano,
            session.acnt_prdt_cd,
        )
        overseas_task = asyncio.to_thread(
            api_client.get_overseas_balance,
            token,
            session.app_key,
            session.app_secret,
            session.cano,
            session.acnt_prdt_cd,
        )
        domestic, overseas = await asyncio.gather(domestic_task, overseas_task)

        quote_service = getattr(request.app.state, "us_quote_service", None)
        if quote_service is not None:
            us_items = list(overseas.get("us_items") or [])
            quote_service.sync_session_holdings(
                session_id,
                session.app_key,
                session.app_secret,
                us_items,
            )
            overseas = dict(overseas)
            enriched_us_items = quote_service.enrich_us_items(us_items)
            overseas["us_items"] = enriched_us_items
            overseas["us_market_status"] = quote_service.get_market_status(
                enriched_us_items
            )
            if enriched_us_items:
                logging.warning(
                    "us_quote_debug %s",
                    [
                        {
                            "ticker": item.get("ticker"),
                            "source": item.get("quote_source"),
                            "stale": item.get("quote_stale"),
                            "tr_key": item.get("quote_tr_key"),
                            "quote_ts": item.get("quote_ts"),
                            "diag": quote_service.get_ticker_diagnostics(
                                item.get("ticker"), item.get("quote_tr_key")
                            ),
                        }
                        for item in enriched_us_items
                    ],
                )

        return {"status": "success", "domestic": domestic, "overseas": overseas}
    except HTTPException:
        raise
    except Exception:
        logging.exception("sync_data failed")
        raise HTTPException(status_code=500, detail="sync_data_failed")


@router.get("/api/us-quotes")
async def get_us_quotes(request: Request):
    require_session(request)
    quote_service = getattr(request.app.state, "us_quote_service", None)
    if quote_service is None:
        return {
            "status": "success",
            "overseas": {
                "us_items": [],
                "us_market_status": {
                    "session": "closed",
                    "is_open": False,
                    "uses_day_prefix": False,
                    "source_state": "idle",
                    "tracked_count": 0,
                    "fresh_count": 0,
                    "fallback_count": 0,
                },
            },
        }

    session_id = request.cookies.get("session")
    payload = quote_service.get_session_quote_payload(session_id)
    return {"status": "success", "overseas": payload}


@router.get("/api/realized-profit/summary")
async def get_realized_profit_summary(request: Request, month: Optional[str] = None):
    session = require_session(request)
    start_day, end_day = _parse_month_value(month)
    try:
        token = await asyncio.to_thread(
            api_client.get_access_token, session.app_key, session.app_secret
        )
        if not token:
            raise HTTPException(
                status_code=500, detail="Failed to get access token from API"
            )

        payload = await asyncio.to_thread(
            api_client.get_realized_profit_summary,
            token,
            session.app_key,
            session.app_secret,
            session.cano,
            session.acnt_prdt_cd,
            start_day.strftime("%Y%m%d"),
            end_day.strftime("%Y%m%d"),
        )
        return _serialize_realized_profit_payload(
            start_day,
            end_day,
            _with_realized_profit_trades(payload, []),
        )
    except HTTPException:
        raise
    except Exception:
        logging.exception("get_realized_profit_summary failed")
        raise HTTPException(status_code=500, detail="realized_profit_summary_failed")


@router.get("/api/realized-profit/detail")
async def get_realized_profit_detail(request: Request, start: str, end: str):
    session = require_session(request)
    start_day = _parse_date_value(start, "start")
    end_day = _parse_date_value(end, "end")
    if start_day > end_day:
        raise HTTPException(
            status_code=400, detail="start must be before or equal to end"
        )
    if (end_day - start_day).days > 370:
        raise HTTPException(status_code=400, detail="Date range is too large")

    try:
        token = await asyncio.to_thread(
            api_client.get_access_token, session.app_key, session.app_secret
        )
        if not token:
            raise HTTPException(
                status_code=500, detail="Failed to get access token from API"
            )

        payload_task = asyncio.to_thread(
            api_client.get_realized_profit_summary,
            token,
            session.app_key,
            session.app_secret,
            session.cano,
            session.acnt_prdt_cd,
            start_day.strftime("%Y%m%d"),
            end_day.strftime("%Y%m%d"),
        )
        trade_payload_task = asyncio.to_thread(
            api_client.get_trade_history,
            token,
            session.app_key,
            session.app_secret,
            session.cano,
            session.acnt_prdt_cd,
            start_day.strftime("%Y%m%d"),
            end_day.strftime("%Y%m%d"),
        )
        payload, trade_payload = await asyncio.gather(payload_task, trade_payload_task)
        trades = trade_payload.get("items", []) if isinstance(trade_payload, dict) else []
        return _serialize_realized_profit_payload(
            start_day,
            end_day,
            _with_realized_profit_trades(payload, trades),
        )
    except HTTPException:
        raise
    except Exception:
        logging.exception("get_realized_profit_detail failed")
        raise HTTPException(status_code=500, detail="realized_profit_detail_failed")


@router.get("/api/stock-search")
async def stock_search(request: Request, q: str = ""):
    if not has_active_session(request):
        raise HTTPException(status_code=401, detail="Unauthorized")

    if not q or len(q) < 1:
        return {"status": "success", "data": []}

    results = []
    raw_query = q.strip()
    query_upper = raw_query.upper()
    cached = _get_stock_search_cache(query_upper)
    if cached is not None:
        return {"status": "success", "data": cached}

    try:
        def run_search():
            local_results = []
            tickers_to_try = [query_upper]
            if raw_query.isdigit() and len(raw_query) <= 6:
                tickers_to_try.append(raw_query.zfill(6) + ".KS")
                tickers_to_try.append(raw_query.zfill(6) + ".KQ")

            for ticker_candidate in tickers_to_try:
                try:
                    info = yf.Ticker(ticker_candidate).info
                    if info and info.get("shortName"):
                        market = "USA"
                        ticker = ticker_candidate
                        if ticker_candidate.endswith(".KS") or ticker_candidate.endswith(
                            ".KQ"
                        ):
                            market = "KOR"
                        elif ticker_candidate.endswith(".T"):
                            market = "JPN"
                        local_results.append(
                            {
                                "ticker": ticker,
                                "name": info.get("shortName", ticker_candidate),
                                "market": market,
                            }
                        )
                except Exception:
                    pass

            try:
                search_results = yf.Search(raw_query)
                if hasattr(search_results, "quotes") and search_results.quotes:
                    for item in search_results.quotes[:8]:
                        symbol = item.get("symbol", "")
                        name = item.get("shortname", "") or item.get("longname", symbol)
                        exchange = item.get("exchange", "")
                        quote_type = str(item.get("quoteType", "")).upper()

                        symbol_u = str(symbol).upper()
                        name_l = str(name).lower()
                        is_occ_option = bool(
                            re.match(r"^[A-Z]{1,6}\d{6}[CP]\d{8}$", symbol_u)
                        )
                        has_option_word = (" call" in name_l) or (" put" in name_l)
                        if (
                            quote_type in {"OPTION", "FUTURE"}
                            or is_occ_option
                            or has_option_word
                        ):
                            continue
                        if quote_type and quote_type not in {"EQUITY", "ETF"}:
                            continue

                        market = "USA"
                        if exchange in ["KSC", "KOE"]:
                            market = "KOR"
                        elif exchange in ["JPX", "TYO"]:
                            market = "JPN"

                        if not any(
                            str(result.get("ticker", "")).upper() == symbol_u
                            for result in local_results
                        ):
                            local_results.append(
                                {"ticker": symbol, "name": name, "market": market}
                            )
            except Exception:
                pass
            return local_results

        results = await asyncio.to_thread(run_search)

        q_lower = raw_query.lower()
        market_rank = {"KOR": 0, "USA": 1, "JPN": 2}

        def relevance_key(item: dict[str, object]):
            name = str(item.get("name", "")).lower()
            ticker = str(item.get("ticker", "")).upper()
            ticker_exact = ticker == query_upper
            ticker_starts = ticker.startswith(query_upper)
            ticker_contains = query_upper in ticker
            name_exact = name == q_lower
            name_starts = name.startswith(q_lower)
            name_contains = q_lower in name
            return (
                0 if ticker_exact else 1,
                0 if ticker_starts else 1,
                0 if ticker_contains else 1,
                0 if name_exact else 1,
                0 if name_starts else 1,
                0 if name_contains else 1,
                market_rank.get(str(item.get("market", "")).upper(), 9),
                len(ticker),
            )

        results.sort(key=relevance_key)
    except Exception as exc:
        logging.error("Stock search error: %s", exc)

    top_results = results[:10]
    _set_stock_search_cache(query_upper, top_results)
    return {"status": "success", "data": top_results}
