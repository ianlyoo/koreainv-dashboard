#!/usr/bin/env python3
"""Targeted, offline regressions for the three Astra interaction findings.

Uses only the synthetic preview server and locally replayed browser libraries.
The before phase records the expected defects without stopping at the first one.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import threading
from datetime import datetime, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import expect, sync_playwright
from preview_web_dashboard import PreviewHandler

ROOT = Path(__file__).resolve().parents[1]
UI_FILES = ('app/static/js/dashboard.js', 'app/static/js/dashboard-ui.js',
            'app/static/js/stock-search.js', 'app/static/css/workspace.css')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chromium', type=Path, required=True)
    parser.add_argument('--vendor', type=Path, required=True)
    parser.add_argument('--phase', choices=('before', 'after'), required=True)
    parser.add_argument('--out', type=Path, default=ROOT / 'docs/ux-overhaul-20261002/review-interactions')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    class QuietHandler(PreviewHandler):
        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), QuietHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    origin = f'http://127.0.0.1:{server.server_port}'
    result = {'phase': args.phase, 'fixture_only': True, 'external_requests_forwarded': 0,
              'head_at_test': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'ui_sha256': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in UI_FILES},
              'checks': [], 'page_errors': [], 'unexpected_requests': []}

    def record(name, passed, observed):
        result['checks'].append({'case': name, 'passed': bool(passed), 'observed': observed})

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=str(args.chromium), args=['--no-sandbox'])

        def context(width=1440, mode='mode2'):
            ctx = browser.new_context(viewport={'width': width, 'height': 1000 if width > 600 else 844},
                                      locale='ko-KR', timezone_id='Asia/Seoul', reduced_motion='reduce')
            ctx.add_init_script("localStorage.setItem('dashboard_appearance_mode', 'light');")

            def route(r):
                url = r.request.url
                for host_path, file in [('cdn.jsdelivr.net/npm/chart.js', 'chart.js'),
                                        ('unpkg.com/lightweight-charts', 'lightweight.js')]:
                    if host_path in url:
                        return r.fulfill(path=str(args.vendor / file), content_type='application/javascript')
                if not url.startswith(origin + '/'):
                    return r.abort()
                if r.request.method != 'GET' or '/orders' in url or '/scheduled-order' in url:
                    result['unexpected_requests'].append({'method': r.request.method, 'path': url.split(origin)[-1]})
                    return r.abort()
                return r.continue_()

            ctx.route('**/*', route)
            page = ctx.new_page()
            page.clock.set_fixed_time(datetime(2026, 10, 2, 5, 30, tzinfo=timezone.utc))
            page.on('pageerror', lambda error: result['page_errors'].append(str(error)))
            page.goto(origin, wait_until='domcontentloaded')
            expect(page.locator('#all_list tr')).to_have_count(7)
            page.evaluate('(mode) => applyLayoutMode(mode, false)', mode)
            page.evaluate('document.fonts.ready')
            return ctx, page

        def open_holding(page):
            holding = page.locator('.holding-link').first
            holding.focus()
            page.keyboard.press('Enter')
            expect(page.locator('#rightInsightPanel')).to_be_visible()
            page.wait_for_function("currentInsightState?.status === 'success'")
            return holding

        def close_case(width, mode, capture=False):
            ctx, page = context(width, mode)
            try:
                holding = open_holding(page)
                page.locator('#insightCloseBtn').focus()
                page.keyboard.press('Enter')
                observed = {'insight_visible': page.locator('#rightInsightPanel').is_visible(),
                            'widgets_visible': page.locator('#rightWidgetsStack').is_visible(),
                            'origin_focused': holding.evaluate('(el) => el === document.activeElement'),
                            'document_fits': page.evaluate('document.documentElement.scrollWidth <= innerWidth')}
                record(f'close-{mode}-{width}', not observed['insight_visible'] and
                       observed['widgets_visible'] and observed['origin_focused'] and observed['document_fits'], observed)
                if capture:
                    page.screenshot(path=str(args.out / f'{args.phase}-expanded-close.png'))
                if args.phase == 'after':
                    open_holding(page)
                    record(f'reopen-{mode}-{width}', page.locator('#rightInsightPanel').is_visible(),
                           {'ticker': page.evaluate('currentInsightState.ticker')})
            finally:
                ctx.close()

        try:
            close_case(2560, 'mode1', capture=True)
            # Each section starts with a fresh, hidden widget stack in basic mode.
            for target in ('allocation', 'markets'):
                ctx, page = context()
                try:
                    open_holding(page)
                    expect(page.locator('#rightWidgetsStack')).not_to_be_visible()
                    link = page.locator(f'.workspace-nav a[href="#{target}"]')
                    link.focus()
                    page.keyboard.press('Enter')
                    observed = page.locator(f'#{target}').evaluate('''el => ({
                        visible: !!el.getClientRects().length, focused: el === document.activeElement,
                        top: el.getBoundingClientRect().top, viewport: innerHeight, hash: location.hash,
                        insightVisible: !!document.getElementById('rightInsightPanel').getClientRects().length
                    })''')
                    record(f'section-{target}', observed['visible'] and observed['focused'] and
                           not observed['insightVisible'] and 0 <= observed['top'] < observed['viewport'] and
                           observed['hash'] == f'#{target}', observed)
                finally:
                    ctx.close()

            ctx, page = context()
            try:
                search = page.locator('#stockSearchInput')
                for key, expected in [('ArrowUp', 'stock-result-6'), ('ArrowDown', 'stock-result-0')]:
                    search.fill('fixture')
                    expect(page.locator('.search-result')).to_have_count(7)
                    page.keyboard.press(key)
                    active = search.get_attribute('aria-activedescendant')
                    record(f'initial-{key}', active == expected, {'active': active, 'expected': expected})
                    if args.phase == 'after':
                        expect(page.locator(f'#{expected}')).to_have_attribute('aria-selected', 'true')
                        page.keyboard.press('ArrowDown' if key == 'ArrowUp' else 'ArrowUp')
                        wrapped = 'stock-result-0' if key == 'ArrowUp' else 'stock-result-6'
                        record(f'wrap-{key}', search.get_attribute('aria-activedescendant') == wrapped,
                               {'active': search.get_attribute('aria-activedescendant'), 'expected': wrapped})
                    page.keyboard.press('Escape')
                    expect(search).to_have_attribute('aria-expanded', 'false')
                    search.fill('')
                if args.phase == 'after':
                    search.fill('fixture')
                    expect(page.locator('.search-result')).to_have_count(7)
                    page.keyboard.press('ArrowUp')
                    page.keyboard.press('Enter')
                    page.wait_for_function("currentInsightState?.status === 'success'")
                    expect(search).to_have_attribute('aria-expanded', 'false')
                    record('initial-ArrowUp-Enter-selects-last', page.evaluate('currentInsightState.ticker') == '7203',
                           {'ticker': page.evaluate('currentInsightState.ticker')})
                    page.locator('#insightCloseBtn').click()
                    expect(search).to_be_focused()
            finally:
                ctx.close()

            if args.phase == 'after':
                for width, mode in [(1440, 'mode1'), (1440, 'mode2'), (390, 'mode1'), (390, 'mode2')]:
                    close_case(width, mode)
                ctx, page = context(2560, 'mode1')
                try:
                    holding = open_holding(page)
                    page.evaluate("applyLayoutMode('mode2', false)")
                    expect(page.locator('#rightInsightPanel')).to_be_visible()
                    expect(page.locator('#rightWidgetsStack')).not_to_be_visible()
                    page.locator('#insightCloseBtn').click()
                    expect(holding).to_be_focused()
                    page.evaluate("applyLayoutMode('mode1', false)")
                    record('layout-switch-retains-dismissal', not page.locator('#rightInsightPanel').is_visible(),
                           {'insight_visible': page.locator('#rightInsightPanel').is_visible()})
                finally:
                    ctx.close()

            failures = [check['case'] for check in result['checks'] if not check['passed']]
            assert not result['page_errors'] and not result['unexpected_requests'], result
            if args.phase == 'before':
                assert set(failures) == {'close-mode1-2560', 'section-allocation', 'section-markets', 'initial-ArrowUp'}, failures
                print('REPRODUCED: all three P3 defects (four failing assertions); initial ArrowDown passes.')
            else:
                assert not failures, failures
                print(f"PASS: {len(result['checks'])} focused interaction checks; no runtime errors or unexpected requests.")
        finally:
            (args.out / f'{args.phase}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
            browser.close()
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    main()
