"""The Android daily-range path asks the proxy only for independent native lots."""
import json
import unittest
from pathlib import Path
from unittest.mock import patch
from app import toss_api_client as toss

class TaxNativeRangeTests(unittest.TestCase):
    def test_native_only_preserves_every_lot_and_never_fetches_per_execution_fx(self):
        orders = json.loads((Path(__file__).parent / 'fixtures/toss/orders-and-fx.json').read_text())['orders']['result']['orders']
        with patch.object(toss, '_fetch_closed_orders', return_value=(orders, True)), patch.object(toss, '_get_usd_exchange_rate', return_value=1300), patch.object(toss, '_get_historical_usd_mid_rate') as quote:
            one = toss.get_trade_history('fake','fake','9','2026-01-01','2026-12-31',tax_estimate=True,hydrate_tax_fx=False)
            two = toss.get_trade_history('fake','fake','9','2026-01-01','2026-12-31',tax_estimate=True,hydrate_tax_fx=False)
        quote.assert_not_called()
        self.assertEqual(one['tax_executions'], two['tax_executions'])
        self.assertEqual(4, len(one['tax_executions']))
        self.assertTrue(all(r.get('buy_amount_native', 0) > 0 for r in one['items'] if r['side'] == '매도'))
        self.assertEqual('client_kis_daily_range', one['tax_fx_basis'])
        self.assertFalse(one['tax_fx_complete'])

if __name__ == '__main__': unittest.main()
