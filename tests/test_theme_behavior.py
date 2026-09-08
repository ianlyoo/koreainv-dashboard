"""Run appearance transitions against a minimal browser model; no server or network."""
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which('node'), 'Node.js is required for frontend behavior checks')
class ThemeBehaviorTests(unittest.TestCase):
    def test_preference_system_storage_and_chart_lifecycle(self):
        script = r"""
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
function createBrowser(saved, blocked = false) {
    const handlers = {};
    const element = { value: '', addEventListener(name, callback) { this[name] = callback; } };
    const media = { matches: true, addEventListener(name, callback) { this.change = callback; } };
    const document = {
        documentElement: { dataset: {}, style: {} },
        getElementById() { return element; },
        addEventListener(name, callback) { handlers['document:' + name] = callback; },
    };
    const sandbox = {
        document, CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
        localStorage: {
            getItem() { if (blocked) throw Error('blocked'); return saved; },
            setItem(key, value) { if (blocked) throw Error('blocked'); saved = value; },
        },
        addEventListener(name, callback) { handlers[name] = callback; },
        dispatchEvent(event) { handlers[event.type]?.(event); },
        matchMedia() { return media; },
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync('app/static/js/theme.js', 'utf8'), sandbox);
    return { sandbox, handlers, document, media, element, saved: () => saved };
}
const b = createBrowser(null);
assert.equal(b.document.documentElement.dataset.theme, 'dark');
b.handlers['document:DOMContentLoaded']();
assert.equal(b.element.value, 'dark');
b.media.matches = false; b.media.change();
assert.equal(b.document.documentElement.dataset.theme, 'dark');
b.element.change({ target: { value: 'system' } });
assert.equal(b.document.documentElement.dataset.theme, 'light');
b.element.change({ target: { value: 'dark' } });
assert.equal(b.saved(), 'dark');
b.media.change();
assert.equal(b.document.documentElement.dataset.theme, 'dark');
b.handlers.storage({ key: 'dashboard_appearance_mode', newValue: 'light' });
assert.equal(b.element.value, 'light');
assert.equal(b.document.documentElement.dataset.theme, 'light');
b.handlers.storage({ key: null, newValue: null });
assert.equal(b.element.value, 'dark');
const blocked = createBrowser(null, true);
blocked.sandbox.dashboardTheme.setPreference('light');
assert.equal(blocked.document.documentElement.dataset.theme, 'light');
const invalid = createBrowser('unexpected');
assert.equal(invalid.sandbox.dashboardTheme.preference, 'dark');

// Theme updates preserve the active lightweight chart (including its viewport).
const ctx = b.sandbox;
ctx.getComputedStyle = () => ({ getPropertyValue: (key) => key });
ctx.console = console;
ctx.setTimeout = () => 1;
ctx.clearTimeout = () => {};
ctx.document.querySelectorAll = () => [];
vm.runInContext(fs.readFileSync('app/static/js/dashboard.js', 'utf8'), ctx);
vm.runInContext(`
    let removed = 0, disconnected = 0, updated = 0;
    currentInsightState = { status: 'success', ticker: '005930', data: { history: [] } };
    insightChart = { applyOptions() { updated++; }, remove() { removed++; } };
    const originalChart = insightChart;
    insightChartKind = 'lightweight';
    insightChartObserver = { disconnect() { disconnected++; } };
    insightCandleSeries = { applyOptions() {} };
    insightVolumeSeries = { setData() {} };
    refreshChartThemes();
    if (insightChart !== originalChart || updated !== 1 || removed !== 0 || currentInsightState.ticker !== '005930') throw Error('Theme changed chart identity or insight state');
    disposeInsightChart();
    disposeInsightChart();
    if (removed !== 1 || disconnected !== 1 || insightChart !== null) throw Error('Chart lifecycle leaked');
`, ctx);
"""
        subprocess.run(['node', '-e', script], cwd=ROOT, check=True, capture_output=True, text=True)
