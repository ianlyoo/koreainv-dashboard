# Toss realized profit

Android direct calls and the Python/central proxy client estimate realized profit from native-currency order executions. They use the same FX-excluded convention as the KIS trade-row path:

```
allocated native cost = moving-average native purchase cost × sold quantity
native net proceeds = execution filledAmount − sell commission − sell tax
KRW realized profit = (native net proceeds − allocated native cost) × sale reference midRate
```

Purchase commissions and taxes are capitalized into native cost. Actual filled amounts are preferred; quantity × average filled price is used when the amount is absent. A missing or malformed commission/tax is not assumed to be confirmed zero. Unsupported currencies, missing basis, incomplete pagination, missing charges, and unavailable historical FX produce a visible unpriced sale with a reason.

KIS supplies native realized profit and a row exchange rate (`frst_bltn_exrt` preferred). Toss supplies no equivalent broker realized-profit or cost-basis ledger. Its historical reference rate also differs from KIS's posted rate. **All reconstructed Toss sale values remain estimated**, including values with complete API pagination.

## History and FX

Fetch CLOSED orders with an explicit `from=2000-01-01`, through the selected end date, following cursors up to 100 pages. An omitted `from` returned only recent history in the live check, despite the official specification describing full history. The explicit bound recovered earlier purchases. A missing/repeated cursor, malformed page, or page-limit exhaustion makes coverage incomplete. The earliest returned execution is exposed; complete pagination does not prove that transfers, corporate actions, or all previous cost basis are represented.

For each selected USD sale, request `/api/v1/exchange-rate` with `dateTime=execution.filledAt`, `baseCurrency=USD`, and `quoteCurrency=KRW`. Accept only a finite positive `midRate` with an ordered response window satisfying `validFrom - 10 minutes <= filledAt <= validUntil + 10 minutes`. Toss can return a five-minute quote window starting seconds after the requested sale; the identical ten-minute tolerance in both clients accepts that requested quote instead of treating window skew as missing data. Require offset-aware timestamps. A missing quote or a window beyond this tolerance remains unavailable. Do not use the spread-adjusted `rate` field or today's rate for realized P/L. Do not substitute order creation time for a missing sale timestamp.

Historical FX caches are scoped to credential identity and exact sale timestamp, bounded to 1,024 entries. Successful quotes expire after 24 hours; misses expire after 60 seconds. Misses are serialized/throttled, and HTTP 429 has at most two retries with backoff. The current quote remains available for ordinary display conversion; it never enters the historical realized-profit formula.

## Returned metadata and compatibility

The Python result includes `profit_fx_basis=sale_historical_mid_rate`, `profit_history_complete`, `profit_history_start_date`, and a readable rate source. Trade rows include the applied `profit_exchange_rate`, `profit_rate_source`, `profit_estimate_reason`, and history fields. Aggregated web results carry account history notes and keep every selected sale, including unpriced rows. Android shows the source in the ledger and the numeric rate and history coverage in trade detail.

Clients receiving an older proxy response without the historical-FX contract retain its rows but suppress that account's realized-profit summary when overseas sales are present, with a central-update reason. The proxy trade-history timeout is 300 seconds to allow initial hydration of annual FX history. Other request timeouts are unchanged. Large uncached date ranges remain slower than cached refreshes.

The official API does not expose Toss's displayed FX-included realized P/L or actual buy/sell FX rates. There is no fabricated displayed-profit fallback or calculated FX component. The live report outside this repository records the available estimates and rejected rates, with masked account suffixes only. No extra MCP account is added to dashboard configuration.

## Validation and shipping

Response-shaped fixtures cover fractional moving-average basis, fees/taxes, historical conversion independent of today's rate, currency/window validation, cache scope/expiry/bounds, nullable charges, insufficient basis, incomplete history, duplicate orders, and legacy proxy compatibility. Android tests run in an isolated Mac copy with JDK 21, Android SDK 36, and Gradle 8.13.

Ship both an Android release and a central redeploy. The separate MCP service does not need a change for this dashboard fix.

Sources: [Toss official OpenAPI](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json), [official KIS period-profit example](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/inquire_period_profit/chk_inquire_period_profit.py).
