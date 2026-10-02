# KoreaInv UX overhaul · 2026-10-02

The dashboard now follows the owner's existing Android direction: a quiet graphite/porcelain canvas, clear signed financial values, blue actions, Manrope numerals, and restrained motion. This builds on `design/android-insight/DESIGN.md`, `design-qa.md`, and the prior Android UX, glass, web read-only and light-palette branches. Approved Android login artwork, PIN composition and navigation motion are preserved.

Web separates the portfolio overview from market context, gives holdings a readable phone layout, and groups appearance, account connections and reset under settings. Negative totals retain their minus sign. Loading and failed refreshes explain what is happening without hiding the last successful snapshot. Empty data and unavailable chart libraries have explicit fallbacks. Search, calendar rendering and dialog/focus ownership are separate presentation modules; shared theme tokens replace duplicated palettes. Keyboard search, native holding buttons, nested dialog focus restoration, reduced motion and Korean labels are covered by the browser journey.

Android shares the same financial hierarchy and copy, limits large-screen reading width, makes cached refresh/retry states explicit, and presents insight charts before company detail. Request ownership is extracted without changing cancellation behavior. Desktop menus share clear Korean labels, a settings shortcut and actionable Windows startup errors. No server refactor was needed: GET contracts, broker access, authentication, storage/schema, update policy and release versions are unchanged. All application behavior remains read-only investing.

## Screenshot evidence

All captures use fictional accounts and deterministic fixture responses on isolated localhost. External browser requests are blocked; chart libraries are replayed from local files. Before images were captured at `510391e` before product edits. The initial before-capture script had a harmless early-root theme assignment warning, documented in [capture metadata](ux-overhaul-20261002/before/capture.json); those original screenshots were not recaptured.

| View | Before | After |
| --- | --- | --- |
| Desktop light · 1440px | [Before](ux-overhaul-20261002/before/desktop-light.png) | [After](ux-overhaul-20261002/after/desktop-light.png) |
| Desktop dark · 1440px | [Before](ux-overhaul-20261002/before/desktop-dark.png) | [After](ux-overhaul-20261002/after/desktop-dark.png) |
| Phone · 390px | [Before](ux-overhaul-20261002/before/mobile-light.png) | [After, full page](ux-overhaul-20261002/after/mobile-light-full.png) |
| Expanded desktop · 2560px | [Before](ux-overhaul-20261002/before/wide-dark.png) | [After](ux-overhaul-20261002/after/wide-dark.png) |
| Loading | [Before](ux-overhaul-20261002/before/mobile-loading.png) | [After](ux-overhaul-20261002/after/loading-390.png) |
| Error | [Before](ux-overhaul-20261002/before/desktop-error.png) | [After](ux-overhaul-20261002/after/error-1440.png) |
| Empty | [Before](ux-overhaul-20261002/before/mobile-empty.png) | [After](ux-overhaul-20261002/after/empty-390.png) |

Additional evidence: [phone settings](ux-overhaul-20261002/after/settings-mobile.png), [phone history](ux-overhaul-20261002/after/history-mobile.png), [phone insight](ux-overhaul-20261002/after/insight-mobile.png), [unlock](ux-overhaul-20261002/after/login-mobile.png), [setup](ux-overhaul-20261002/after/setup-mobile.png), [partial account failure](ux-overhaul-20261002/after/partial-1440.png), [stale refresh](ux-overhaul-20261002/after/stale-desktop.png), [missing chart runtime](ux-overhaul-20261002/after/no-chart-1440.png).

## Validation and limits

- Offline Python baseline: 187 tests / 41 subtests passed. After: 193 tests / 43 subtests passed. One existing Starlette/httpx deprecation warning remains.
- [Browser results](ux-overhaul-20261002/after/results.json): 53 checks, 27 screenshots, 22 axe WCAG A/AA scans; no runtime errors or automated accessibility findings. Widths: 360, 390, 820, 1120, 1440 and 2560px. This does not establish full WCAG conformance or replace assistive-technology testing.
- JavaScript syntax checks passed. Portable Kotlin/JUnit tests passed (5), including late cancellation, newest-result ownership and retry. All 12 changed Kotlin files parse cleanly. Launcher files pass Python parsing without importing or executing native integrations.
- Android SDK/emulator/adb were unavailable. The offline Gradle attempt lacked the Android plugin; the online attempt could not create the host's protected Android directory. No third attempt or host configuration change was made. No APK build, Compose type check, emulator capture or TalkBack validation is claimed. The portable compiler is Gradle's bundled Kotlin 2.0.21, not the application's configured compiler.
- macOS/Windows native menus, real provider widgets and live account workflows were not exercised. No host installer, live service restart, deployment, release or mandatory-update change occurred.

Reproduce browser evidence with `scripts/verify_ux.py --chromium <existing-chromium> --vendor <local-vendor-dir>` using Playwright, Chart.js 4.4.8 (`chart.js`), LightweightCharts 3.8.0 (`lightweight.js`) and axe-core 4.10.3 (`axe.js`). The script creates and closes its own fixture server. Portable Android checks use `scripts/check_android_presentation.py --gradle-lib <existing-gradle-lib> --out <temporary-output>`. The PM handoff records exact local commands and redacted secret-scan outcomes.

## Review follow-up · 2026-10-03

The independent Astra review of `3cdedd718ac20d3dd361dd35e62dc9c8603b399d` accepted the direction with no P1/P2 findings. Three P3 interactions were reproduced before correction: expanded-mode Close left the insight visible; allocation/market links navigated to hidden widgets; first ArrowUp selected the penultimate result.

Close now dismisses the insight in either layout, restores the original trigger's focus, and collapses the unused wide-screen column. Section links reveal hidden content before native fragment navigation and focus. First ArrowUp selects the last result and first ArrowDown selects the first; subsequent presses wrap normally.

[Before regression results](ux-overhaul-20261002/review-interactions/before.json) record four failing assertions across those three defects. [After results](ux-overhaul-20261002/review-interactions/after.json) record 18 passing focused checks, including desktop/mobile close and reopen, focus restoration, both section links, search initialization/wrap/selection and layout-switch dismissal. One affected search-race unit test, syntax checks for the three changed JavaScript files, and unchanged-boundary checks also passed. The accepted broad suites, native builds and independent review were not repeated; the limits above still apply.

Only the changed Close interaction has new captures: [before Close](ux-overhaul-20261002/review-interactions/before-expanded-close.png) and [after Close](ux-overhaul-20261002/review-interactions/after-expanded-close.png). Original screenshot proof is unchanged. Reproduce with `scripts/verify_review_interactions.py --chromium <existing-chromium> --vendor <local-vendor-dir> --phase after`; `--phase before` is intended for the reviewed source revision. Both phases use isolated synthetic fixtures and block external requests.
