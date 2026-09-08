# Android Liquid Glass refinement

Base: `a40a924` (`main`, app 1.9.0). Design approved on 2026-09-08, including follow-up layout changes. Release target: 1.9.1 (Android versionCode 35), with the existing recommended-update publishing workflow.

## Structure

`MainActivity` loads appearance preferences and applies the Compose theme. `ui/Navigation.kt` owns the four primary destinations, account session and repository lifetime. `KisRepository` supplies dashboard/trade data; screen state controls filtering and presentation. No broker, authentication, order or financial calculation code changes are part of this work.

The shared UI is now split into:

- `DashboardGlassHost`: continuous edge-to-edge canvas, safe insets and one shared backdrop.
- `DashboardScaffold`: records an opaque scrolling body once; floats the header above it and passes header height as scroll content padding.
- `LiquidGlass`: genuine Backdrop 1.0.6 vibrancy, blur and lens effects only for floating header controls and bottom navigation. Ordinary content and filters avoid runtime shader effects.
- `HeaderUi`: responsive headings, controls, unboxed list rows and floating tab bar.
- `DashboardIcons`: consistent lightweight vector navigation symbols.

## Design

The main financial values use neutral text, while profit/loss retains semantic colors. Portfolio/trade identity and amounts align in two columns, with stacked fallback for narrow widths, large text or long amounts. Asset and Settings sections use spacing and subtle separators rather than individual glass cards.

The header and system status bar share the canvas. Content scrolls underneath the header; a fading edge protects title legibility without a hard rectangular cutoff. Header controls use the body directly as their optical source. A pointer blocker behind the controls prevents taps from reaching hidden rows. The footer retains its floating capsule and animated selection.

The body recording includes an opaque background. Recording only transparent content previously allowed the original sharp text to show through blurred navigation. Header and footer are excluded from the source to avoid self-sampling and repeated recordings.

On Android below API 31, floating surfaces stay opaque for legibility. API 31–32 supports blur; refraction is guarded by the library. Display cutouts and IME insets are handled explicitly without changing the minimum/target SDK or upgrading dependencies.

## Validation and artifacts

- Debug APK builds successfully.
- 132 existing Android unit tests pass, including monetary presentation, theme and contrast checks.
- Android lint: no errors, 115 warnings and two hints.
- API 35 emulator review uses the debug-only `UiPreviewActivity` with synthetic accounts/trades and no repository/network client.
- All four screens were inspected in light/dark modes and at 360dp / 130% font size, including scrolling to the last items. Monetary values remain untruncated through responsive stacking.
- Interaction smoke checks passed for header touch blocking, currency switching, upper Settings entry, theme radio selection, account-management entry, trade filtering and cash expansion. The account PIN field remains above the keyboard, and keyboard dismissal restores the layout.
- Screenshots, gesture recording, interaction smoke log, frame statistics and the local design gallery are under ignored `build/android-review/`.
- Source-based review covered layout/inset ownership, overlay touch interception and backdrop cycles.

Physical-device frame timing, API 26–30 execution and a credential-backed production session remain untested. Debug emulator timings are diagnostic and should not be presented as a guaranteed improvement on the user's device.

- Settings ends with the requested `© 2026 Youngin` copyright notice.

## References

- [AndroidLiquidGlass 1.0.6](https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.6), matching the existing dependency. Existing Apache 2.0 notice remains packaged.
- [Apple Materials guidance](https://developer.apple.com/design/human-interface-guidelines/materials): Liquid Glass belongs in the floating navigation/control layer.
- [Android edge-to-edge](https://developer.android.com/develop/ui/compose/system/setup-e2e).
- [Compose performance](https://developer.android.com/develop/ui/compose/performance/bestpractices): cache derived work and defer animated state reads to placement/drawing.

## Design feedback refinements

- Holding/trade profit text aligns to the baseline of the last metadata row (quantity/market or side/quantity/date). Baseline alignment avoids an extra intrinsic measurement pass; narrow/large-text stacking remains supported.
- Total-assets component owns both metric separators, with 20dp between each separator and the metric row. Removed the previously stacked outer padding/separator gap.
- Holdings heading and account/count metadata share a baseline, with the metadata on the right. Typography and colors are retained. The filter row has 28dp above it and reduced space to the first holding.
- All body dropdown triggers use unboxed inline text and arrows, including the account-form selector. Header and bottom navigation glass is retained. Touch targets stay at least 48dp; opened choice menus retain a readable surface.
- Login already used the common palette. Only the large surrounding panel was removed; the plain numeric keypad and authentication behavior are unchanged. Light/dark login captures are included in the review gallery.
