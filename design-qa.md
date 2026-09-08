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
