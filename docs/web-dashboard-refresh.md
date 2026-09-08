# Desktop web dashboard refresh

Implemented on `codex/web-glass-readonly`, keeping the existing basic/expanded layout and the positions of summaries, holdings, allocation, market overview, calendar and asset insights.

## Appearance and interaction

- Dark is the initial appearance, preserving the original dashboard identity. System/light/dark choices are persisted and shared between dashboard/login. System mode follows OS changes; explicit choices remain stable. Startup resolves the theme before rendering to avoid a flash.
- The original deep-dark/gold surfaces, vivid financial colors and nine-color allocations are retained, with a warm ivory/gold light counterpart. Glass is concentrated on original-shaped controls, menus and dialogs: thin reflective rims, depth and pointer-following highlights. Dense data planes retain the original hierarchy.
- Chart.js, TradingView and LightweightCharts follow theme changes. Existing insight/filter state and chart viewport are retained where the provider supports it.
- Summary detail interactions remain, using a short fade/shift that renders correctly in WebKit. Hidden faces are explicitly hidden; WebKit no longer draws mirrored back-face text.
- TradingView iframe canvas schemes are isolated from the host theme, fixing opaque white backgrounds in dark mode. The widget's own theme is still set explicitly.
- Smaller desktop windows retain usable header controls, horizontal holdings scrolling and fitting summary values. Long allocation names have ellipsis and full-name tooltips. Reduced motion removes decorative transitions.

## Order feature removal

Removed order controls/forms, frontend state/requests, both scheduled-order API families, encrypted order store/worker, broker submission/cancellation helpers, central order settings/deployment example and Android submission client/models.

Historical trade/profit readers, balance and buying-power queries, account management/authentication and Toss read-only proxy support remain. Existing order data files were not deleted, and no broker order or cancellation was sent during implementation.

## Validation

- Full Python suite: 139 tests passed with 19 subtests. New regression coverage verifies retired endpoints return 404 even when authenticated and old central-server environment flags cannot start execution.
- Android compilation and 147 unit tests passed after removing the unused submission client.
- All JavaScript syntax checks passed. Theme tests cover storage failures, OS preferences, explicit choices and chart lifecycle behavior.
- Chromium browser journey passed 17 checks covering theme persistence, account filter/validation/creation UI, currency and cash detail, historical buy/sell data, asset charts, layout modes and login. No runtime page errors or order requests occurred.
- Chromium and WebKit visual checks cover both themes, summary details, account/history dialogs, insights and login/setup. 1366px viewport checks confirm no document overflow and no clipped summary amounts; holdings overflow remains explicitly scrollable. Reduced-motion and real OS-theme emulation checks passed.
- Screenshots/logs are local artifacts under `output/playwright/`; baseline snapshot and screenshots are retained there for comparison. Native Windows/macOS release packages have not been produced in this design pass.

## Local preview

```sh
python3.11 scripts/preview_web_dashboard.py --port 8770
```

Open `http://127.0.0.1:8770/`, `/login` or `/setup`. The preview server uses only fictional account/API data and does not import brokerage clients or execute actions. Account edits in this preview are simulated. Existing third-party TradingView widgets still retrieve public market data. The server binds to loopback only.


## 21:9 revision and SaveTicker status

The user's reference is the original dashboard at 3439×1244 on a 21:9 display. Expanded mode remains a three-region workspace: left summaries/holdings, middle allocation + market/calendar, right company insight. For a fresh preference, viewports at least 2000px wide and 21:9 default to expanded mode; saved layout choices always win. Both 3440×1440 and 3439×1244 were checked with 17 fictional positions and AVGO selected.

Liquid DOM was evaluated as a reference. Its WebGPU glass core can render supplied textures, but automatic live DOM capture requires Chrome's experimental HTML-in-Canvas feature. The production design therefore retains accessible DOM and uses scoped CSS reflections and backdrop filtering without requiring browser flags or WebGPU. `glass.js` throttles pointer work to animation frames and stops on hidden pages, reduced motion and touch/coarse pointers.

SaveTicker now supplies US company insights through its authenticated web API: price/ranges, financial metrics, quarterly revenue, analyst consensus, options, insider trades and related headlines. The production AVGO route was verified with all seven sections available. The dashboard shows each section's source/date and uses Yahoo with explicit labeling when SaveTicker is unavailable. Credentials remain server-side in ignored local configuration; sessions are held in memory. See [connection details](saveticker-connection-findings.md).

For the actual AVGO query snapshot alongside synthetic accounts, run the preview with `--insight-snapshot output/playwright/saveticker-live-AVGO.json`. The footer distinguishes the two sources.

References: [Liquid DOM core](https://github.com/AndrewPrifer/liquid-dom/blob/master/packages/core/README.md), [SaveTicker AVGO](https://saveticker.com/company/AVGO?entry=popular_ranking).


## Chart-first insight refinement

Company insight now starts with the chart, followed by identity/price and financial details. Metric backgrounds, outlines, gold top borders and rounded cards have been removed. The reading hierarchy uses larger tabular values, quiet labels, whitespace and thin section dividers. SaveTicker and Yahoo fallback both put their single chart mount first. The original dark/gold and warm light palettes remain; no data contract changed.

Verified in Chromium at 3439×1244 and 1366×900: chart is the first content element, metric backgrounds are transparent with zero borders, and the insight pane has no horizontal overflow. Existing rendering and chart-theme tests pass.


## Insight tables and data charts

Below the first price chart, core metrics use comparison tables. Six Chart.js visuals show quarterly revenue, analyst composition, target range/current price, option shares, relative cumulative option volume and insider transaction counts, each backed by labeled exact-value tables. Blue, teal, coral and neutral tones replace a gold-only chart palette. Tooltips support pointer and keyboard input; reduced motion and chart cleanup are verified. See [visualization contract](insight-visualization.md).


## Summary glass surfaces

The three top summary cards no longer have gold accent bars. They now use translucent cool-neutral surfaces, a soft silver rim, blur/saturation and restrained pointer-following highlights. Light mode uses a frosted white counterpart. The shared reflection handler includes summary cards and respects reduced motion, coarse pointers and page visibility. Visual checks confirmed both themes, detail toggling, pointer reflection and no document overflow at 3439×1244.
