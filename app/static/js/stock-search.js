/* Combobox lifecycle: a newer query always owns the results, even before debounce. */
let stockSearchTimer;
let stockSearchSequence = 0;
let stockSearchController;
let stockSearchResults = [];
let stockSearchIndex = -1;
function closeStockSearch() {
    stockSearchSequence += 1;
    clearTimeout(stockSearchTimer);
    stockSearchController?.abort();
    stockSearchResults = [];
    stockSearchIndex = -1;
    document.getElementById('searchDropdown').style.display = 'none';
    const input = document.getElementById('stockSearchInput');
    input.setAttribute('aria-expanded', 'false');
    input.removeAttribute('aria-activedescendant');
}
function closeStockSearchOnBlur() {
    // Closing insight can restore this input before a prior blur timeout fires.
    window.setTimeout(() => {
        const active = document.activeElement;
        if (active !== document.getElementById('stockSearchInput') &&
            !document.getElementById('searchDropdown').contains(active)) closeStockSearch();
    }, 150);
}
function searchStock(query) {
    closeStockSearch();
    const text = query.trim();
    if (!text) return;
    const sequence = stockSearchSequence;
    const dropdown = document.getElementById('searchDropdown');
    const input = document.getElementById('stockSearchInput');
    function message(copy) {
        dropdown.replaceChildren();
        const el = document.createElement('div');
        el.className = 'search-message'; el.textContent = copy; el.setAttribute('role', 'status');
        dropdown.append(el); dropdown.style.display = 'block'; input.setAttribute('aria-expanded', 'true');
    }
    message('종목을 찾고 있습니다…');
    stockSearchTimer = setTimeout(async () => {
        stockSearchController = new AbortController();
        try {
            const response = await fetch(`/api/stock-search?q=${encodeURIComponent(text)}`, { signal: stockSearchController.signal });
            if (response.status === 401) { window.location.href = '/login'; return; }
            if (!response.ok) throw Error('search');
            const result = await response.json();
            if (sequence !== stockSearchSequence) return;
            stockSearchResults = result.status === 'success' && Array.isArray(result.data) ? result.data : [];
            if (!stockSearchResults.length) { message('검색 결과가 없습니다. 종목명이나 티커를 확인해 주세요.'); return; }
            dropdown.replaceChildren();
            stockSearchResults.forEach((item, index) => {
                const option = document.createElement('button');
                option.type = 'button'; option.className = 'search-result'; option.id = `stock-result-${index}`;
                option.setAttribute('role', 'option'); option.setAttribute('aria-selected', 'false'); option.tabIndex = -1;
                const identity = document.createElement('span'), detail = document.createElement('small');
                identity.append(document.createTextNode(String(item.name || item.ticker || '종목')));
                detail.textContent = `${item.ticker || ''} · ${item.market || ''}`; identity.append(detail); option.append(identity);
                option.addEventListener('mousedown', event => event.preventDefault());
                option.addEventListener('click', () => selectSearchResult(item.ticker, item.market));
                dropdown.append(option);
            });
            dropdown.style.display = 'block'; input.setAttribute('aria-expanded', 'true');
        } catch (error) {
            if (sequence !== stockSearchSequence || error.name === 'AbortError') return;
            message('검색하지 못했습니다. 검색어를 다시 입력해 주세요.');
        }
    }, 250);
}
function handleSearchKeydown(event) {
    if (event.key === 'Escape') { event.preventDefault(); closeStockSearch(); return; }
    if (!stockSearchResults.length) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        const movingDown = event.key === 'ArrowDown';
        stockSearchIndex = stockSearchIndex < 0
            ? (movingDown ? 0 : stockSearchResults.length - 1)
            : (stockSearchIndex + (movingDown ? 1 : -1) + stockSearchResults.length) % stockSearchResults.length;
        document.querySelectorAll('.search-result').forEach((el, index) => el.setAttribute('aria-selected', String(index === stockSearchIndex)));
        const option = document.getElementById(`stock-result-${stockSearchIndex}`);
        document.getElementById('stockSearchInput').setAttribute('aria-activedescendant', option.id);
        option.scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'Enter') {
        event.preventDefault();
        const item = stockSearchResults[Math.max(0, stockSearchIndex)];
        selectSearchResult(item.ticker, item.market);
    }
}
function selectSearchResult(ticker, market) {
    document.getElementById('stockSearchInput').value = '';
    closeStockSearch();
    fetchAssetInsight(String(ticker || ''), market);
}
