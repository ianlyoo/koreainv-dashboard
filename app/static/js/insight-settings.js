/* Credentials live only in this form/request, never browser persistence. */
let stPreviousFocus;
function clearSaveTickerBrowserData() {
    currentInsightRequestSeq += 1;
    currentInsightState = null;
    renderCurrentInsightContent();
}
function stNotice(message) { document.getElementById('stConnectionStatus').textContent = message; }
async function stRequest(path, options) {
    const response = await fetch('/api/saveticker/' + path, options);
    const data = await response.json();
    if (response.status === 401) { window.location.href = '/login'; throw Error('잠금 해제가 필요합니다.'); }
    if (!response.ok) throw Error(typeof data.detail === 'string' ? data.detail : '요청을 처리하지 못했습니다.');
    return data;
}
async function openSaveTickerSettings() {
    stPreviousFocus = document.activeElement;
    document.getElementById('stConnectionForm').reset();
    document.getElementById('saveTickerSettings').classList.add('active');
    document.getElementById('stEmail').focus();
    stNotice('연결 상태를 확인하는 중입니다.');
    try {
        const data = await stRequest('connection');
        stNotice((data.connected ? '연결됨' : '연결 안 됨') + (data.saved ? ' · 기기에 저장됨' : ' · 기기 저장 없음') + (data.migration_incomplete ? ' · 연결 정보 이전 미완료' : '') + (data.warning ? ' · ' + data.warning : ''));
    } catch (error) { stNotice(error.message); }
}
function closeSaveTickerSettings() {
    document.getElementById('stConnectionForm').reset();
    document.getElementById('saveTickerSettings').classList.remove('active');
    stPreviousFocus?.focus();
}
async function connectSaveTicker(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button[type=submit]');
    button.disabled = true;
    stNotice('연결하는 중입니다.');
    clearSaveTickerBrowserData();
    try {
        const pending = stRequest('connection', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: document.getElementById('stEmail').value, password: document.getElementById('stPassword').value, remember: document.getElementById('stRemember').checked }) });
        form.reset();
        await pending;
        // Reload also drops every browser-side insight snapshot from the previous connection.
        window.location.reload();
    } catch (error) { stNotice(error.message); }
    finally { form.reset(); button.disabled = false; }
}
async function manageSaveTicker(kind) {
    clearSaveTickerBrowserData();
    try { await stRequest(kind, { method: 'DELETE' }); window.location.reload(); }
    catch (error) { stNotice(error.message); }
}
document.getElementById('saveTickerSettings').addEventListener('keydown', event => {
    if (event.key === 'Escape') { closeSaveTickerSettings(); event.stopPropagation(); }
    if (event.key === 'Tab') {
        const elements = Array.from(event.currentTarget.querySelectorAll('button:not(:disabled), input'));
        const first = elements[0], last = elements[elements.length - 1];
        if (event.shiftKey && document.activeElement === first) { last.focus(); event.preventDefault(); }
        else if (!event.shiftKey && document.activeElement === last) { first.focus(); event.preventDefault(); }
    }
});
