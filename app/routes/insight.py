from __future__ import annotations

import asyncio
import datetime
import logging
import threading
import time
import urllib.parse

import yfinance as yf
from fastapi import APIRouter


router = APIRouter()
logger = logging.getLogger(__name__)
_INSIGHT_CACHE_TTL_SECONDS = 300
_insight_cache: dict[str, dict[str, object]] = {}
_insight_cache_lock = threading.RLock()


def _get_cached_insight(cache_key: str):
    with _insight_cache_lock:
        cached = _insight_cache.get(cache_key)
        cached_ts = cached.get("ts", 0.0) if isinstance(cached, dict) else 0.0
        cached_ts_value = 0.0
        if isinstance(cached_ts, (int, float)):
            cached_ts_value = float(cached_ts)
        elif isinstance(cached_ts, str):
            try:
                cached_ts_value = float(cached_ts)
            except ValueError:
                cached_ts_value = 0.0
        if cached and (time.time() - cached_ts_value) < _INSIGHT_CACHE_TTL_SECONDS:
            return cached.get("data")
    return None


def _set_cached_insight(cache_key: str, data: object):
    with _insight_cache_lock:
        _insight_cache[cache_key] = {"ts": time.time(), "data": data}


def _as_str(value: object, default: str = "") -> str:
    if isinstance(value, str):
        return value
    if value is None:
        return default
    return str(value)


def _as_float(value: object, default: float = 0.0) -> float:
    if isinstance(value, int):
        return float(value)
    if isinstance(value, float):
        return value
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return default
    return default


def _resolve_yf_ticker(ticker: str, market_type: str) -> tuple[str, dict[str, object]]:
    normalized_ticker = str(ticker or "").strip()
    normalized_market = str(market_type or "USA").upper()

    if normalized_market == "KOR" and not normalized_ticker.endswith((".KS", ".KQ")):
        for suffix in (".KS", ".KQ"):
            candidate = normalized_ticker + suffix
            try:
                info = yf.Ticker(candidate).info
            except Exception:
                info = {}
            if info and (
                info.get("regularMarketPrice") is not None
                or info.get("currentPrice") is not None
            ):
                return candidate, info
        fallback_ticker = normalized_ticker + ".KS"
        return fallback_ticker, yf.Ticker(fallback_ticker).info

    if normalized_market == "JPN" and not normalized_ticker.endswith(".T"):
        normalized_ticker = normalized_ticker + ".T"

    return normalized_ticker, yf.Ticker(normalized_ticker).info


def _build_financials(info: dict[str, object], fallback_ticker: str) -> tuple[dict[str, object], float]:
    logo_url = info.get("logo_url", "")
    if not logo_url:
        website = _as_str(info.get("website", ""), "")
        if website:
            try:
                parsed_uri = urllib.parse.urlparse(website)
                domain = parsed_uri.netloc.replace("www.", "")
                logo_url = (
                    f"https://img.logo.dev/{domain}?token=pk_Wf5NHZcSQmOdtQZWUZA9TA"
                )
            except Exception:
                pass

    current_price = _as_float(
        info.get("currentPrice") or info.get("regularMarketPrice") or 0,
        0.0,
    )
    financials = {
        "forwardPE": info.get("forwardPE") or "N/A",
        "returnOnEquity": info.get("returnOnEquity")
        if info.get("returnOnEquity") is not None
        else "N/A",
        "debtToEquity": info.get("debtToEquity") or "N/A",
        "currentPrice": current_price if current_price else "N/A",
        "shortName": info.get("shortName") or fallback_ticker,
        "currency": info.get("currency") or "",
        "recommendation": info.get("recommendationKey") or "N/A",
        "logo_url": logo_url,
        "fiftyTwoWeekHigh": info.get("fiftyTwoWeekHigh") or "N/A",
        "fiftyTwoWeekLow": info.get("fiftyTwoWeekLow") or "N/A",
        "beta": info.get("beta") if info.get("beta") is not None else "N/A",
        "marketCap": info.get("marketCap") or "N/A",
        "shortPercentOfFloat": info.get("shortPercentOfFloat")
        if info.get("shortPercentOfFloat") is not None
        else "N/A",
        "targetMeanPrice": info.get("targetMeanPrice") or "N/A",
        "targetHighPrice": info.get("targetHighPrice") or "N/A",
        "targetLowPrice": info.get("targetLowPrice") or "N/A",
        "dividendYield": info.get("dividendYield")
        if info.get("dividendYield") is not None
        else "N/A",
    }
    return financials, float(current_price or 0)


def _fetch_options_data(yf_ticker: str, current_price: float):
    try:
        tc = yf.Ticker(yf_ticker)
        opts = tc.options
        if not opts:
            return None

        def _build_confidence_meta(level: str, reason: str) -> dict[str, str]:
            labels = {
                "high": "높음",
                "medium": "보통",
                "low": "낮음",
                "none": "미산출",
            }
            return {
                "level": level,
                "label": labels.get(level, "미산출"),
                "reason": reason,
            }

        def _sum_metric(df, col: str) -> int:
            if df is None or df.empty or col not in df.columns:
                return 0
            try:
                return int(df[col].fillna(0).clip(lower=0).sum())
            except Exception:
                return 0

        nearest_date = sorted(str(opt) for opt in opts)[0]
        chain = tc.option_chain(nearest_date)
        calls_vol = _sum_metric(getattr(chain, "calls", None), "volume")
        puts_vol = _sum_metric(getattr(chain, "puts", None), "volume")
        calls_oi = _sum_metric(getattr(chain, "calls", None), "openInterest")
        puts_oi = _sum_metric(getattr(chain, "puts", None), "openInterest")

        import numpy as np

        oi_trust_min = 100
        price_ref = float(current_price or 0)
        low_band = price_ref * 0.85 if price_ref > 0 else 0.0
        high_band = price_ref * 1.15 if price_ref > 0 else float("inf")
        recent_cutoff = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=45)
        strike_near_limit = 24

        def _prepare_filtered(df, side: str):
            if df is None or df.empty:
                return df
            work = df.copy()
            if "strike" not in work.columns:
                return work
            if "openInterest" not in work.columns:
                work["openInterest"] = 0
            if "volume" not in work.columns:
                work["volume"] = 0
            work["strike"] = work["strike"].fillna(0)
            work["openInterest"] = work["openInterest"].fillna(0).clip(lower=0)
            work["volume"] = work["volume"].fillna(0).clip(lower=0)

            if "lastTradeDate" in work.columns:
                try:
                    last_trade = work["lastTradeDate"]
                    dt = last_trade
                    if getattr(last_trade, "dt", None) is None:
                        import pandas as pd

                        dt = pd.to_datetime(last_trade, utc=True, errors="coerce")
                    recent = work[(dt.isna()) | (dt >= recent_cutoff)]
                    if not recent.empty and (
                        float(recent["openInterest"].sum()) > 0
                        or float(recent["volume"].sum()) > 0
                    ):
                        work = recent
                except Exception:
                    pass

            if price_ref > 0:
                near = work[(work["strike"] >= low_band) & (work["strike"] <= high_band)]
                if not near.empty and (
                    float(near["openInterest"].sum()) > 0
                    or float(near["volume"].sum()) > 0
                ):
                    work = near

                directional = work[work["strike"] >= price_ref] if side == "call" else work[work["strike"] <= price_ref]
                if not near.empty and (
                    float(near["openInterest"].sum()) > 0
                    or float(near["volume"].sum()) > 0
                ):
                    chosen = directional if (
                        not directional.empty
                        and (
                            float(directional["openInterest"].sum()) > 0
                            or float(directional["volume"].sum()) > 0
                        )
                    ) else work
                    chosen = chosen.assign(_dist=(chosen["strike"] - price_ref).abs())
                    chosen = chosen.sort_values("_dist", ascending=True).head(strike_near_limit)
                    return chosen.drop(columns=["_dist"])
            return work

        calls_for_metrics = _prepare_filtered(chain.calls, "call")
        puts_for_metrics = _prepare_filtered(chain.puts, "put")

        filtered_calls_oi = _sum_metric(calls_for_metrics, "openInterest")
        filtered_puts_oi = _sum_metric(puts_for_metrics, "openInterest")
        filtered_total_oi = filtered_calls_oi + filtered_puts_oi
        filtered_weaker_side_oi = min(filtered_calls_oi, filtered_puts_oi)

        def _max_strike_by_metric(df, prefer_oi: bool):
            if df is None or df.empty or "strike" not in df.columns:
                return 0
            work = df.copy()
            if "openInterest" not in work.columns:
                work["openInterest"] = 0
            if "volume" not in work.columns:
                work["volume"] = 0
            work["openInterest"] = work["openInterest"].fillna(0).clip(lower=0)
            work["volume"] = work["volume"].fillna(0).clip(lower=0)

            metric = work["openInterest"] if prefer_oi else work["volume"]
            if float(metric.sum()) <= 0:
                metric = work["volume"]
                if not prefer_oi:
                    metric = work["openInterest"] if float(work["openInterest"].sum()) > 0 else work["volume"]
            if float(metric.sum()) <= 0:
                return 0

            idx = metric.idxmax()
            return float(work.loc[idx]["strike"])

        oi_reliable = calls_oi >= oi_trust_min and puts_oi >= oi_trust_min
        max_call_oi_strike = _max_strike_by_metric(calls_for_metrics, prefer_oi=oi_reliable)
        max_put_oi_strike = _max_strike_by_metric(puts_for_metrics, prefer_oi=oi_reliable)
        strike_basis = "OI" if oi_reliable else "Volume"

        max_pain = 0
        call_strikes = (
            calls_for_metrics["strike"].values
            if calls_for_metrics is not None and not calls_for_metrics.empty and "strike" in calls_for_metrics.columns
            else []
        )
        put_strikes = (
            puts_for_metrics["strike"].values
            if puts_for_metrics is not None and not puts_for_metrics.empty and "strike" in puts_for_metrics.columns
            else []
        )
        strikes = np.unique(np.concatenate((call_strikes, put_strikes)))
        strikes_count = int(len(strikes))
        total_oi = calls_oi + puts_oi
        weaker_side_oi = min(calls_oi, puts_oi)

        oi_confidence = _build_confidence_meta("none", "근월물 OI 데이터가 없습니다.")
        if total_oi <= 0:
            oi_confidence = _build_confidence_meta("none", "근월물 OI 데이터가 없어 신뢰도를 계산할 수 없습니다.")
        elif weaker_side_oi >= 100 and total_oi >= 500:
            oi_confidence = _build_confidence_meta("high", "콜/풋 양측 OI가 모두 충분합니다.")
        elif weaker_side_oi >= 25 and total_oi >= 150:
            oi_confidence = _build_confidence_meta("medium", "양측 OI가 있어 근월물 해석에 활용 가능합니다.")
        elif calls_oi > 0 and puts_oi > 0:
            oi_confidence = _build_confidence_meta("low", "양측 OI가 있지만 규모가 작아 신호가 약합니다.")
        else:
            oi_confidence = _build_confidence_meta("low", "한쪽 OI가 부족해 해석 편향 가능성이 있습니다.")

        max_pain_confidence = _build_confidence_meta("none", "근월물 OI가 부족해 맥스페인을 계산할 수 없습니다.")

        if strikes_count <= 0:
            max_pain_confidence = _build_confidence_meta("none", "근월물 유효 행사가가 부족해 맥스페인을 계산할 수 없습니다.")
        elif filtered_total_oi > 0:
            calls_oi_series = (
                calls_for_metrics["openInterest"].fillna(0).clip(lower=0)
                if calls_for_metrics is not None and "openInterest" in calls_for_metrics.columns
                else 0
            )
            puts_oi_series = (
                puts_for_metrics["openInterest"].fillna(0).clip(lower=0)
                if puts_for_metrics is not None and "openInterest" in puts_for_metrics.columns
                else 0
            )
            min_loss = float("inf")
            for strike in strikes:
                loss = 0.0
                if (
                    calls_for_metrics is not None
                    and not calls_for_metrics.empty
                    and "strike" in calls_for_metrics.columns
                    and "openInterest" in calls_for_metrics.columns
                ):
                    loss += (
                        (calls_for_metrics["strike"] < strike)
                        * (strike - calls_for_metrics["strike"])
                        * calls_oi_series
                    ).sum()
                if (
                    puts_for_metrics is not None
                    and not puts_for_metrics.empty
                    and "strike" in puts_for_metrics.columns
                    and "openInterest" in puts_for_metrics.columns
                ):
                    loss += (
                        (puts_for_metrics["strike"] > strike)
                        * (puts_for_metrics["strike"] - strike)
                        * puts_oi_series
                    ).sum()
                if loss < min_loss:
                    min_loss = loss
                    max_pain = float(strike)

            if max_pain > 0:
                if filtered_weaker_side_oi >= 100 and filtered_total_oi >= 500 and strikes_count >= 8:
                    max_pain_confidence = _build_confidence_meta("high", "양측 OI와 근처 행사가 분포가 충분합니다.")
                elif filtered_weaker_side_oi >= 25 and filtered_total_oi >= 150 and strikes_count >= 6:
                    max_pain_confidence = _build_confidence_meta("medium", "근월물 OI가 제한적이지만 참고 가능한 수준입니다.")
                elif filtered_calls_oi > 0 and filtered_puts_oi > 0:
                    max_pain_confidence = _build_confidence_meta("low", "양측 OI가 얇아 참고용으로만 보는 것이 좋습니다.")
                else:
                    max_pain_confidence = _build_confidence_meta("low", "한쪽 OI 의존도가 높아 신뢰도가 낮습니다.")

        atm_iv = None
        try:
            if current_price and not chain.calls.empty and "impliedVolatility" in chain.calls.columns:
                atm_call = chain.calls.iloc[(chain.calls["strike"] - float(current_price)).abs().argsort()[:1]]
                iv_val = atm_call["impliedVolatility"].values[0]
                if iv_val and iv_val > 0:
                    atm_iv = round(float(iv_val) * 100, 2)
        except Exception:
            pass

        pcr_volume = round(puts_vol / calls_vol, 2) if calls_vol > 0 else (None if puts_vol == 0 else "High")
        pcr_oi = round(puts_oi / calls_oi, 2) if calls_oi > 0 else (None if puts_oi == 0 else "High")
        pcr_basis = "OI" if oi_reliable else "Volume"
        pcr = pcr_oi if pcr_basis == "OI" else pcr_volume
        oi_available = (calls_oi + puts_oi) > 0

        return {
            "date": nearest_date,
            "calls_volume": calls_vol,
            "calls_oi": calls_oi,
            "puts_volume": puts_vol,
            "puts_oi": puts_oi,
            "oi_available": oi_available,
            "oi_confidence": oi_confidence,
            "max_pain": max_pain,
            "max_pain_available": max_pain > 0,
            "max_pain_confidence": max_pain_confidence,
            "filtered_calls_oi": filtered_calls_oi,
            "filtered_puts_oi": filtered_puts_oi,
            "max_call_oi_strike": max_call_oi_strike,
            "max_put_oi_strike": max_put_oi_strike,
            "strike_basis": strike_basis,
            "strikes_count": strikes_count,
            "oi_reliable": oi_reliable,
            "atm_iv": atm_iv,
            "pcr": pcr,
            "pcr_basis": pcr_basis,
            "pcr_oi": pcr_oi,
            "pcr_volume": pcr_volume,
        }
    except Exception as exc:
        logger.warning("Options parsing error: %s", exc)
        return None


def _fetch_news_data(yf_ticker: str) -> list[dict[str, str]]:
    news_data = []
    try:
        news = yf.Ticker(yf_ticker).news
        if news:
            for item in news[:5]:
                content = item.get("content") if isinstance(item, dict) else {}
                if not isinstance(content, dict):
                    content = {}
                title = content.get("title", item.get("title", ""))
                provider_info = content.get("provider", {})
                if not isinstance(provider_info, dict):
                    provider_info = {}
                provider = provider_info.get(
                    "displayName", item.get("publisher", "Yahoo Finance")
                )
                click_data = content.get("clickThroughUrl", {})
                if not isinstance(click_data, dict):
                    click_data = {}
                link = click_data.get("url", item.get("link", ""))
                if title:
                    news_data.append(
                        {"title": title, "publisher": provider, "link": link}
                    )
    except Exception as exc:
        logger.warning("News parsing error: %s", exc)
    return news_data


def _fetch_history_data(yf_ticker: str) -> list[dict[str, object]]:
    history_data = []
    try:
        hist = yf.Ticker(yf_ticker).history(period="6mo", interval="1d")
        if hist is not None and not hist.empty:
            for idx, row in hist.iterrows():
                time_value = str(idx)[:10]
                volume_value = row.get("Volume", 0)
                history_data.append(
                    {
                        "time": time_value,
                        "open": round(float(row["Open"]), 2),
                        "high": round(float(row["High"]), 2),
                        "low": round(float(row["Low"]), 2),
                        "close": round(float(row["Close"]), 2),
                        "volume": int(float(volume_value or 0)),
                    }
                )
    except Exception as exc:
        logger.warning("History data error: %s", exc)
    return history_data


@router.get("/api/asset-insight")
async def get_asset_insight(ticker: str, market_type: str = "USA"):
    try:
        if not ticker:
            return {"status": "error", "message": "Ticker not provided."}

        cache_key = f"{str(market_type or 'USA').upper()}:{str(ticker).strip().upper()}"
        cached = _get_cached_insight(cache_key)
        if cached is not None:
            return cached

        yf_ticker, info = await asyncio.to_thread(_resolve_yf_ticker, ticker, market_type)
        financials, current_price = await asyncio.to_thread(_build_financials, info, ticker)
        options_task = asyncio.to_thread(_fetch_options_data, yf_ticker, current_price)
        news_task = asyncio.to_thread(_fetch_news_data, yf_ticker)
        history_task = asyncio.to_thread(_fetch_history_data, yf_ticker)
        options_data, news_data, history_data = await asyncio.gather(
            options_task,
            news_task,
            history_task,
        )

        payload = {
            "status": "success",
            "data": {
                "financials": financials,
                "options": options_data,
                "news": news_data,
                "history": history_data,
            },
        }
        _set_cached_insight(cache_key, payload)
        return payload
    except Exception as exc:
        logger.exception("Insight info error: %s", exc)
        return {"status": "error", "message": str(exc)}
