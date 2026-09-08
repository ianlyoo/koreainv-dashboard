# Android Liquid Glass UI

## Goal and visual direction

Use restrained, translucent glass over a quiet graphite or porcelain canvas, with crisp financial values and a single cool-blue interaction accent. Glass should reveal depth at navigation and controls without reducing the readability of balances, account names or forms.

## Screen and interaction plan

- Keep portfolio, assets and trade history as the primary workspaces; add Settings as the fourth bottom destination.
- Replace the upper-right overflow menu with a Settings button. Place account management, update checking and logout on the Settings screen.
- Offer System (default), Light and Dark appearance. Persist the selection independently of account credentials and retain it on logout.
- Apply common glass surfaces, borders, typography and theme tokens to login/setup, portfolio/assets/trades, detail and account-management screens.
- Use a moving bottom-tab selection surface and brief press feedback. Backdrop distortion affects the background only; foreground labels and amounts stay sharp.
- Keep the PIN keypad as plain numbers, without individual circular backgrounds, borders or shadows (user feedback).
- Preserve readable wrapping, minimum tap targets, existing tab-state restoration, form validation and update behavior.

## Rendering and compatibility

- Integrate `io.github.kyant0:backdrop:1.0.6`, the Android-specific release of [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.6).
- Record a background-only layer for ordinary glass surfaces and a separate content layer for the floating bottom bar. Keep the bar outside its own recording to prevent recursive rendering.
- Dropdowns sample the content layer as well; their Material container remains transparent with matching rounded corners. On pre-31 Android, floating surfaces are opaque to avoid readable background text overlapping labels.
- Enable blur/refraction where supported; older Android devices retain a contrasting translucent surface. Keep minimum Android API 26.
- Update Kotlin/Compose and the Android build toolchain to versions compatible with Backdrop. Do not alter account/token or profit-calculation behavior.

## Ownership

- Parent: common glass components, existing screen adaptation, debug preview, integration and visual validation.
- Settings worker: settings screen/navigation, appearance persistence and root wiring.
- Theme worker: immutable light/dark palettes and Material/system-bar theme behavior.
- Tooling worker: compatible pinned dependencies, Gradle wrapper and SDK configuration.

## Completion checks

- Debug/release compilation and relevant Android unit tests.
- Actual Compose screen inspection with synthetic data in light/dark modes, including Settings and narrow/enlarged-text layouts.
- Settings navigation/back, appearance selection and persistence, system-mode behavior, account-management/update/logout routes.
- API 26 fallback smoke check where an isolated test device can be prepared.
- Record remaining device/performance limitations explicitly. Commit/release only when requested separately.

## Implemented and validated — 2026-09-08

- Backdrop 1.0.6, Shapes 1.2.0, Compose BOM 2026.02.00 (Compose 1.10.3 / Material 3 1.4.0), Kotlin/Compose compiler 2.3.10, AGP 8.13.2, Gradle 8.13, JDK 17. Compile SDK 36; target SDK 34 and minimum SDK 26 retained. Release workflow installs the required SDK explicitly.
- Common glass surfaces cover primary screens, details, login/setup, account forms, filters and bottom navigation. The PIN keypad uses plain text buttons. Settings contains the three appearance modes and the former overflow actions.
- 132 Android unit tests passed, including theme-resolution and palette contrast checks. Debug and unsigned release APK builds passed. Lint: no errors, 116 warnings and two hints (including existing/deprecation findings).
- API 35: all nine screens inspected in light mode; portfolio/settings/login and enlarged text inspected in dark mode. 360dp / font 130% checks covered the four-tab bar, settings scrolling and PIN keypad.
- UI controls verified with synthetic data: upper-right Settings entry, theme radio selection, persistence after process restart, live OS light/dark changes in SYSTEM mode, settings action callbacks and filter selection. Update dialogs used synthetic callbacks in the preview; production routing/update guards were independently reviewed.
- API 30: isolated fallback smoke in light/dark/system modes passed without crashes or linkage errors. Floating-bar opacity was increased after visual inspection, and the bottom content remained reachable. See `build/ui-review-api30/SMOKE-REPORT.md` for exact APK provenance and the final visual-only deltas.
- Release DEX inspection confirmed synthetic preview classes are absent. Backdrop license/notice is packaged in assets. No broker, token, order or profit-calculation source was changed.
- Screenshots and comparison page are in `build/ui-review/`. Both isolated emulator/ADB environments are stopped after review; user's existing emulator and ADB server remain untouched.

Physical-device GPU performance, a full production credential-backed navigation session, and API 26 specifically were not exercised. API 30 exercises the pre-31 rendering fallback.
