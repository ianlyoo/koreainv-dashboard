# Company insight visualization contract

The existing application is the delivery surface. Its existing Chart.js runtime renders the data charts; TradingView remains the first item in the insight pane. This is a refinement of the live dashboard, not a separate analytical report.

| Section | Comparison | Visual and exact lookup | Fields and safeguards |
| --- | --- | --- | --- |
| Core metrics | Current value and meaningful comparator | Compact tables with metric, value and comparison/period | Different units remain separate. Already-percent values are not multiplied by 100; fiscal-quarter revenue is not labeled TTM. |
| Quarterly revenue | Five reported quarters | Chronological blue columns with a zero baseline, plus revenue/YoY table | `revenue.quarters` contains discrete fiscal quarters. Missing revenue is not zero, and the provider array is not mutated. |
| Analyst consensus | Composition of available recommendations | Horizontal stacked bar and labeled buy/hold/sell counts | Use the observed count total as the denominator. Unknown counts do not become zero. Total-count differences must not silently distort percentages. |
| Analyst targets | Low, mean and high relative to current price | Price range/dots with a USD axis; recent firm/date/rating/previous/current target table | Include the current price in the plotted domain. This is an interval comparison, not a zero-baseline magnitude bar. No stale upstream upside percentage is presented as a current calculation. |
| Option shares | Call versus put by volume, open interest and premium | Three 100% stacked bars with direct labels and exact shares | Shares already use percent units. An incomplete/invalid pair must not imply a known 100% composition. Call and put retain consistent colors across rows. |
| Relative option volume | Same-time cumulative volume against typical volume | Three bars with an explicit 100% reference and numeric labels/table | Only `optionVolumeVsAvg.windows[d3,d7,d30].available=true` supplies a value. Zero is valid; missing is not zero. |
| Option levels | Relevant price levels and net gamma exposure | Aligned table with USD values, expiry and data timestamps | Max Pain, walls and Gamma Flip are prices. GEX uses `gammaPer1Pct` in USD for a 1% underlying move; it must not share a price axis. Provisional/prior-day states remain visible. |
| Insider activity | Buy/sell transaction counts over the stated window | Two count bars, signed net value, full supplied recent-transaction table | Use reported window counts. Do not infer 90-day gross buy/sell dollar totals from a limited recent list. Transaction code/date and signed amounts remain explicit. |
| Related news | Exact headline lookup | Dated headline links | Titles and metadata are escaped; destination remains a validated SaveTicker news link. |

Color carries meaning without being the only distinction: blue for a single quantitative series; teal for call/buy, coral for put/sell, neutral for hold and reference marks. Legends, labels, signs and tables also identify each series. Dark and light themes use separately legible tones. Figures have no decorative card backgrounds or outlines.

All charts retain semantic table/text alternatives. Missing data and an unavailable chart runtime preserve the readable data. Chart instances are disposed on selection/loading/error and refreshed with theme changes. Reduced motion disables chart animation. Final QA covers the actual 21:9 pane, a narrower desktop pane, both themes, labels/units, zero/missing values and repeated selection/theme changes.


Validation completed: six native charts and twelve semantic tables rendered for the captured AVGO response. AVGO→AAPL→AVGO and theme changes retained exactly six detail instances. Keyboard tooltips, zero/missing data, full supplied insider rows, chart disposal and reduced motion passed focused tests. Chromium checks at 3439×1244 and 1366×900 showed no insight overflow or runtime page errors. Dark and light screenshots were inspected. The two detail columns flow independently to avoid blank gaps between unequal sections; charts remain unboxed. Local QA artifacts are under `output/playwright/insight-viz-*`.
