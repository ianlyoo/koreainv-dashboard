"""SaveTicker rendering contract checks without a live server or credentials."""
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which('node'), 'Node.js is required for frontend behavior checks')
class SaveTickerFrontendTests(unittest.TestCase):
    def test_rendering_values_sources_and_untrusted_data(self):
        script = r"""
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const context = {
    console, URL, setTimeout: () => 1, clearTimeout() {},
    document: { addEventListener() {}, querySelectorAll: () => [], getElementById: () => ({}) },
    window: { addEventListener() {} },
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('app/static/js/dashboard.js', 'utf8'), context);
vm.runInContext(`
const hostile = '<img src=x onerror="alert(1)">';
const state = { status: 'success', ticker: hostile, marketType: 'USA', data: {
    source: 'saveticker', financials: { shortName: hostile, currency: 'USD', currentPrice: 100 },
    saveticker: { status: 'partial', fetched_at: hostile, sections: {
        header: { price: 0, asOf: 1788882840000, changePercent: 0, marketStatus: hostile, dayRange: { low: 0, high: 1, current: 0 } },
        key_metrics: { per: { value: 0 }, eps: { value: null }, roe: { value: 33.4322 }, revenueTtm: { value: 22187000000 }, periodLabel: "'26 Q2", dividendYield: { value: 0.7097 }, shortInterestPct: { value: 1.2 } },
        revenue: { source: hostile, quarters: [{ label: hostile, revenue: 0, yoy: null }] },
        analyst: { provider: hostile, dist: { buy: 0, hold: 0, sell: 0 }, upsidePct: 9999, recent: [{ firm: hostile, rating: hostile, target: 0, at: hostile }] },
        options: { optionable: true, batchIsProvisional: true, batchIsPriorDay: true, snapshotIsPriorDay: true, maxPain: 0, gammaPer1Pct: 108726845.745, netGammaExposure: 79575, volumeShare: { call: 69.414, put: 30.586 }, openInterestShare: { call: 0, put: 100 }, premiumShare: { call: 71.0679, put: 28.9321 }, optionVolumeVsAvg: { windows: { d3: { available: true, ratioPct: 0 }, d7: { available: false, ratioPct: 98765 }, d30: { available: true, ratioPct: 171.7 } } }, nearestExpiry: hostile },
        insider: { window: 90, netValue: 0, buyCount: 0, sellCount: 0, recent: [{ name: hostile, title: hostile, value: 0, transactionDate: hostile, transactionCode: hostile }] },
    } },
} };
const output = buildInsightInfoHtml(state);
if (output.includes('<img') || output.includes('9999') || output.includes('79,575')) throw Error('Unescaped text or incorrect upstream metric leaked');
for (const expected of ['SaveTicker · 일부 제공', '33.43%', '0.71%', '1.2%', '69.41%', '$0', '0배', '22,187,000,000', '&#39;26 Q2', '108,726,845.75', '잠정 집계', '전일 스냅샷', '전일 배치', '전체 만기', '주가 1% 변동 기준', '프리미엄 비중', '71.07%', '171.7%', '현재 시각까지의 누적 거래량', '—']) {
    if (!output.includes(expected)) throw Error('Missing expected output: ' + expected);
}
if ((output.match(/id="tv_chart_container"/g) || []).length !== 1) throw Error('Duplicate or missing chart mount');
if (output.includes('news-item') || output.includes('Yahoo') || output.includes('NaN') || output.includes('Infinity')) throw Error('Incorrect source or invalid display');
for (const value of [null, undefined, '', ' ', false, Infinity, NaN, 'NaN']) {
    if (insightNumber(value) !== '—') throw Error('Missing/nonfinite value not rejected');
}
if (insightNumber(0) !== '0' || insightNumber('0') !== '0') throw Error('Zero lost');
if (insightSafeUrl('javascript:alert(1)') || insightSafeUrl('data:text/html,<script>')) throw Error('Unsafe URL allowed');
if (!insightSafeUrl('https://example.com/?a="&b=1').includes('&amp;')) throw Error('URL attribute not escaped');
state.data.saveticker.sections.news = { items: [
    { title: hostile, publisher: hostile, published_at: hostile, link: 'https://saveticker.com/news/123?a=1&b=2' },
    { title: 'Unsafe headline', link: 'javascript:alert(1)' },
    { title: 'Offsite headline', link: 'https://saveticker.com.evil.example/news/1' },
    { title: 'Other route', link: 'https://saveticker.com/company/AVGO' },
] };
const newsOutput = buildInsightInfoHtml(state);
if (!newsOutput.includes('관련 뉴스') || !newsOutput.includes('https://saveticker.com/news/123?a=1&amp;b=2')) throw Error('Provider news missing');
if (newsOutput.includes('<img') || newsOutput.includes('javascript:') || newsOutput.includes('evil.example') || newsOutput.includes('href="https://saveticker.com/company')) throw Error('Unsafe news escaped validation');
if (output.includes('98765') || output.includes('98,765')) throw Error('Unavailable comparison was shown');
if (!output.includes('class="st-details"') || !output.endsWith('</div></div>')) throw Error('Details grouping missing');
const tagCounts = tag => [(output.match(new RegExp('<' + tag + '(?: |>)', 'g')) || []).length, (output.match(new RegExp('</' + tag + '>', 'g')) || []).length];
for (const tag of ['div', 'section', 'ul', 'table']) { const [open, close] = tagCounts(tag); if (open !== close) throw Error('Unbalanced ' + tag); }
for (const [input, expected] of [
    [0, '1970-01-01 09:00 KST'],
    ['2026-09-08T15:55:06.768418Z', '2026-09-09 00:55 KST'],
    ['2026-09-08T15:55:00+09:00', '2026-09-08 15:55 KST'],
    ['2026-09-08', '2026-09-08'],
    ['2024-02-29', '2024-02-29'],
]) { if (insightDate(input) !== expected) throw Error('Incorrect date: ' + input); }
for (const invalid of [null, false, '', hostile, '2026-02-30', '2026-02-30T12:00:00Z', '2026-09-08T25:00:00Z', '2026-09-08 12:00', 'yesterday', Infinity]) {
    if (insightDate(invalid) !== '—') throw Error('Invalid date accepted: ' + invalid);
}
cachedItems = [{ type: 'USA', ticker: 'AVGO', name: 'Broadcom portfolio name' }];
if (getInsightDisplayName({ ...state, ticker: 'AVGO', data: { source: 'saveticker', financials: { shortName: 'AVGO' } } }) !== 'Broadcom portfolio name') throw Error('Holding identity lookup failed');
if (getInsightDisplayName({ ...state, ticker: 'AVGO', data: { source: 'saveticker', financials: { shortName: 'Actual provider name' } } }) !== 'Actual provider name') throw Error('Provider identity overwritten');
cachedItems = [];
const partial = buildInsightInfoHtml({ ...state, data: { ...state.data, saveticker: { status: 'partial', sections: {} } } });
if (!partial.includes('현재 제공되지 않습니다') || partial.includes('NaN')) throw Error('Partial section failed');
const unsupported = buildInsightInfoHtml({ ...state, data: { financials: { currentPrice: 1, currency: 'USD', forwardPE: 'N/A', returnOnEquity: 'N/A', debtToEquity: 'N/A', beta: 'N/A', marketCap: 'N/A', shortPercentOfFloat: 'N/A', targetMeanPrice: 'N/A', fiftyTwoWeekLow: 'N/A', fiftyTwoWeekHigh: 'N/A' }, saveticker: { status: 'unsupported' }, news: [] } });
if (!unsupported.includes('Yahoo Finance') || unsupported.includes('st-insight')) throw Error('Yahoo fallback source incorrect');
`, context);
"""
        result = subprocess.run(['node', '-e', script], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_chart_series_and_lifecycle(self):
        script = r"""
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const elements = new Map();
const element = id => {
    if (!elements.has(id)) elements.set(id, { id, innerHTML: '', isConnected: true, closest() { return { remove() {} }; } });
    return elements.get(id);
};
let reduced = false;
const created = [];
class ChartMock {
    constructor(canvas, config) {
        this.canvas = canvas; this.config = config; this.data = config.data; this.options = config.options;
        this.tooltip = { setActiveElements: active => { this.tooltipActive = active; } };
        this.destroyed = 0; created.push(this);
    }
    destroy() { this.destroyed++; }
    setActiveElements(active) { this.active = active; }
    getDatasetMeta() { return { data: [{ getCenterPoint: () => ({ x: 1, y: 1 }) }] }; }
    update() {}
}
const context = {
    console, URL, setTimeout: () => 1, clearTimeout() {},
    getComputedStyle: () => ({ getPropertyValue: () => '#999999' }),
    document: { documentElement: { dataset: { theme: 'dark' } }, addEventListener() {}, querySelectorAll: () => [], getElementById: element },
    window: { addEventListener() {}, matchMedia: () => ({ matches: reduced }) },
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('app/static/js/dashboard.js', 'utf8'), context);
const state = { status: 'success', ticker: 'AVGO', marketType: 'USA', data: { source: 'saveticker', financials: { shortName: 'AVGO', currentPrice: 110 }, saveticker: { status: 'available', sections: {
    header: { price: 110 },
    revenue: { quarters: [ { label: "'26 Q2", revenue: 400 }, { label: "'26 Q1", revenue: 300 }, { label: "'25 Q4", revenue: null }, { label: "'25 Q3", revenue: 100 }, { label: "'25 Q2", revenue: 0 } ] },
    analyst: { analystCount: 29, dist: { buy: 26, hold: 3, sell: 0 }, target: { low: 100, mean: 150, high: 200 } },
    options: { volumeShare: { call: 69.414, put: 30.586 }, openInterestShare: { call: 0, put: 100 }, premiumShare: { call: null, put: 100 }, optionVolumeVsAvg: { windows: { d3: { available: true, ratioPct: 0 }, d7: { available: false, ratioPct: 222 }, d30: { available: true, ratioPct: 171.7 } } } },
    insider: { window: 90, buyCount: 1, sellCount: 8, netValue: -283323409.82, recent: Array.from({ length: 9 }, (_, i) => ({ name: 'Person' + i, transactionCode: 'S', value: -i, transactionDate: '2026-07-10' })) },
} } } };
context.fixture = state;
const normalize = value => JSON.parse(JSON.stringify(value));
const specs = normalize(context.saveTickerDetailChartSpecs(state));
assert.equal(specs.length, 6);
const revenue = specs.find(s => s.id === 'revenue');
assert.deepEqual(revenue.labels, ["'25 Q2", "'25 Q3", "'25 Q4", "'26 Q1", "'26 Q2"]);
assert.deepEqual(revenue.datasets[0].data, [0, 100, null, 300, 400]);
const consensus = specs.find(s => s.id === 'consensus');
assert.equal(consensus.datasets[2].data[0], 0);
assert.ok(Math.abs(consensus.datasets.reduce((sum, d) => sum + d.data[0], 0) - 100) < 0.00001);
assert.deepEqual(specs.find(s => s.id === 'shares').labels, ['거래량', '미결제약정']);
assert.deepEqual(specs.find(s => s.id === 'shares').datasets[0].data, [69.414, 0]);
assert.deepEqual(specs.find(s => s.id === 'volume-comparison').datasets[0].data, [0, null, 171.7]);
assert.equal(specs.find(s => s.id === 'volume-comparison').reference, 100);
assert.deepEqual(specs.find(s => s.id === 'insider-counts').datasets[0].data, [1, 8]);
const malformed = structuredClone(state);
malformed.data.saveticker.sections.analyst.analystCount = 30;
malformed.data.saveticker.sections.options.volumeShare = { call: 50, put: 0 };
malformed.data.saveticker.sections.options.openInterestShare = { call: 0, put: 0 };
assert.ok(!context.saveTickerDetailChartSpecs(malformed).some(s => ['consensus', 'shares'].includes(s.id)));
const fallback = context.buildSaveTickerInsightHtml(state);
assert.ok(!fallback.includes('<canvas'));
assert.ok(fallback.includes('Person8') && fallback.includes('-$283,323,409.82'));
assert.ok(fallback.indexOf('id="tv_chart_container"') < fallback.indexOf('insight-top-bar'));
context.Chart = ChartMock;
context.initializeInsightDetailCharts(state);
assert.equal(created.length, 6);
for (const chart of created) {
    const axis = chart.config.type === 'scatter' || chart.options.indexAxis === 'y' ? 'x' : 'y';
    if (chart.config.type !== 'scatter') {
        assert.equal(chart.options.scales[axis].min, 0);
        assert.equal(chart.options.scales[axis].beginAtZero, true);
    }
    assert.equal(chart.options.animation.duration, 250);
}
assert.equal(created.find(c => c.canvas.id === 'st-chart-shares').options.scales.x.max, 100);
const first = created[0];
first.canvas.onkeydown({ key: 'ArrowRight', preventDefault() {} });
assert.equal(first.active[0].index, 0);
first.canvas.onkeydown({ key: 'Escape', preventDefault() {} });
assert.equal(first.active.length, 0);
// Theme rebuild destroys all detail charts and respects reduced motion.
reduced = true;
vm.runInContext('currentInsightState = fixture; scheduleMarketOverviewMount = () => {}; refreshChartThemes();', context);
assert.ok(created.slice(0, 6).every(c => c.destroyed === 1));
assert.equal(created.length, 12);
assert.ok(created.slice(6).every(c => c.options.animation === false));
// Selecting another stock first renders loading; previous charts cannot leak.
vm.runInContext("currentInsightState = { status: 'loading', ticker: 'AAPL', marketType: 'USA' }; renderCurrentInsightContent();", context);
assert.ok(created.slice(6).every(c => c.destroyed === 1));
context.disposeInsightChart();
assert.ok(created.every(c => c.destroyed === 1));
context.initializeInsightDetailCharts(state);
vm.runInContext("currentInsightState = { status: 'error', ticker: 'AAPL', marketType: 'USA' }; renderCurrentInsightContent();", context);
assert.ok(created.every(c => c.destroyed === 1));
"""
        result = subprocess.run(['node', '-e', script], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
