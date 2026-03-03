# Korea Investment Dashboard - Context

## Project Summary
- Goal: Personal dashboard to monitor Korea Investment & Securities account status.
- Stack:
  - Backend: FastAPI (`main.py`)
  - Frontend: Single-page template (`templates/index.html`)
  - Realtime: KIS WebSocket (`ws_client.py`) -> SSE (`/api/realtime-prices`)
- Main flow:
  1. Login/setup with PIN-based encrypted settings
  2. `/api/sync` loads domestic/overseas balances
  3. WebSocket subscriptions start for held symbols
  4. SSE pushes price updates to frontend
  5. Frontend updates table/summary/chart

## Important Files
- `main.py`: API routes, auth/session checks, sync + realtime SSE endpoints
- `api_client.py`: access token + balance fetch (domestic/us/jp)
- `ws_client.py`: approval key cache, WS connection/reconnect, subscription, parse + broadcast
- `templates/index.html`: main UI and client logic (sync, realtime, table/chart, orders)
- `order_client.py`: order/cancel/asking-price API wrappers

## Current Status (Completed)

### 1) Realtime profit rate basis fixed
- Problem: profit rate in holdings detail was switching to daily change rate.
- Fix applied:
  - Frontend now computes holding profit by average-price basis:
    - `((now_price - avg_price) / avg_price) * 100`
  - Applied in:
    - Initial sync item merge
    - Realtime update path
    - Table render path
  - `data.change_rate` is no longer used for holdings profit display.

### 2) Realtime matching stability improved
- Added/used ticker normalization for matching:
  - Handles domestic code variants and overseas raw symbol fallback (`raw_symbol`).

### 3) Japan symbols included in WS subscription
- `/api/sync` now includes JP holdings in `overseas_codes` for WS start/update.
- `api_client.py` JP item includes `excg_cd` fallback for subscription prefix mapping.

### 4) Hover flicker reduced in holdings table
- Problem: realtime event called full `renderTable()` and replaced tbody repeatedly.
- Fix applied:
  - Added row-level partial update path in `index.html`:
    - Rows tagged with `data-ticker`
    - Cells tagged with `.js-eval`, `.js-now`, `.js-profit`
    - `updateRealtimeRows(changedTickers)` updates only changed rows
  - Realtime loop now calls partial update instead of full table rerender.

### 5) index.html recovery from broken state
- `index_backup.html` was restored into `templates/index.html`.
- Critical logic patches were re-applied to restored file.
- Script syntax check currently passes.

## Current Status (In Progress / Watch Items)
- Encoding artifacts still exist in some Korean UI text literals in `templates/index.html`.
  - Functional logic is working, but some labels/messages may be garbled.
  - Recommended next: text cleanup pass (UI strings only, no logic changes).

## Known Risks
- `templates/index.html` is large and heavily inline-scripted.
  - Small edits can break unrelated sections if not carefully scoped.
- Mixed encoding history (garbled literals) increases patch fragility.
- There is also `templates/Dashboard.html` in repo, but active main template is `templates/index.html`.

## Suggested Next Tasks
1. Text cleanup pass for garbled Korean literals in `templates/index.html`.
2. Optional: reduce chart update frequency (batch/debounce) to further reduce UI churn.
3. Add small smoke tests / scripts:
   - `/api/sync` success
   - SSE stream receiving price events
   - holdings row updates without hover reset.
4. Consider splitting frontend JS from HTML into separate module file for safer edits.

## Run / Verify
- Start server:
  - `python main.py`
- Open:
  - `http://localhost:8000`
- Basic verification checklist:
  1. Login/setup succeeds
  2. `/api/sync` loads holdings
  3. Realtime status turns connected
  4. Holdings profit rate matches avg-price basis
  5. Hover on holdings row does not flicker heavily during realtime

## Notes for Next Machine
- This file intentionally avoids secrets.
- Credentials are expected from local `.env` + encrypted settings in `data/settings.json`.
- If behavior differs on another machine:
  - hard refresh browser (`Ctrl+F5`)
  - verify template being served is `templates/index.html`
  - check backend logs for WS subscribe/parse errors.

