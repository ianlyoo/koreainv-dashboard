/* Calendar rows use DOM text, never untrusted provider HTML. */
let calendarLoading = false;
async function fetchMarketCalendar() {
    if (calendarLoading) return;
    calendarLoading = true;
    const container = document.getElementById('calendar_list');
    const text = (tag, value, className) => {
        const el = document.createElement(tag); el.textContent = value; el.className = className; return el;
    };
    container.replaceChildren(text('p', '시장 일정을 불러오는 중입니다…', 'calendar-notice'));
    container.setAttribute('aria-busy', 'true');
    try {
        const response = await fetch('/api/market-calendar');
        if (response.status === 401) { window.location.href = '/login'; return; }
        if (!response.ok) throw Error('calendar');
        const result = await response.json();
        if (result.status !== 'success' || !Array.isArray(result.data)) throw Error('calendar');
        container.replaceChildren();
        if (!result.data.length) container.append(text('p', '예정된 주요 일정이 없습니다.', 'calendar-notice'));
        const value = item => item == null || item === 'None' || item === '-' ? '미발표' : String(item);
        for (const item of result.data) {
            const row = document.createElement('div'); row.className = 'cal-item';
            row.append(text('span', item.time || '시각 미정', 'cal-date'));
            const details = document.createElement('div'); details.className = 'cal-desc';
            const name = String(item.event || '시장 일정').replace(`${item.currency} - `, '');
            details.append(text('p', name, 'cal-name'));
            details.append(text('p', `중요도 ${Math.max(0, Math.min(3, Number(item.importance) || 0))} · ${item.currency || ''}`, 'cal-importance'));
            details.append(text('p', `실제 ${value(item.actual)} · 예상 ${value(item.forecast)} · 이전 ${value(item.previous)}`, 'cal-values'));
            row.append(details); container.append(row);
        }
    } catch (_error) {
        container.replaceChildren(text('p', '시장 일정을 불러오지 못했습니다.', 'calendar-notice'));
        const retry = text('button', '다시 시도', 'btn-history'); retry.type = 'button';
        retry.addEventListener('click', fetchMarketCalendar); container.append(retry);
    } finally {
        calendarLoading = false; container.setAttribute('aria-busy', 'false');
    }
}
