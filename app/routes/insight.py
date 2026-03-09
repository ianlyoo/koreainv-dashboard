from __future__ import annotations

import datetime
import logging
import urllib.parse

import yfinance as yf
from fastapi import APIRouter


router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/api/asset-insight")
async def get_asset_insight(ticker: str, market_type: str = "USA"):
    try:
        if not ticker:
            return {"status": "error", "message": "Ticker not provided."}

        yf_ticker = ticker
        if market_type == "KOR":
            if not ticker.endswith(".KS") and not ticker.endswith(".KQ"):
                yf_ticker = ticker + ".KS"
                test_tc = yf.Ticker(yf_ticker)
                test_info = test_tc.info
                if not test_info or test_info.get("regularMarketPrice") is None:
                    yf_ticker = ticker + ".KQ"
        elif market_type == "JPN":
            if not ticker.endswith(".T"):
                yf_ticker = ticker + ".T"

        tc = yf.Ticker(yf_ticker)
        info = tc.info

        logo_url = info.get("logo_url", "")
        if not logo_url:
            website = info.get("website", "")
            if website:
                try:
                    parsed_uri = urllib.parse.urlparse(website)
                    domain = parsed_uri.netloc.replace("www.", "")
                    logo_url = (
                        f"https://img.logo.dev/{domain}?token=pk_Wf5NHZcSQmOdtQZWUZA9TA"
                    )
                except Exception:
                    pass

        current_price = info.get("currentPrice") or info.get("regularMarketPrice") or 0
        financials = {
            "forwardPE": info.get("forwardPE") or "N/A",
            "returnOnEquity": info.get("returnOnEquity")
            if info.get("returnOnEquity") is not None
            else "N/A",
            "debtToEquity": info.get("debtToEquity") or "N/A",
            "currentPrice": current_price if current_price else "N/A",
            "shortName": info.get("shortName") or ticker,
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

        options_data = None
        try:
            opts = tc.options
            if opts:

                def _sum_metric(df, col: str) -> int:
                    if df is None or df.empty or col not in df.columns:
                        return 0
                    try:
                        return int(df[col].fillna(0).clip(lower=0).sum())
                    except Exception:
                        return 0

                oi_trust_min = 100
                nearest_date = opts[0]
                chain = tc.option_chain(nearest_date)
                calls_vol = _sum_metric(getattr(chain, "calls", None), "volume")
                puts_vol = _sum_metric(getattr(chain, "puts", None), "volume")
                calls_oi = _sum_metric(getattr(chain, "calls", None), "openInterest")
                puts_oi = _sum_metric(getattr(chain, "puts", None), "openInterest")

                import numpy as np

                price_ref = float(current_price or 0) if current_price else 0.0
                low_band = price_ref * 0.85 if price_ref > 0 else 0.0
                high_band = price_ref * 1.15 if price_ref > 0 else float("inf")
                recent_cutoff = datetime.datetime.now(
                    datetime.timezone.utc
                ) - datetime.timedelta(days=45)
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

                                dt = pd.to_datetime(
                                    last_trade, utc=True, errors="coerce"
                                )
                            recent = work[(dt.isna()) | (dt >= recent_cutoff)]
                            if not recent.empty and (
                                float(recent["openInterest"].sum()) > 0
                                or float(recent["volume"].sum()) > 0
                            ):
                                work = recent
                        except Exception:
                            pass

                    if price_ref > 0:
                        near = work[
                            (work["strike"] >= low_band) & (work["strike"] <= high_band)
                        ]
                        if not near.empty and (
                            float(near["openInterest"].sum()) > 0
                            or float(near["volume"].sum()) > 0
                        ):
                            work = near

                        if side == "call":
                            directional = work[work["strike"] >= price_ref]
                        else:
                            directional = work[work["strike"] <= price_ref]

                        if not near.empty and (
                            float(near["openInterest"].sum()) > 0
                            or float(near["volume"].sum()) > 0
                        ):
                            chosen = (
                                directional
                                if (
                                    not directional.empty
                                    and (
                                        float(directional["openInterest"].sum()) > 0
                                        or float(directional["volume"].sum()) > 0
                                    )
                                )
                                else work
                            )
                            chosen = chosen.assign(
                                _dist=(chosen["strike"] - price_ref).abs()
                            )
                            chosen = (
                                chosen.sort_values("_dist", ascending=True)
                                .head(strike_near_limit)
                                .drop(columns=["_dist"])
                            )
                            return chosen
                    return work

                calls_for_metrics = _prepare_filtered(chain.calls, "call")
                puts_for_metrics = _prepare_filtered(chain.puts, "put")

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
                            metric = (
                                work["openInterest"]
                                if float(work["openInterest"].sum()) > 0
                                else work["volume"]
                            )
                    if float(metric.sum()) <= 0:
                        return 0

                    idx = metric.idxmax()
                    return float(work.loc[idx]["strike"])

                oi_reliable = calls_oi >= oi_trust_min and puts_oi >= oi_trust_min
                max_call_oi_strike = _max_strike_by_metric(
                    calls_for_metrics, prefer_oi=oi_reliable
                )
                max_put_oi_strike = _max_strike_by_metric(
                    puts_for_metrics, prefer_oi=oi_reliable
                )
                strike_basis = "OI" if oi_reliable else "Volume"

                max_pain = 0
                call_strikes = (
                    calls_for_metrics["strike"].values
                    if calls_for_metrics is not None
                    and not calls_for_metrics.empty
                    and "strike" in calls_for_metrics.columns
                    else []
                )
                put_strikes = (
                    puts_for_metrics["strike"].values
                    if puts_for_metrics is not None
                    and not puts_for_metrics.empty
                    and "strike" in puts_for_metrics.columns
                    else []
                )
                strikes = np.unique(np.concatenate((call_strikes, put_strikes)))

                if len(strikes) > 0 and oi_reliable:
                    calls_oi_series = (
                        calls_for_metrics["openInterest"].fillna(0).clip(lower=0)
                        if calls_for_metrics is not None
                        and "openInterest" in calls_for_metrics.columns
                        else 0
                    )
                    puts_oi_series = (
                        puts_for_metrics["openInterest"].fillna(0).clip(lower=0)
                        if puts_for_metrics is not None
                        and "openInterest" in puts_for_metrics.columns
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

                atm_iv = None
                try:
                    if (
                        current_price
                        and not chain.calls.empty
                        and "impliedVolatility" in chain.calls.columns
                    ):
                        atm_call = chain.calls.iloc[
                            (chain.calls["strike"] - float(current_price))
                            .abs()
                            .argsort()[:1]
                        ]
                        iv_val = atm_call["impliedVolatility"].values[0]
                        if iv_val and iv_val > 0:
                            atm_iv = round(float(iv_val) * 100, 2)
                except Exception:
                    pass

                pcr_volume = (
                    round(puts_vol / calls_vol, 2)
                    if calls_vol > 0
                    else (None if puts_vol == 0 else "High")
                )
                pcr_oi = (
                    round(puts_oi / calls_oi, 2)
                    if calls_oi > 0
                    else (None if puts_oi == 0 else "High")
                )
                pcr_basis = "OI" if oi_reliable else "Volume"
                pcr = pcr_oi if pcr_basis == "OI" else pcr_volume
                oi_available = (calls_oi + puts_oi) > 0

                options_data = {
                    "date": nearest_date,
                    "calls_volume": calls_vol,
                    "calls_oi": calls_oi,
                    "puts_volume": puts_vol,
                    "puts_oi": puts_oi,
                    "oi_available": oi_available,
                    "max_pain": max_pain,
                    "max_call_oi_strike": max_call_oi_strike,
                    "max_put_oi_strike": max_put_oi_strike,
                    "strike_basis": strike_basis,
                    "oi_reliable": oi_reliable,
                    "atm_iv": atm_iv,
                    "pcr": pcr,
                    "pcr_basis": pcr_basis,
                    "pcr_oi": pcr_oi,
                    "pcr_volume": pcr_volume,
                }
        except Exception as exc:
            logger.warning("Options parsing error: %s", exc)

        news_data = []
        try:
            news = tc.news
            if news:
                for item in news[:5]:
                    content = item.get("content", {})
                    title = content.get("title", item.get("title", ""))
                    provider = content.get("provider", {}).get(
                        "displayName", item.get("publisher", "Yahoo Finance")
                    )
                    click_data = content.get("clickThroughUrl", {})
                    link = click_data.get("url", item.get("link", ""))
                    if title:
                        news_data.append(
                            {"title": title, "publisher": provider, "link": link}
                        )
        except Exception as exc:
            logger.warning("News parsing error: %s", exc)

        history_data = []
        try:
            hist = tc.history(period="6mo", interval="1d")
            if hist is not None and not hist.empty:
                for idx, row in hist.iterrows():
                    history_data.append(
                        {
                            "time": idx.strftime("%Y-%m-%d"),
                            "open": round(float(row["Open"]), 2),
                            "high": round(float(row["High"]), 2),
                            "low": round(float(row["Low"]), 2),
                            "close": round(float(row["Close"]), 2),
                            "volume": int(row.get("Volume", 0)),
                        }
                    )
        except Exception as exc:
            logger.warning("History data error: %s", exc)

        return {
            "status": "success",
            "data": {
                "financials": financials,
                "options": options_data,
                "news": news_data,
                "history": history_data,
            },
        }
    except Exception as exc:
        logger.exception("Insight info error: %s", exc)
        return {"status": "error", "message": str(exc)}
