/* Presentation and interaction only. No account storage or API ownership. */
(() => {
    'use strict';
    let hasSnapshot = false;
    let sort = 'value';
    let insightOrigin = null;
    const byId = id => document.getElementById(id);
    const sortHoldings = (items, mode = sort) => [...items].sort((a, b) => {
        if (mode === 'name') return String(a.name || a.ticker).localeCompare(String(b.name || b.ticker), 'ko');
        const value = item => mode === 'return' ? item.profit_rt : mode === 'profit'
            ? item.evalAmtKrw - item.purchaseAmtKrw : item.evalAmtKrw;
        return (Number(value(b)) || 0) - (Number(value(a)) || 0);
    });
    function syncState(state) {
        const notice = byId('loading');
        const pending = state === 'loading';
        const failed = state === 'error';
        if (state === 'success') hasSnapshot = true;
        notice?.classList.toggle('active', pending || failed);
        notice?.classList.toggle('is-error', failed);
        if (notice) notice.dataset.state = state;
        const spinner = notice?.querySelector('.spinner');
        if (spinner) spinner.hidden = !pending;
        if (byId('syncRetry')) byId('syncRetry').hidden = !failed;
        if (byId('syncNoticeText')) byId('syncNoticeText').textContent = failed
            ? (hasSnapshot ? '새로고침하지 못했습니다. 마지막으로 불러온 정보를 표시합니다.' : '자산을 불러오지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.')
            : (hasSnapshot ? '최신 자산 정보를 확인하고 있습니다.' : '자산을 불러오는 중입니다. 잠시만 기다려 주세요.');
        if (byId('syncBtn')) {
            byId('syncBtn').disabled = pending;
            byId('syncBtn').setAttribute('aria-busy', String(pending));
        }
        byId('layoutRoot')?.setAttribute('aria-busy', String(pending && !hasSnapshot));
        document.body.classList.toggle('has-snapshot', hasSnapshot);
    }
    function portfolioState(items, accountId, accounts) {
        const account = accounts.find(item => String(item.account_id) === accountId);
        const scope = accountId === 'all' ? '전체 계좌' : account?.label || '선택한 계좌';
        if (byId('holdingCount')) byId('holdingCount').textContent = `${items.length}종목`;
        if (byId('portfolioScope')) byId('portfolioScope').textContent = `${scope} · 주식 평가금액 기준`;
        const empty = byId('portfolioEmpty');
        if (empty) empty.hidden = items.length !== 0;
        const table = document.querySelector('.holdings-table');
        if (table) table.hidden = items.length === 0;
    }
    function openInsight() {
        if (!document.body.classList.contains('mobile-insight')) insightOrigin = document.activeElement;
        document.body.classList.add('mobile-insight');
        if (window.matchMedia('(max-width: 900px)').matches) {
            byId('rightInsightPanel')?.focus({ preventScroll: true });
            window.scrollTo({ top: 0, behavior: 'instant' });
        }
    }
    function closeInsight() {
        document.body.classList.remove('mobile-insight');
        if (insightOrigin?.isConnected) insightOrigin.focus();
        insightOrigin = null;
    }
    function installSectionNavigation() {
        document.querySelector('.workspace-nav')?.addEventListener('click', event => {
            if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            const link = event.target.closest('a[href^="#"]');
            const target = link && byId(link.hash.slice(1));
            if (!target) return;
            if (!target.getClientRects().length) closeInsightPane();
            target.focus({ preventScroll: true });
            // Keep native fragment/history behavior; the target is reachable before it scrolls.
        });
    }
    // All dialogs share focus containment/restoration, including nested account dialogs.
    // Existing open/close handlers still own form reset, credentials and confirmation.
    function installDialogs() {
        const closeHandlers = {
            customModal: () => closeModal(), realizedProfitModal: () => closeRealizedProfitModal(),
            accountModal: () => closeAccountModal(), accountEditModal: () => closeAccountEditModal(),
            accountDeleteModal: () => closeAccountDeleteModal(), saveTickerSettings: () => closeSaveTickerSettings(),
        };
        const dialogs = Array.from(document.querySelectorAll('.modal-overlay, .profit-detail-overlay'));
        const openStack = [];
        const returnFocus = new Map();
        const focusable = el => Array.from(el.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), summary, [tabindex="0"]'))
            .filter(node => node.getClientRects().length && !node.closest('[inert]'));
        const roots = Array.from(document.body.children).filter(el => !dialogs.includes(el) && !['SCRIPT', 'STYLE'].includes(el.tagName));
        function synchronize() {
            for (const dialog of dialogs) {
                const index = openStack.indexOf(dialog);
                const active = dialog.classList.contains('active');
                if (active && index < 0) {
                    returnFocus.set(dialog, document.activeElement);
                    openStack.push(dialog);
                    dialog.inert = false;
                    dialog.querySelector('.profit-detail-close, .btn-cancel')?.focus();
                } else if (!active && index >= 0) {
                    openStack.splice(index, 1);
                    const previous = returnFocus.get(dialog);
                    returnFocus.delete(dialog);
                    // First restore reachability, then the original trigger.
                    roots.forEach(el => { el.inert = openStack.length > 0; });
                    openStack.forEach(el => { el.inert = false; });
                    if (previous?.isConnected) previous.focus();
                }
            }
            const top = openStack.at(-1);
            roots.forEach(el => { el.inert = !!top; });
            dialogs.forEach(el => {
                el.inert = el !== top;
                el.setAttribute('aria-modal', String(el === top));
                el.setAttribute('aria-hidden', String(!el.classList.contains('active')));
            });
            document.body.style.overflow = top ? 'hidden' : '';
        }
        for (const dialog of dialogs) {
            dialog.setAttribute('role', 'dialog');
            dialog.tabIndex = -1;
            const heading = dialog.querySelector('h2, .modal-title');
            if (heading) {
                heading.id ||= `${dialog.id}Title`;
                dialog.setAttribute('aria-labelledby', heading.id);
            }
            new MutationObserver(synchronize).observe(dialog, { attributes: true, attributeFilter: ['class'] });
        }
        document.addEventListener('keydown', event => {
            const top = openStack.at(-1);
            if (!top) return;
            if (event.key === 'Escape') {
                event.preventDefault(); event.stopImmediatePropagation(); closeHandlers[top.id]?.();
            } else if (event.key === 'Tab') {
                const elements = focusable(top), first = elements[0], last = elements.at(-1);
                if (!first) { event.preventDefault(); top.focus(); }
                else if (event.shiftKey && (document.activeElement === first || !top.contains(document.activeElement))) { event.preventDefault(); last.focus(); }
                else if (!event.shiftKey && (document.activeElement === last || !top.contains(document.activeElement))) { event.preventDefault(); first.focus(); }
            }
        }, true);
        synchronize();
    }
    window.dashboardUI = { syncState, portfolioState, sortHoldings, openInsight, closeInsight,
        setSort(value) { sort = ['value', 'return', 'profit', 'name'].includes(value) ? value : 'value'; applyPortfolioAccountFilter(); },
    };
    document.addEventListener('DOMContentLoaded', () => {
        installDialogs();
        installSectionNavigation();
    }, { once: true });
})();
