#!/usr/bin/env python3
"""Offline browser evidence and regression journey against a loopback fixture server.

Requires Playwright plus local Chart.js 4.4.8, LightweightCharts 3.8.0 and axe-core
4.10.3 files named chart.js, lightweight.js, axe.js. All external browser requests
are intercepted; no application server, brokerage client or credentials are loaded.
"""
from __future__ import annotations
import argparse
import json
import threading
from datetime import datetime, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright, expect
from preview_web_dashboard import PreviewHandler, SYNC, insight_payload

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chromium', type=Path, required=True)
    parser.add_argument('--vendor', type=Path, required=True)
    parser.add_argument('--out', type=Path, default=ROOT / 'docs/ux-overhaul-20261002/after')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    class QuietHandler(PreviewHandler):
        def log_message(self, *_): pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), QuietHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    origin = f'http://127.0.0.1:{server.server_port}'
    results = {'fixture_only': True, 'external_requests_forwarded': 0, 'checks': [], 'screenshots': [], 'page_errors': [], 'axe': []}
    checks = results['checks']
    def check(name, value=True):
        assert value, name
        checks.append(name)
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=str(args.chromium), args=['--no-sandbox'])
        def context(width=1440, theme='light', state='normal', height=None):
            ctx = browser.new_context(viewport={'width': width, 'height': height or (1000 if width > 600 else 844)},
                                      locale='ko-KR', timezone_id='Asia/Seoul', reduced_motion='reduce')
            ctx.add_init_script(f"if (!localStorage.getItem('dashboard_appearance_mode')) localStorage.setItem('dashboard_appearance_mode', {json.dumps(theme)});")
            pending = []
            def route(r):
                url = r.request.url
                if 'cdn.jsdelivr.net/npm/chart.js' in url:
                    if state == 'no-chart': return r.abort()
                    return r.fulfill(path=str(args.vendor / 'chart.js'), content_type='application/javascript')
                if 'unpkg.com/lightweight-charts' in url:
                    return r.fulfill(path=str(args.vendor / 'lightweight.js'), content_type='application/javascript')
                if not url.startswith(origin + '/'):
                    return r.abort()
                path = urlsplit(url).path
                if '/scheduled-orders' in path or '/api/orders' in path:
                    raise AssertionError('An execution endpoint was requested')
                if path == '/api/sync':
                    if state == 'loading': pending.append(r); return
                    if state == 'error': return r.fulfill(status=503, json={'status': 'error'})
                    if state == 'empty': return r.fulfill(json={'status':'success','accounts':SYNC['accounts'], 'domestic':{'items':[]},'overseas':{'us_items':[],'jp_items':[]}})
                    if state == 'partial': return r.fulfill(json={**SYNC,'overseas':{**SYNC['overseas'],'us_items':[],'us_summary':{'usd_cash_balance':0,'usd_exrt':1350}},'account_errors':[{'account_label':'해외 투자','error':'unavailable'}]})
                return r.continue_()
            ctx.route('**/*', route)
            page = ctx.new_page()
            page.clock.set_fixed_time(datetime(2026,10,2,5,30,tzinfo=timezone.utc))
            page.on('pageerror', lambda e: results['page_errors'].append(str(e)))
            page.goto(origin + ('/?fixture=wide' if width == 2560 else '/'), wait_until='domcontentloaded')
            if state != 'loading': page.wait_for_function("document.getElementById('loading').dataset.state !== 'loading'")
            page.evaluate('document.fonts.ready')
            return ctx, page, pending
        def overflow(page):
            return page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        def capture(page, name, full=False):
            page.screenshot(path=str(args.out / f'{name}.png'), full_page=full)
            results['screenshots'].append(f'{name}.png')
        def axe(page, name):
            page.add_script_tag(path=str(args.vendor / 'axe.js'))
            violations = page.evaluate("async () => (await axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}})).violations.map(v=>({id:v.id,impact:v.impact,targets:v.nodes.map(n=>n.target)}))")
            results['axe'].append({'screen':name,'violations':violations})
            if not violations: check(f'{name}: no automated WCAG A/AA findings')
            else: print('Accessibility findings:', name, violations, flush=True)
        try:
            for width, theme, name in [(1440,'light','desktop-light'),(1440,'dark','desktop-dark'),(390,'light','mobile-light'),(390,'dark','mobile-dark'),(360,'light','mobile-narrow'),(820,'light','tablet-light'),(1120,'light','compact-desktop'),(2560,'dark','wide-dark')]:
                ctx,page,_ = context(width,theme)
                expect(page.locator('#all_list tr')).to_have_count(17 if width == 2560 else 7)
                check(f'{name}: no document overflow', overflow(page))
                if width == 2560:
                    page.evaluate("applyLayoutMode('mode1')")
                    page.locator('.holding-link').first.click()
                    page.wait_for_function("currentInsightState?.status === 'success'")
                capture(page,name)
                if width in (390,1440): capture(page,name+'-full',True)
                axe(page,name)
                ctx.close()
            for state,width in [('loading',390),('error',1440),('error',390),('empty',390),('partial',1440),('no-chart',1440)]:
                ctx,page,pending = context(width,state=state)
                if state in ('error','loading'):
                    expect(page.locator('#val_total_assets')).to_have_text('—')
                if state == 'error': expect(page.locator('#syncRetry')).to_be_visible()
                if state == 'empty':
                    expect(page.locator('#portfolioEmpty')).to_be_visible()
                    intro = page.locator('#allocation .section-intro').bounding_box()
                    note = page.locator('#allocation_list .scope-note').bounding_box()
                    assert note['y'] >= intro['y'] + intro['height'], 'Empty allocation copy must not overlap its heading'
                if state == 'partial': expect(page.locator('#accountSyncWarning')).to_contain_text('제외')
                if state == 'no-chart':
                    expect(page.locator('#all_list tr')).to_have_count(7)
                    expect(page.locator('#loading')).not_to_be_visible()
                    check('missing chart runtime preserves successful data')
                if state in ('empty', 'no-chart'):
                    expect(page.locator('#allocation .chart-container')).not_to_be_visible()
                check(f'{state}-{width}: no overflow',overflow(page))
                capture(page,f'{state}-{width}',full=state == 'empty')
                axe(page,f'{state}-{width}')
                for r in pending: r.fulfill(json=SYNC)
                ctx.close()
            ctx,page,_ = context()
            original_total = page.locator('#val_total_assets').inner_text()
            # Failed refresh keeps the snapshot, and retry recovers without a reload.
            page.route('**/api/sync*', lambda r:r.fulfill(status=503,json={'status':'error'}))
            page.locator('#syncBtn').click()
            expect(page.locator('#syncNoticeText')).to_contain_text('마지막')
            expect(page.locator('#val_total_assets')).to_have_text(original_total)
            capture(page,'stale-desktop')
            page.unroute('**/api/sync*')
            page.locator('#syncRetry').click()
            expect(page.locator('#loading')).not_to_be_visible()
            check('stale snapshot survives failure and retry recovers')
            # Scope, sort, keyboard currency and summary interactions.
            page.locator('#portfolioAccountFilter').select_option('preview-toss')
            expect(page.locator('#all_list tr')).to_have_count(3)
            expect(page.locator('#portfolioScope')).to_contain_text('해외 투자')
            page.locator('#holdingSort').select_option('return')
            expect(page.locator('.holding-link').first).to_contain_text('Apple')
            page.locator('#currencyToggle').focus();page.keyboard.press('Enter')
            expect(page.locator('#currencyToggle')).to_have_attribute('aria-pressed','true')
            check('account scope, sort and keyboard currency switch')
            page.locator('#cashSummaryCard').focus();page.keyboard.press('Enter')
            expect(page.locator('#cashSummaryCard')).to_have_class(__import__('re').compile('is-flipped'))
            page.locator('#profitSummaryToggle').click()
            expect(page.locator('#profitSummaryToggle')).to_have_attribute('aria-pressed','true')
            check('cash and realized profit summaries remain accessible')
            # Dialog focus loop, nested settings and form validation.
            page.locator('.btn-account').click()
            expect(page.locator('#accountModal')).to_have_attribute('aria-modal','true')
            page.locator('#accountModal .profit-detail-close').focus();page.keyboard.press('Shift+Tab')
            expect(page.locator('#logoutBtn')).to_be_focused()
            page.locator('#appearanceMode').select_option('dark')
            capture(page,'settings-desktop')
            axe(page,'settings-desktop')
            page.get_by_role('button',name='SaveTicker 연결 설정 ›').click()
            expect(page.locator('#accountModal')).to_have_attribute('inert','')
            page.keyboard.press('Escape')
            expect(page.locator('#saveTickerSettings')).not_to_be_visible()
            expect(page.locator('#accountModal')).to_be_visible()
            page.locator('summary').click()
            page.locator('#accountAddSubmitBtn').click()
            check('invalid account form remains open',page.locator('#accountModal').is_visible())
            page.keyboard.press('Escape')
            expect(page.locator('.btn-account')).to_be_focused()
            check('dialog focus trap, nested dismissal and trigger restoration')
            # History remains historical, including filters and period requests.
            page.get_by_role('button',name='거래내역',exact=True).click()
            page.locator('[data-profit-tab="sell"]').click()
            expect(page.locator('#sellHistoryRows tr')).to_have_count(2)
            page.locator('[data-profit-preset="oneYear"]').click()
            expect(page.locator('#realizedProfitStart')).not_to_have_value('')
            capture(page,'history-desktop');axe(page,'history-desktop')
            page.keyboard.press('Escape')
            check('historical trade tabs and period selection')
            # Safe search and keyboard selection, latest-query wins.
            search = page.locator('#stockSearchInput')
            search.fill('Apple')
            expect(page.locator('.search-result')).to_have_count(7)
            page.keyboard.press('ArrowDown');page.keyboard.press('Enter')
            page.wait_for_function("currentInsightState?.status === 'success'")
            expect(search).to_have_attribute('aria-expanded','false')
            capture(page,'insight-desktop');axe(page,'insight-desktop')
            page.locator('#insightCloseBtn').click()
            check('keyboard search opens insight and closes results')
            page.route('**/api/stock-search*', lambda r:r.fulfill(json={'status':'success','data':[{'ticker':'DEMO','market':'USA','name':'<img src=x onerror=alert(1)>'}]}))
            search.fill('hostile');expect(page.locator('.search-result')).to_have_count(1)
            expect(page.locator('#searchDropdown img')).to_have_count(0)
            check('search renders provider content as text')
            page.keyboard.press('Escape')
            page.unroute('**/api/stock-search*')
            # Holding labels and attributes must survive untrusted provider content.
            page.evaluate("""() => {
                const markup = buildHoldingRowHtml({ticker: '" onclick="bad()', name: '<img src=x onerror=bad()>',
                    type: 'USA', qty: 1, now_price: 1, avg_price: 1, evalAmtKrw: 1, purchaseAmtKrw: 1,
                    profit_rt: 0, account_label: '" onmouseover="bad()'});
                document.getElementById('all_list').innerHTML = markup;
            }""")
            expect(page.locator('#all_list img')).to_have_count(0)
            expect(page.locator('#all_list [onmouseover]')).to_have_count(0)
            expect(page.locator('#all_list .holding-link')).to_contain_text('<img src=x onerror=bad()>')
            check('holding content cannot inject elements or event attributes')
            # Storage persistence without storing any financial data.
            page.reload(wait_until='domcontentloaded')
            expect(page.locator('html')).to_have_attribute('data-theme','dark')
            check('theme preference persists across reload')
            ctx.close()
            # Phone insight has a return path and no nested desktop canvas.
            ctx,page,_ = context(390)
            page.locator('.holding-link').first.click()
            page.wait_for_function("currentInsightState?.status === 'success'")
            check('mobile insight is focused and fits', overflow(page))
            capture(page,'insight-mobile');axe(page,'insight-mobile')
            page.locator('#insightCloseBtn').click()
            expect(page.locator('.holding-link').first).to_be_focused()
            page.locator('.btn-account').click()
            check('mobile settings fits', overflow(page))
            capture(page,'settings-mobile');axe(page,'settings-mobile')
            page.keyboard.press('Escape')
            page.get_by_role('button',name='거래내역',exact=True).click()
            expect(page.locator('#buyHistoryRows tr')).to_have_count(1)
            check('mobile history fits', overflow(page))
            capture(page,'history-mobile');axe(page,'history-mobile')
            page.keyboard.press('Escape')
            page.goto(origin+'/login',wait_until='networkidle')
            expect(page.locator('#login-view')).to_be_visible()
            capture(page,'login-mobile');axe(page,'login-mobile')
            page.goto(origin+'/setup',wait_until='networkidle')
            page.locator('#setup-btn').click()
            expect(page.locator('#setup-view')).to_be_visible()
            check('empty setup validation does not navigate away', '/setup' in page.url)
            capture(page,'setup-mobile',True);axe(page,'setup-mobile')
            ctx.close()
            check('no JavaScript runtime errors',not results['page_errors'])
            check('all accessibility scans passed',not any(scan['violations'] for scan in results['axe']))
            print(f"PASS: {len(checks)} checks; {len(results['screenshots'])} screenshots; {len(results['axe'])} accessibility scans")
        finally:
            (args.out / 'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
            browser.close();server.shutdown();server.server_close()


if __name__ == '__main__': main()
