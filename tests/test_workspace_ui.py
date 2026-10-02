"""Behavior checks for presentation boundaries; fixture-only, no browser or broker."""
import ast
import shutil
import subprocess
import unittest
from pathlib import Path
from app import launcher_presentation

ROOT = Path(__file__).resolve().parents[1]


class LauncherPresentationTests(unittest.TestCase):
    def test_unknown_release_is_not_presented_as_a_version(self):
        text = launcher_presentation.version_info('1.2.3', None, 'unknown')
        self.assertIn('최신 버전: 확인할 수 없음', text)
        self.assertNotIn('v확인', text)
        self.assertIn('조회 전용', text)

    def test_known_version_and_policy_are_preserved(self):
        text = launcher_presentation.version_info('v1.2.3', 'v1.3.0', 'mandatory')
        self.assertIn('현재 버전: v1.2.3', text)
        self.assertIn('최신 버전: v1.3.0', text)
        self.assertIn('업데이트 정책: 필수', text)

    def test_launchers_remain_import_free_syntax_checks_on_other_platforms(self):
        # Parsing never imports AppKit, starts a tray, touches host data or checks releases.
        for path in ('launcher_mac.py', 'launcher_windows.py'):
            with self.subTest(path=path):
                ast.parse((ROOT / path).read_text(encoding='utf-8-sig'))


@unittest.skipUnless(shutil.which('node'), 'Node required for frontend behavior checks')
class WorkspaceBehaviorTests(unittest.TestCase):
    def run_node(self, source):
        subprocess.run(['node', '-e', source], cwd=ROOT, check=True, capture_output=True, text=True)

    def test_latest_search_wins_and_clearing_cancels_pending_results(self):
        self.run_node(r"""
const assert = require('node:assert/strict'), vm = require('node:vm'), fs = require('node:fs');
class Element {
  constructor() { this.attrs = {}; this.children = []; this.style = {}; this.value = ''; }
  setAttribute(k,v) { this.attrs[k] = v; } removeAttribute(k) { delete this.attrs[k]; }
  replaceChildren(...nodes) { this.children = nodes; } append(...nodes) { this.children.push(...nodes); }
  addEventListener() {} scrollIntoView() {}
}
const elements = { stockSearchInput: new Element(), searchDropdown: new Element() };
let timer, requests = [], selected;
const ctx = { console, AbortController, document: {
  getElementById: id => elements[id], createElement: () => new Element(), createTextNode: s => s,
  querySelectorAll: () => elements.searchDropdown.children,
}, setTimeout: cb => { timer = cb; }, clearTimeout: () => { timer = null; },
fetch: () => new Promise(resolve => requests.push(resolve)),
fetchAssetInsight: (ticker, market) => selected = [ticker, market],
window: { location: {} } };
vm.createContext(ctx); vm.runInContext(fs.readFileSync('app/static/js/stock-search.js', 'utf8'),ctx);
const flush = () => new Promise(resolve => setImmediate(resolve));
const response = (ticker,name=ticker) => ({ok:true,status:200,json:async()=>({status:'success',data:[{ticker,name,market:'USA'}]})});
(async()=>{
  ctx.searchStock('old'); timer();
  ctx.searchStock('new'); timer();
  requests[1](response('NEW','<img onerror=bad()>')); await flush();
  requests[0](response('OLD')); await flush(); // A transport may finish even after abort.
  ctx.handleSearchKeydown({key:'Enter',preventDefault(){}});
  assert.deepEqual(selected,['NEW','USA']);
  assert.equal(elements.stockSearchInput.attrs['aria-expanded'],'false');
  ctx.searchStock('later'); timer();
  ctx.searchStock(''); requests[2](response('LATE')); await flush();
  assert.equal(elements.searchDropdown.style.display,'none');
  assert.equal(elements.stockSearchInput.attrs['aria-expanded'],'false');
})().catch(e=>{console.error(e);process.exitCode=1});
""")

    def test_sorting_is_immutable_and_account_scope_is_explicit(self):
        self.run_node(r"""
const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const elements = Object.fromEntries(['holdingCount','portfolioScope','portfolioEmpty'].map(k=>[k,{}]));
const table = {};
const ctx={window:{},document:{addEventListener(){},getElementById:id=>elements[id],querySelector:()=>table}};
vm.createContext(ctx);vm.runInContext(fs.readFileSync('app/static/js/dashboard-ui.js','utf8'),ctx);
const a={name:'A',evalAmtKrw:10,purchaseAmtKrw:20,profit_rt:-50};
const b={name:'B',evalAmtKrw:5,purchaseAmtKrw:1,profit_rt:400};
const input=[a,b];
assert.equal(ctx.window.dashboardUI.sortHoldings(input,'profit')[0],b);
assert.equal(ctx.window.dashboardUI.sortHoldings(input,'return')[0],b);
assert.equal(input[0],a);
ctx.window.dashboardUI.portfolioState([a],'fictional',[{account_id:'fictional',label:'가상 계좌'}]);
assert.equal(elements.portfolioScope.textContent,'가상 계좌 · 주식 평가금액 기준');
ctx.window.dashboardUI.portfolioState([],'all',[]);
assert.equal(elements.portfolioEmpty.hidden,false);
assert.equal(table.hidden,true);
""")

    def test_negative_summary_retains_its_sign(self):
        self.run_node(r"""
const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const elements = new Map();
const element=id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id)};
const ctx={console,window:{addEventListener(){}},document:{addEventListener(){},getElementById:element}};
vm.createContext(ctx);vm.runInContext(fs.readFileSync('app/static/js/dashboard.js','utf8'),ctx);
ctx.renderPortfolioSummary([{type:'KOR',qty:1,now_price:80,avg_price:100,evalAmtKrw:80,purchaseAmtKrw:100}]);
assert.equal(element('val_total_profit').innerText,'-₩20');
""")
