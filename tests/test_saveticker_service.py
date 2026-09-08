from __future__ import annotations

import asyncio
import copy
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock, patch

import requests

from app.routes import insight
from app.services.saveticker_service import HEADERS, SECTIONS, SaveTickerService, empty_snapshot

PAYLOADS = {
    "header": {"price": 0, "marketCap": 123, "week52Range": {"low": 0, "high": 100}},
    "key_metrics": {"roe": {"value": 25}, "shortInterestPct": {"value": 0}, "per": {"value": 40}},
    "revenue": {"quarters": []},
    "analyst": {"target": {"mean": 0, "high": 100, "low": None}, "recent": []},
    "insider": {"buyCount": 0, "sellCount": 0, "recent": []},
    "options": {"optionable": False, "volume": 0},
    "news": {"news_list": [{"id": "42", "title": "Headline", "source": "reuters", "created_at": "2026-09-04T11:00:00Z", "content": "private body", "bookmarked": True, "user": {"email": "private"}}]},
}


def response(status=200, data=None):
    return Mock(status_code=status, json=Mock(return_value=data))


class SaveTickerTests(unittest.TestCase):
    def setUp(self):
        self.session = Mock(headers={}, cookies=requests.cookies.RequestsCookieJar())
        def login(*args, **kwargs):
            self.session.cookies.set("access_token", "test-session")
            return response(data={"user_info": {"id": 1}})
        self.session.post.side_effect = login
        self.session.get.side_effect = lambda url, **kw: response(data=copy.deepcopy(PAYLOADS[next(k for k,v in SECTIONS.items() if url.endswith('/'+v) or (k == 'news' and '/api/news/company?' in url))]))
        self.service = SaveTickerService('test@example.invalid', 'test-password', session=self.session)

    def test_success_memory_session_cache_and_singleflight(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(self.service.fetch, ['AVGO'] * 4))
        self.assertTrue(all(r['status'] == 'available' for r in results))
        self.assertEqual(self.session.post.call_count, 1)
        self.assertEqual(self.session.get.call_count, 7)
        self.assertEqual(self.session.headers, HEADERS)
        for call in self.session.get.call_args_list + self.session.post.call_args_list:
            self.assertFalse(call.kwargs['allow_redirects'])
            self.assertEqual(call.kwargs['timeout'], (3.05, 8))
        results[0]['sections']['header']['price'] = 10
        self.assertEqual(self.service.fetch('AVGO')['sections']['header']['price'], 0)

    def test_news_only_headlines_are_cached_with_safe_links(self):
        result = self.service.fetch('AVGO')
        items = result['sections']['news']['items']
        self.assertEqual(items, [{'title': 'Headline', 'publisher': 'reuters', 'link': 'https://saveticker.com/news/42', 'published_at': '2026-09-04T11:00:00Z'}])
        self.assertNotIn('private', str(result))
        self.assertNotIn('bookmarked', str(self.service._cache))
        self.assertIn('/api/news/company?page=1&page_size=3&ticker=AVGO&sort=created_at_desc', self.session.get.call_args.args[0])

    def test_news_malformed_payload_is_partial_and_empty_list_available(self):
        from app.services.saveticker_service import _normalize_news
        self.assertIsNone(_normalize_news({'news_list': ['bad']}))
        self.assertEqual(_normalize_news({'news_list': []}), {'items': []})
        self.assertEqual(_normalize_news({'news_list': [{'id': 'a/b?x', 'title': 'safe'}]})['items'][0]['link'], 'https://saveticker.com/news/a%2Fb%3Fx')

    def test_login_failure_backoff_no_credentials_in_result(self):
        self.session.post.side_effect = lambda *a, **k: response(403, {'secret': 'test-password'})
        result = self.service.fetch('AVGO')
        self.assertEqual(result['status'], 'unavailable')
        self.service.fetch('AAPL')
        self.assertEqual(self.session.post.call_count, 1)
        self.session.get.assert_not_called()
        self.assertNotIn('test-password', str(result))
        self.assertFalse(self.session.cookies)

    def test_login_requires_verified_auth_cookie(self):
        def login_without_auth_cookie(*args, **kwargs):
            self.session.cookies.set('unrelated', 'cookie')
            return response(data={'user_info': {'id': 1}})
        self.session.post.side_effect = login_without_auth_cookie
        self.assertEqual(self.service.fetch('AVGO')['status'], 'unavailable')
        self.session.get.assert_not_called()

    def test_401_refresh_only_once_then_backoff(self):
        self.session.get.side_effect = [response(401), response(401)]
        self.assertEqual(self.service.fetch('AVGO')['status'], 'unavailable')
        self.assertEqual(self.session.post.call_count, 2)
        self.assertEqual(self.session.get.call_count, 2)
        self.service.fetch('AAPL')
        self.assertEqual(self.session.post.call_count, 2)

    def test_401_refresh_recovers(self):
        self.session.get.side_effect = [response(401)] + [response(data=x) for x in PAYLOADS.values()]
        self.assertEqual(self.service.fetch('AVGO')['status'], 'available')
        self.assertEqual(self.session.post.call_count, 2)

    def test_partial_invalid_and_missing_payloads(self):
        self.session.get.side_effect = [response(data=PAYLOADS['header']), response(data=[]), response(404), response(data={'recent': ['bad']}), response(data={}), response(data={'volume': float('nan')}), response(404)]
        result = self.service.fetch('AVGO')
        self.assertEqual(result['status'], 'partial')
        self.assertEqual(result['section_status']['key_metrics'], 'error')
        self.assertEqual(result['section_status']['revenue'], 'unavailable')
        self.assertIsNone(result['sections']['options'])

    def test_network_timeout_cached_briefly_and_bounded_cache_expiry(self):
        self.session.get.side_effect = requests.Timeout('private error')
        self.assertEqual(self.service.fetch('AVGO')['status'], 'unavailable')
        self.service.fetch('AVGO')
        self.assertEqual(self.session.get.call_count, 7)
        self.assertTrue(self.service._authenticated)
        self.service._retry_at = 0
        self.service._cache.clear()
        self.session.get.side_effect = lambda *a, **k: response(404)
        self.service.MAX_CACHE = 2
        for ticker in ('AAPL', 'MSFT', 'GOOG'):
            self.service.fetch(ticker)
        self.assertEqual(len(self.service._cache), 2)
        self.service._cache['GOOG'] = (time.monotonic() - 301, empty_snapshot('available'))
        self.assertEqual(self.service.fetch('GOOG')['status'], 'unavailable')

    def test_isolated_revenue_failure_preserves_session_and_later_sections_retries_after_60(self):
        healthy_get = self.session.get.side_effect
        self.session.get.side_effect = lambda url, **kwargs: response(500) if url.endswith('/revenue-trend') else healthy_get(url, **kwargs)
        result = self.service.fetch('AVGO')
        self.assertEqual(result['status'], 'partial')
        self.assertEqual(result['section_status']['revenue'], 'error')
        for section in ('analyst', 'options', 'news'):
            self.assertEqual(result['section_status'][section], 'available')
        self.assertTrue(self.session.cookies.get('access_token'))
        self.assertTrue(self.service._authenticated)
        self.assertEqual(self.session.post.call_count, 1)
        self.service.fetch('AVGO')
        self.assertEqual(self.session.get.call_count, 7)
        self.service._cache['AVGO'] = (time.monotonic() - 61, result)
        self.session.get.side_effect = healthy_get
        self.assertEqual(self.service.fetch('AVGO')['status'], 'available')
        self.assertEqual(self.session.get.call_count, 14)
        self.assertEqual(self.session.post.call_count, 1)

    def test_disabled_and_unsupported_make_no_requests(self):
        self.assertEqual(SaveTickerService().fetch('AVGO')['status'], 'disabled')
        for ticker, market in [('005930', 'KOR'), ('../admin', 'USA'), ('AAPL?x=1', 'USA')]:
            self.assertEqual(self.service.fetch(ticker, market)['status'], 'unsupported')
        self.session.post.assert_not_called()


class InsightSaveTickerTests(unittest.TestCase):
    def setUp(self):
        insight._insight_cache.clear()

    def test_partial_skips_yahoo_preserves_zero_and_missing_semantics(self):
        snapshot = empty_snapshot('partial')
        snapshot['sections'].update(copy.deepcopy(PAYLOADS))
        snapshot['sections']['news'] = {'items': [{'title': 'Headline', 'publisher': 'reuters', 'link': 'https://saveticker.com/news/42', 'published_at': None}]}
        with patch.object(insight.saveticker_service, 'fetch', return_value=snapshot), patch.object(insight, '_resolve_yf_ticker') as yahoo:
            result = asyncio.run(insight.get_asset_insight('AVGO'))
            self.assertEqual(asyncio.run(insight.get_asset_insight('AVGO')), result)
        yahoo.assert_not_called()
        data = result['data']
        self.assertEqual(data['source'], 'saveticker')
        self.assertIsNone(data['options'])
        self.assertEqual(data['news'][0]['title'], 'Headline')
        fin = data['financials']
        self.assertEqual(fin['shortName'], 'AVGO')
        self.assertEqual(fin['currentPrice'], 0)
        self.assertEqual(fin['returnOnEquity'], .25)
        self.assertEqual(fin['shortPercentOfFloat'], 0)
        self.assertEqual(fin['targetMeanPrice'], 0)
        self.assertEqual(fin['targetLowPrice'], 'N/A')
        self.assertEqual(fin['forwardPE'], 'N/A')

    def test_unavailable_and_non_us_fallback_explicit_source(self):
        for market, status in [('USA', 'unavailable'), ('KOR', 'unsupported')]:
            with patch.object(insight.saveticker_service, 'fetch', return_value=empty_snapshot(status)), patch.object(insight, '_resolve_yf_ticker', return_value=('X', {'currentPrice': 10})), patch.object(insight, '_fetch_options_data', return_value=None), patch.object(insight, '_fetch_news_data', return_value=[]), patch.object(insight, '_fetch_history_data', return_value=[]):
                result = asyncio.run(insight.get_asset_insight('X', market))
            self.assertEqual(result['data']['source'], 'yahoo')
            self.assertEqual(result['data']['saveticker']['status'], status)

    def test_route_error_snapshot_cache_expires_after_60_seconds(self):
        snapshot = empty_snapshot('partial')
        snapshot['sections']['header'] = PAYLOADS['header']
        snapshot['section_status']['header'] = 'available'
        snapshot['section_status']['revenue'] = 'error'
        with patch.object(insight.saveticker_service, 'fetch', return_value=snapshot) as fetch:
            asyncio.run(insight.get_asset_insight('AVGO'))
            asyncio.run(insight.get_asset_insight('AVGO'))
            self.assertEqual(fetch.call_count, 1)
            insight._insight_cache['USA:AVGO']['ts'] = time.time() - 61
            asyncio.run(insight.get_asset_insight('AVGO'))
            self.assertEqual(fetch.call_count, 2)

    def test_both_providers_fail_with_safe_error_and_provider_status(self):
        with patch.object(insight.saveticker_service, 'fetch', return_value=empty_snapshot('unavailable')), patch.object(insight, '_resolve_yf_ticker', side_effect=RuntimeError('private error')):
            result = asyncio.run(insight.get_asset_insight('AVGO'))
        self.assertEqual(result['status'], 'error')
        self.assertEqual(result['data']['saveticker']['status'], 'unavailable')
        self.assertNotIn('private error', str(result))
