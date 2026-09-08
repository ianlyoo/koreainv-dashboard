# Android onboarding and login QA

final result: passed

## Visual truth and implementation

- User selected displayed login option 3, then explicitly removed the two supporting slogans and requested a dark-mode counterpart. No further visual selection is pending.
- Revised source: `build/onboarding-review/login-reference.png` (853 × 1844 pixels), generated using the built-in Image Gen tool.
- Native implementation: `build/onboarding-review/unlock-comparison.png` (780 × 1831 pixels, density 2). App content bounds are `[0,95][780,1783]`, or 390 × 844dp after excluding system UI.
- Comparison state: light theme, two PIN digits entered, neither busy nor failed.
- Source is displayed at 390px width without distortion (the generated source has sub-pixel aspect rounding). Native capture is displayed at 390px width and cropped by 47.5dp at the top and 24dp at the bottom solely to exclude Android system UI.
- Combined full-view and focused input-region comparison: `build/onboarding-review/compare.html`, inspected together in the in-app browser at `http://127.0.0.1:8766/compare.html`.
- Dark implementation: `build/onboarding-review/unlock-dark.png`; same composition with a matching static dark artwork and native theme colors.

## Findings

No actionable P0/P1/P2 differences remain. The glass horizon, typography hierarchy, horizontal heading/PIN grouping, plain numeric grid and single bottom-right backspace match the selected direction. Removed slogans and the old confirmation button are absent. Native controls remain editable/accessibility-aware rather than baked into artwork.

P3: Native font metrics and the standard Material backspace icon differ slightly from the generated concept. Artwork covers Android's system bars and therefore has a slightly different cover crop from the chrome-free reference. These preserve the design hierarchy and do not impair use.

## Comparison history

The first combined comparison used the final native implementation at matching 390 × 844dp content dimensions and the revised approved reference. It found no actionable P0/P1/P2 issue. No visual fixes were made after that comparison.

User follow-up: move only the unlock heading and PIN indicators slightly inward. The row inset now increases smoothly from 36dp on narrow screens to 56dp on a 411dp screen (51dp at 390dp). Updated light/dark captures and the comparison capture were regenerated; the heading's 56dp left inset was checked from actual accessibility bounds. Brand, artwork, keypad and vertical placement remain unchanged. The debug build passed. This intentional adjustment supersedes the original reference's horizontal gap.

## Functional and responsive validation

- Android debug build and 139 unit tests passed, including seven PIN-state transition tests. Android lint reports no errors (120 warnings, two hints).
- On API 35, fourth-digit entry submits once; repeated taps during an active attempt do not submit again.
- Both delayed and same-frame rejection allow the identical incorrect PIN to be retried after clearing input. Backspace removes one digit; successful entry navigates without confirmation.
- PIN input is transient (`remember`, not saveable), and neither accessibility semantics nor preview logs contain its value.
- Account-management keyboard focus/dismissal and initial-setup first-invalid-field relocation were exercised. Focused fields remain visible above IME and clear of the header.
- Login, account forms and asset distribution were inspected at 360dp width and 130% font scale. Required controls and the final Save action remain reachable.
- Asset distribution gives names the space remaining after measuring the actual percentage. Normal names and a long name paired with `100.00%` were verified without clipping.
- Account management, initial setup, holding detail and trade detail were inspected in light and dark themes after removing their old section panels.
- Logs and screenshots: `build/onboarding-review/flow-results.json`, `verify-flows.log`, `final-captures.log`, and the images linked from the local gallery. `unlock-flow.mp4` records an actual synthetic failure, correction and successful automatic login.

## Limits

Authentication UI tests use the debug-only synthetic callback. No live brokerage account or real saved-account credentials were used. Existing credential verification remains in SettingsManager; this change alters input submission and presentation. Physical-device performance was not measured. User approved the final design and authorized publishing v1.9.2 as a mandatory update on 2026-09-08.

## Artwork

Production backgrounds are `android-app/app/src/main/res/drawable-nodpi/login_horizon_light.png` and `login_horizon_dark.png`. Prompts and source provenance are in `build/onboarding-review/login-concepts/selected-design.md`. The backspace vector comes from Google Material Icons; its Apache 2.0 notice/license is packaged.

## Light palette refinement — 2026-09-08

- Replaced muted light chart hues with clearer blue, cyan, emerald, orange, coral and violet. Chart marks are independent of financial text colors. The light action/information accent now uses a more saturated blue.
- Dark palette is byte-for-byte unchanged. Layout and financial value semantics are unaffected.
- Debug build and both existing `DashboardColorsTest` checks passed. All six chart colors exceed 3:1 against the actual light canvas (minimum 3.386:1).
- Inspected actual API 35 synthetic asset-distribution captures before and after, plus the portfolio screen. Comparison: `build/onboarding-review/index.html#palette`; captures and contrast evidence: `build/onboarding-review/palette/`.
- This follow-up is on the development branch and is not included in the published v1.9.2 release.

### Financial red and green follow-up

- Light financial green is now `#00784F` and red is `#C32C43`, shared by portfolio P/L, trade summaries, buy/sell labels and detail views. Dark values are unchanged.
- Debug build and both existing palette contrast tests passed. Green/red contrast is respectively 5.156:1/5.208:1 on the canvas and 4.558:1/4.604:1 on the darkest tested light surface.
- Synthetic before/after captures for portfolio, trades, holding detail and trade detail are in `build/onboarding-review/palette/*-financial-*.png`; the gallery's `#financial` section compares each screen.

## Tactile motion implementation — 2026-09-08

- Added immediate press compression and spring restoration without delaying click dispatch; quick same-frame taps remain visible, canceled presses restore safely. Input areas remain outside visual scale, and header ripple stays inside its circular clip.
- Bottom selection now stretches and settles continuously, with coordinated icon/color feedback. Currency selection uses a moving measured background. Refresh controls keep their glass container while crossfading the internal loading state.
- Real and synthetic NavHosts share short tab/detail transitions. Outgoing scenes cannot receive pointer or accessibility actions. Unlock/setup/splash boundaries transition immediately.
- Each screen owns a fixed body recording; the persistent bottom glass reads only the active source through an attachment-checked proxy. This fixes the detached-coordinate crash reproduced during rapid navigation, without nested body recordings.
- Final debug build and 142 unit tests passed. All three Android instrumentation tests passed, including 36 interrupted native NavHost selections. Android lint: zero errors, 120 warnings and three hints.
- A separate API 35 synthetic stress run passed 120 rapid tab selections across light/dark modes. The full native journey passed 18 checks: cancellation, retargeting, holding/trade detail and back, tab scroll restoration, system animations disabled, 360dp/130% text controls, logout and absence of runtime crashes.
- Final evidence: `build/motion-review/results.json`, `stress-results.json`, `final-device-tests.log`, `regression-build.log`, `runtime.log`; actual light/dark recordings are shown at `http://127.0.0.1:8766/motion.html` with normal and half-speed playback. The locked screenshot confirms the PIN screen replaces account content on logout.
- Native visual QA confirmed final light/dark screens and sampled transition frames. Testing used an API 35 emulator and synthetic data; physical-device high-refresh performance and haptics were not measured. This work remains on the development branch and is not part of published v1.9.2.

### Currency selector shape follow-up

- Replaced the selected background's fixed 18dp corners with `CircleShape`; currency touch indication uses the same fully rounded shape. Existing spacing, accessible targets and sliding motion remain intact.
- Debug build passed. Captured both selections in light/dark on API 35 and recorded repeated selection changes. Current native preview: `http://127.0.0.1:8766/currency.html`.

## v1.9.3 release preparation

- User approved the palette, tactile motion and fully rounded currency selector, and requested a mandatory update.
- All version metadata is synchronized to 1.9.3, Android code 37. The release build passed; production DEX includes motion/backdrop lifetime code and excludes `UiPreviewActivity` and `SyntheticDashboardSource`.
- The release uses an annotated `[mandatory-update]` tag and the same marker in its public release body, following `RELEASE_POLICY.md`.
