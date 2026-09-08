# SaveTicker integration — 2026-09-09

## Verified connection

The production `/api/asset-insight` route successfully authenticated and returned all seven SaveTicker sections for AVGO (3.3 seconds) and AAPL (3.5 seconds): header, key metrics, quarterly revenue, analyst consensus, insider trades, options and related headlines.

An ordinary HTTP client with the declared `KoreaInvDashboard/1.9.4 (personal portfolio dashboard)` User-Agent reaches the current API. The earlier Python default User-Agent received Cloudflare 1010; that did not establish that server integration was unavailable. No browser impersonation, challenge bypass, browser-cookie extraction or administrator endpoint is required.

The current website's client uses `POST https://saveticker.com/api/auth/login` with email/password. A successful response contains `user_info` and an `access_token` HTTP cookie. The connector keeps that cookie in process memory and reads credentials only from server configuration. It does not expose either to the dashboard response, JavaScript, logs or source control.

## Data and interpretation

- Company requests use `/api/stocks/api/v1/tickers/{symbol}/{header,key-metrics,revenue-trend,analyst,sec-insider,options}`.
- Related headlines use `/api/news/company?page=1&page_size=3&ticker={symbol}&sort=created_at_desc`. Only headline, publisher, publication time and `/news/{id}` links are retained; article bodies and account-specific metadata are discarded before caching.
- SaveTicker is the primary source for US stock insights. The old Yahoo path remains for unsupported markets or complete SaveTicker failure, with explicit source labeling. Partial SaveTicker responses show missing sections without inventing values.
- Percentage fields already use percent units. The API field named `revenueTtm` is displayed by the source website as the reported fiscal-quarter revenue; the dashboard uses that period label rather than calling it trailing annual revenue.
- Net GEX uses `gammaPer1Pct`, the USD hedge amount for a 1% underlying-price move. It does not use the differently scaled `netGammaExposure` field.
- Option volume covers all expiries. Snapshot dates, nearest expiry, prior-day flags and provisional batch state are preserved. Analyst targets and financial/short-interest data retain their own dates.
- The connector caches healthy reads for five minutes, bounds the cache, serializes session access and retries authentication once on 401. Access failures cause a 60-second cooldown; individual endpoint 5xx/network failures leave healthy sections available. Error snapshots expire after 60 seconds in both connector and route caches. The UI describes cached snapshots rather than claiming continuous live prices.

## Configuration

Connect from dashboard **Settings → SaveTicker**. Credentials remain in the unlocked dashboard session by default; optional persistence uses macOS Keychain or Windows Credential Manager. Disconnect removes the stored connection. Android connects independently through **Settings → Connection → SaveTicker**, with optional Android Keystore encryption after app PIN unlock and a configured device lock.

Legacy `.env` credentials are no longer loaded into application configuration. The explicit migration helper writes and verifies the protected OS item before removing only the two legacy bindings; it preserves other settings and does not create a plaintext backup. The authorized local migration was verified on 2026-09-09. No actual account identifiers or credentials belong in `.env.example`, fixtures, logs, or release packages.

This uses the current site's authenticated web API. No public versioning or third-party API stability guarantee was found; failures retain an explicit Yahoo fallback. The old `api.saveticker.com` news API returns a legacy-service notice and is not used.

## Preview and validation

The normal preview keeps synthetic account/portfolio data. An optional `--insight-snapshot output/playwright/saveticker-live-AVGO.json` loads the captured successful response for captured symbols only and labels it as a SaveTicker query snapshot. That preview never imports brokerage clients, credentials or live providers.

Mocked regression tests cover authentication, refresh, cooldown, cache isolation/bounds/singleflight, malformed/partial responses, missing-versus-zero values, news filtering, provenance and safe rendering. A separate live route check verified the real account and seven sections. Local artifacts are ignored under `output/playwright/`.

Final validation: 32 targeted tests and 19 subtests passed. Chromium checks at3439×1244 and1366×900 confirm the insight pane has no horizontal overflow; wide metrics/details use four/two columns, narrow details use one. Dark and light rendering, chart retention, safe source links and the credential scan passed.
