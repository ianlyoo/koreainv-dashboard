        let myChart = null;
        let currentCurrencyMode = 'local'; // 'local' or 'krw'
        let cachedItems = [];
        let cachedKrCash = 0;
        let cachedUsdCash = 0;
        let cachedJpyCash = 0;
        let cachedExrt = 1350;
        let cachedJpExrt = 905;
        let currentTradingViewWidget = null;
        let currentLayoutMode = 'mode2';
        let rightPaneState = 'widgets';
        let assetCardShowingForeign = false;
        let cashCardShowingForeign = false;
        let profitCardShowingRealized = false;
        let realizedProfitSummaryCache = new Map();
        let realizedProfitDetailCache = new Map();
        let realizedProfitSummaryLoading = false;
        let realizedProfitDetailLoading = false;
        let currentRealizedProfitDetail = null;
        let realizedProfitTaxEstimate = null;
        let realizedProfitTaxPopoverOpen = false;
        let realizedProfitBuyPage = 1;
        let realizedProfitSellPage = 1;
        let activeRealizedPreset = 'thisMonth';
        let activeProfitModalTab = 'buy';
        let activeProfitMarketFilter = 'all';
        let activeRealizedSummaryMonth = (() => {
            const today = new Date();
            return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
        })();
        const CAPITAL_GAINS_TAX_THRESHOLD_KRW = 2500000;
        const CAPITAL_GAINS_TAX_RATE = 0.22;
        const REALIZED_PROFIT_CACHE_TTL_MS = 5 * 60 * 1000;
        const REALIZED_PROFIT_PAGE_SIZE = 10;
        let cachedTotalEvalKrw = 0;
        let cachedDomesticEvalKrw = 0;
        let cachedForeignEvalUsd = 0;
        let lastUsMarketStatus = null;
        let syncRequestInFlight = false;
        let usQuoteRequestInFlight = false;
        let lastLiveChartUpdateAt = 0;
        let usQuotePollingIntervalId = null;
        let usQuotePollingTimeoutId = null;
        let usQuotePollingActive = false;
        const US_QUOTE_POLL_INTERVAL_MS = 3000;
        const US_QUOTE_POLL_WINDOW_MS = 1 * 60 * 1000;
        const LIVE_CHART_UPDATE_MIN_INTERVAL_MS = 15000;

        const LAYOUT_STORAGE_KEY = 'dashboard_layout_mode';

        // 프리미엄 색상 팔레트
        const chartColors = [
            '#3b82f6', '#8b5cf6', '#ec4899', '#f43f5e',
            '#f97316', '#fbbf24', '#10b981', '#14b8a6', '#0ea5e9'
        ];

        // 숫자 포맷
        function formatNumber(num) {
            return num.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
        }

        function formatSignedKrw(value) {
            const amount = Number(value || 0);
            const sign = amount > 0 ? '+' : amount < 0 ? '-' : '';
            return `${sign}₩${formatNumber(Math.round(Math.abs(amount)))}`;
        }

        function formatUsd(value) {
            const amount = Number(value || 0);
            return `$${formatNumber(amount.toFixed(2))}`;
        }

        function formatJpy(value) {
            const amount = Number(value || 0);
            return `¥${formatNumber(Math.round(amount))}`;
        }

        function formatPlainKrw(value) {
            return `₩${formatNumber(Math.round(Math.abs(Number(value || 0))))}`;
        }

        function profitClassName(value) {
            const amount = Number(value || 0);
            if (amount > 0) return 'profit-plus';
            if (amount < 0) return 'profit-minus';
            return '';
        }

        function getTodayIso() {
            const now = new Date();
            const year = now.getFullYear();
            const month = String(now.getMonth() + 1).padStart(2, '0');
            const day = String(now.getDate()).padStart(2, '0');
            return `${year}-${month}-${day}`;
        }

        function toIsoDate(date) {
            const year = date.getFullYear();
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const day = String(date.getDate()).padStart(2, '0');
            return `${year}-${month}-${day}`;
        }

        function getThisMonthRange() {
            const today = new Date();
            return {
                start: toIsoDate(new Date(today.getFullYear(), today.getMonth(), 1)),
                end: getTodayIso(),
            };
        }

        function getLastMonthRange() {
            const today = new Date();
            const firstDay = new Date(today.getFullYear(), today.getMonth() - 1, 1);
            const lastDay = new Date(today.getFullYear(), today.getMonth(), 0);
            return {
                start: toIsoDate(firstDay),
                end: toIsoDate(lastDay),
            };
        }

        function getRecentMonthsRange(monthCount) {
            const today = new Date();
            return {
                start: toIsoDate(new Date(today.getFullYear(), today.getMonth() - (monthCount - 1), 1)),
                end: getTodayIso(),
            };
        }

        function formatDisplayDate(isoDate) {
            if (!isoDate) return '-';
            if (/^\d{8}$/.test(isoDate)) {
                return `${isoDate.slice(0, 4)}.${isoDate.slice(4, 6)}.${isoDate.slice(6, 8)}`;
            }
            const parts = isoDate.split('-');
            if (parts.length !== 3) return isoDate;
            return `${parts[0]}.${parts[1]}.${parts[2]}`;
        }

        function getFreshRealizedCacheEntry(cache, key) {
            const entry = cache.get(key);
            if (!entry) return null;
            if ((Date.now() - Number(entry.ts || 0)) > REALIZED_PROFIT_CACHE_TTL_MS) {
                cache.delete(key);
                return null;
            }
            return entry.data;
        }

        function setRealizedCacheEntry(cache, key, data) {
            cache.set(key, {
                ts: Date.now(),
                data,
            });
        }

        // 수익률 포맷 및 배지 생성
        function formatProfit(val, isBadge = false) {
            const num = parseFloat(val);
            const formatted = Math.abs(num).toFixed(2) + '%';
            if (num > 0) {
                return isBadge
                    ? `<span class="profit-badge bg-plus">+${formatted}</span>`
                    : `<span class="profit-plus">+${formatted}</span>`;
            }
            if (num < 0) {
                return isBadge
                    ? `<span class="profit-badge bg-minus">-${formatted}</span>`
                    : `<span class="profit-minus">-${formatted}</span>`;
            }
            return isBadge
                ? `<span class="profit-badge" style="background: rgba(255,255,255,0.1); color: #fff;">0.00%</span>`
                : `<span style="color: var(--text-sub);">0.00%</span>`;
        }

        function formatSignedPercent(value) {
            const amount = Number(value || 0);
            const sign = amount > 0 ? '+' : amount < 0 ? '-' : '';
            return `${sign}${Math.abs(amount).toFixed(2)}%`;
        }

        function normalizeTicker(value) {
            const normalized = (value || '').toString().trim().toUpperCase();
            if (normalized.length >= 6 && /^[A-Z]*\d{6}$/.test(normalized)) {
                return normalized.slice(-6);
            }
            return normalized;
        }

        function calcHoldingProfitRate(nowPrice, avgPrice) {
            const parseNum = (v) => {
                if (typeof v === 'number') return v;
                const cleaned = (v ?? '').toString().replace(/[^0-9.\-]/g, '');
                return Number(cleaned);
            };
            const avg = parseNum(avgPrice);
            const now = parseNum(nowPrice);
            if (!isFinite(avg) || avg <= 0 || !isFinite(now)) return null;
            return ((now - avg) / avg) * 100;
        }

        function formatUsMarketSessionLabel(sessionKey) {
            switch ((sessionKey || '').toString()) {
                case 'day_market':
                    return '주간거래';
                case 'premarket':
                    return '프리마켓';
                case 'regular':
                    return '정규장';
                case 'aftermarket':
                    return '애프터마켓';
                case 'closed':
                    return '휴장';
                default:
                    return '-';
            }
        }

        function renderPortfolioSummary(combinedItems) {
            const domesticItems = combinedItems.filter(item => item.type === 'KOR');
            const usItems = combinedItems.filter(item => item.type === 'USA');
            const jpItems = combinedItems.filter(item => item.type === 'JPN');

            const totalEvalKrw = combinedItems.reduce((acc, item) => acc + (item.evalAmtKrw || 0), 0);
            const totalPurchaseKrw = combinedItems.reduce((acc, item) => acc + (item.purchaseAmtKrw || 0), 0);
            const domesticEvalKrw = domesticItems.reduce((acc, item) => acc + ((item.qty || 0) * (item.now_price || 0)), 0);
            const usEvalUsd = usItems.reduce((acc, item) => acc + ((item.qty || 0) * (item.now_price || 0)), 0);
            const jpEvalUsd = jpItems.reduce((acc, item) => {
                const exrt = item.bass_exrt || 905;
                const evalKrw = (item.qty || 0) * (item.now_price || 0) * (exrt / 100);
                return acc + (cachedExrt > 0 ? (evalKrw / cachedExrt) : 0);
            }, 0);

            cachedTotalEvalKrw = totalEvalKrw;
            cachedDomesticEvalKrw = domesticEvalKrw;
            cachedForeignEvalUsd = usEvalUsd + jpEvalUsd;

            const totalProfitAmt = totalEvalKrw - totalPurchaseKrw;
            const totalProfitRt = totalPurchaseKrw > 0 ? (totalProfitAmt / totalPurchaseKrw) * 100 : 0;
            const profitSign = totalProfitAmt > 0 ? '+' : '';
            const profitClass = totalProfitAmt > 0 ? 'profit-plus' : (totalProfitAmt < 0 ? 'profit-minus' : '');

            document.getElementById('val_total_assets').innerText = `₩${formatNumber(Math.round(totalEvalKrw))}`;
            document.getElementById('val_purchase').innerText = `₩${formatNumber(Math.round(totalPurchaseKrw))}`;
            renderAssetCardValues();

            const profitElem = document.getElementById('val_total_profit');
            profitElem.innerText = `${profitSign}₩${formatNumber(Math.round(Math.abs(totalProfitAmt)))}`;
            profitElem.className = `summary-value ${profitClass}`;
            document.getElementById('val_total_rt').innerHTML = formatProfit(totalProfitRt, true);
        }

        function rebuildPortfolioView(combinedItems, usMarketStatus) {
            lastUsMarketStatus = usMarketStatus || lastUsMarketStatus;
            renderPortfolioSummary(combinedItems);
            cachedItems = [...combinedItems].sort((a, b) => b.evalAmtKrw - a.evalAmtKrw);
            renderTable();
            updateChart(cachedItems, cachedTotalEvalKrw);
        }

        function stopUsQuotePolling() {
            if (usQuotePollingIntervalId) {
                clearInterval(usQuotePollingIntervalId);
                usQuotePollingIntervalId = null;
            }
            if (usQuotePollingTimeoutId) {
                clearTimeout(usQuotePollingTimeoutId);
                usQuotePollingTimeoutId = null;
            }
            usQuoteRequestInFlight = false;
            usQuotePollingActive = false;
        }

        function hasMeaningfulUsQuoteChange(currentItem, updatedItem) {
            return Number(currentItem.now_price || 0) !== Number(updatedItem.now_price || 0)
                || Number(currentItem.bid || 0) !== Number(updatedItem.bid || 0)
                || Number(currentItem.ask || 0) !== Number(updatedItem.ask || 0)
                || Boolean(currentItem.quote_stale) !== Boolean(updatedItem.quote_stale)
                || String(currentItem.quote_ts || '') !== String(updatedItem.quote_ts || '');
        }

        function buildHoldingRowHtml(item) {
            let badgeHtml = '';
            let quoteBadgeHtml = '';
            let pricePrefix = '';
            let dp = 0;

            let displayAvg = item.avg_price;
            let displayNow = item.now_price;
            let totalValLocal = item.now_price * item.qty;
            let profitPct = calcHoldingProfitRate(item.now_price, item.avg_price);
            if (profitPct === null) profitPct = 0;

            if (item.type === 'KOR') {
                badgeHtml = `<span class="badge badge-kor">KOR</span>`;
                pricePrefix = '₩';
                dp = 0;
            } else if (item.type === 'USA') {
                badgeHtml = `<span class="badge badge-usa">USA</span>`;
                if (item.quote_stale !== false) {
                    quoteBadgeHtml = `<span class="badge badge-quote-fallback">종가</span>`;
                }
                if (currentCurrencyMode === 'krw') {
                    pricePrefix = '₩';
                    dp = 0;
                    let exrt = item.bass_exrt || 1350;
                    displayAvg = item.avg_price * exrt;
                    displayNow = item.now_price * exrt;
                    totalValLocal = item.now_price * item.qty * exrt;
                } else {
                    pricePrefix = '$';
                    dp = 2;
                }
            } else if (item.type === 'JPN') {
                badgeHtml = `<span class="badge badge-jpn">JPN</span>`;
                if (currentCurrencyMode === 'krw') {
                    pricePrefix = '₩';
                    dp = 0;
                    let exrt = item.bass_exrt || 905;
                    displayAvg = item.avg_price * (exrt / 100);
                    displayNow = item.now_price * (exrt / 100);
                    totalValLocal = item.now_price * item.qty * (exrt / 100);
                } else {
                    pricePrefix = '¥';
                    dp = 2;
                }
            }

            let profitAmtLocal = totalValLocal - (displayAvg * item.qty);
            let profitAmtSign = profitAmtLocal > 0 ? '+' : (profitAmtLocal < 0 ? '-' : '');
            let profitAmtClass = profitAmtLocal > 0 ? 'profit-plus' : (profitAmtLocal < 0 ? 'profit-minus' : '');
            let profitAmtFormatted = `${profitAmtSign}${pricePrefix}${formatNumber(Math.abs(profitAmtLocal).toFixed(dp))}`;

            return `
                <tr data-ticker="${normalizeTicker(item.ticker)}" onclick="fetchAssetInsight('${item.ticker}', '${item.type}')" style="cursor: pointer; transition: background 0.2s;">
                    <td>
                        <div class="ticker-cell">
                            <span class="ticker-name">${item.name}</span>
                            <div class="ticker-meta-row">
                                ${badgeHtml}
                                ${quoteBadgeHtml}
                                ${item.ticker ? `<span class="ticker-symbol">${item.ticker}</span>` : ''}
                            </div>
                        </div>
                    </td>
                    <td>${formatNumber(item.qty)}</td>
                    <td class="js-eval" style="color:#fff; font-weight:600;">${pricePrefix}${formatNumber(totalValLocal.toFixed(dp))}</td>
                    <td style="color:var(--text-sub);">${pricePrefix}${formatNumber(displayAvg.toFixed(dp))}</td>
                    <td class="js-now">${pricePrefix}${formatNumber(displayNow.toFixed(dp))}</td>
                    <td class="holding-profit-amount ${profitAmtClass}">${profitAmtFormatted}</td>
                    <td class="js-profit">${formatProfit(profitPct, false)}</td>
                </tr>
            `;
        }

        function updateUsRowsInTable(changedTickers) {
            changedTickers.forEach((ticker) => {
                const item = cachedItems.find((entry) => entry.type === 'USA' && normalizeTicker(entry.ticker) === ticker);
                if (!item) return;
                const row = document.querySelector(`#all_list tr[data-ticker="${ticker}"]`);
                if (row) {
                    row.outerHTML = buildHoldingRowHtml(item).trim();
                }
            });
        }

        function maybeRefreshLiveChart() {
            const now = Date.now();
            if ((now - lastLiveChartUpdateAt) < LIVE_CHART_UPDATE_MIN_INTERVAL_MS) {
                return;
            }
            lastLiveChartUpdateAt = now;
            updateChart(cachedItems, cachedTotalEvalKrw);
        }

        function mergeUsQuoteItems(usItems, usMarketStatus) {
            lastUsMarketStatus = usMarketStatus || lastUsMarketStatus;
            if (!Array.isArray(usItems) || usItems.length === 0) {
                if (usMarketStatus?.session !== 'day_market') {
                    stopUsQuotePolling();
                }
                return;
            }

            const usMap = new Map(usItems.map(item => [normalizeTicker(item.ticker), item]));
            const changedTickers = [];
            const mergedItems = cachedItems.map(item => {
                if (item.type !== 'USA') {
                    return item;
                }
                const updated = usMap.get(normalizeTicker(item.ticker));
                if (!updated) {
                    return item;
                }
                const exrt = updated.bass_exrt || item.bass_exrt || cachedExrt || 1350;
                const merged = { ...item, ...updated, type: 'USA' };
                merged.bass_exrt = exrt;
                merged.evalAmtKrw = (merged.qty || 0) * (merged.now_price || 0) * exrt;
                merged.purchaseAmtKrw = (merged.qty || 0) * (merged.avg_price || 0) * exrt;
                merged.profit_rt = calcHoldingProfitRate(merged.now_price, merged.avg_price) ?? 0;
                if (hasMeaningfulUsQuoteChange(item, merged)) {
                    changedTickers.push(normalizeTicker(item.ticker));
                }
                return merged;
            });

            cachedItems = mergedItems;
            if (changedTickers.length > 0) {
                renderPortfolioSummary(cachedItems);
                updateUsRowsInTable(changedTickers);
                maybeRefreshLiveChart();
            }

            if (usMarketStatus?.session !== 'day_market') {
                stopUsQuotePolling();
            }
        }

        async function pollUsQuotesOnce() {
            if (usQuoteRequestInFlight || document.hidden) {
                return;
            }
            usQuoteRequestInFlight = true;
            try {
                const res = await fetch('/api/us-quotes');
                if (res.status === 401) {
                    stopUsQuotePolling();
                    window.location.href = '/login';
                    return;
                }
                const data = await res.json();
                if (data.status === 'success') {
                    mergeUsQuoteItems(data?.overseas?.us_items || [], data?.overseas?.us_market_status || null);
                }
            } catch (err) {
                console.error('US quote polling failed', err);
            } finally {
                usQuoteRequestInFlight = false;
            }
        }

        function startUsQuotePollingWindow(usMarketStatus) {
            stopUsQuotePolling();
            lastUsMarketStatus = usMarketStatus || lastUsMarketStatus;
            if (document.hidden || usMarketStatus?.session !== 'day_market') {
                return;
            }
            usQuotePollingActive = true;
            usQuotePollingIntervalId = setInterval(() => {
                pollUsQuotesOnce();
            }, US_QUOTE_POLL_INTERVAL_MS);
            usQuotePollingTimeoutId = setTimeout(() => {
                stopUsQuotePolling();
            }, US_QUOTE_POLL_WINDOW_MS);
            pollUsQuotesOnce();
        }

        function updateLayoutModeUI() {
            const btnMode1 = document.getElementById('layoutModeBtn1');
            const btnMode2 = document.getElementById('layoutModeBtn2');
            if (!btnMode1 || !btnMode2) return;

            btnMode1.classList.toggle('active', currentLayoutMode === 'mode1');
            btnMode2.classList.toggle('active', currentLayoutMode === 'mode2');
        }

        function setRightPaneState(state) {
            rightPaneState = state === 'insight' ? 'insight' : 'widgets';
            const widgetsPane = document.getElementById('rightWidgetsStack');
            const insightPane = document.getElementById('rightInsightPanel');
            if (!widgetsPane || !insightPane) return;

            if (currentLayoutMode === 'mode1') {
                widgetsPane.classList.remove('is-hidden');
                insightPane.classList.remove('is-hidden');
                return;
            }

            if (rightPaneState === 'insight') {
                widgetsPane.classList.add('is-hidden');
                insightPane.classList.remove('is-hidden');
            } else {
                widgetsPane.classList.remove('is-hidden');
                insightPane.classList.add('is-hidden');
            }
        }

        function applyLayoutMode(mode, persist = true) {
            currentLayoutMode = mode === 'mode1' ? 'mode1' : 'mode2';
            document.body.classList.remove('layout-mode-1', 'layout-mode-2');
            document.body.classList.add(currentLayoutMode === 'mode1' ? 'layout-mode-1' : 'layout-mode-2');

            setRightPaneState(rightPaneState);
            updateLayoutModeUI();

            if (persist) {
                localStorage.setItem(LAYOUT_STORAGE_KEY, currentLayoutMode);
            }

            if (myChart) {
                setTimeout(() => {
                    try {
                        myChart.resize();
                        myChart.update('none');
                    } catch (err) {
                        console.error('Chart resize failed after layout mode switch', err);
                    }
                }, 50);
            }
        }

        function openInsightPane() {
            if (currentLayoutMode === 'mode2') {
                setRightPaneState('insight');
            }
        }

        function closeInsightPane() {
            if (currentLayoutMode === 'mode2') {
                setRightPaneState('widgets');
            }
        }

        // 환율 모드 토글
        function toggleCurrency() {
            const toggle = document.getElementById('currencyToggle');
            const optLocal = document.getElementById('opt-local');
            const optKrw = document.getElementById('opt-krw');

            if (currentCurrencyMode === 'local') {
                currentCurrencyMode = 'krw';
                toggle.classList.add('krw-mode');
                optLocal.classList.remove('active');
                optKrw.classList.add('active');
            } else {
                currentCurrencyMode = 'local';
                toggle.classList.remove('krw-mode');
                optKrw.classList.remove('active');
                optLocal.classList.add('active');
            }
            renderTable();
        }

        // 테이블 렌더링
        function renderTable() {
            document.getElementById('all_list').innerHTML = cachedItems.map((item) => buildHoldingRowHtml(item)).join('');
        }


        // 차트 및 비중 리스트 업데이트
        function updateChart(items, totalEval) {
            const ctx = document.getElementById('portfolioChart').getContext('2d');

            let labels = [];
            let data = [];
            let bgColors = [];

            let listHtml = '';

            items.forEach((i, idx) => {
                labels.push(i.ticker || i.name);
                data.push(i.evalAmtKrw);

                const color = chartColors[idx % chartColors.length];
                bgColors.push(color);

                const percent = ((i.evalAmtKrw / totalEval) * 100).toFixed(1);
                listHtml += `
                    <div class="alloc-item">
                        <div class="alloc-info">
                            <div class="alloc-color" style="color: ${color}; background-color: ${color};"></div>
                            <div class="alloc-name">${i.name}</div>
                        </div>
                        <div class="alloc-percent">${percent}%</div>
                    </div>
                `;
            });

            const defaultCenterTicker = '';
            document.getElementById('allocation_list').innerHTML = listHtml;
            document.getElementById('chart_center_val').innerText = defaultCenterTicker;

            if (myChart) {
                myChart.data.labels = labels;
                myChart.data.datasets[0].data = data;
                myChart.data.datasets[0].backgroundColor = bgColors;
                myChart.update();
            } else {
                Chart.defaults.color = '#9ca3af';
                Chart.defaults.font.family = "'Pretendard', sans-serif";

                myChart = new Chart(ctx, {
                    type: 'doughnut',
                    data: {
                        labels: labels,
                        datasets: [{
                            data: data,
                            backgroundColor: bgColors,
                            borderWidth: 0,
                            hoverOffset: 10,
                            borderRadius: 4
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        cutout: '80%', /* 도넛의 얇기를 조절해 세련되게 */
                        layout: { padding: 10 },
                        plugins: {
                            legend: { display: false },
                            tooltip: {
                                backgroundColor: 'rgba(15, 23, 42, 0.9)',
                                titleColor: '#fff',
                                bodyColor: '#fff',
                                bodyFont: { size: 14, weight: 'bold' },
                                borderColor: 'rgba(255,255,255,0.1)',
                                borderWidth: 1,
                                padding: 16,
                                cornerRadius: 12,
                                displayColors: true,
                                callbacks: {
                                    label: function (context) {
                                        let value = context.raw;
                                        let percent = ((value / totalEval) * 100).toFixed(1);
                                        return ` ₩${formatNumber(Math.round(value))} (${percent}%)`;
                                    }
                                }
                            }
                        },
                        onHover: (event, chartElement) => {
                            // 마우스 호버 시 중앙 텍스트 변경
                            const centerText = document.getElementById('chart_center_val');
                            if (chartElement.length > 0) {
                                const index = chartElement[0].index;
                                centerText.innerText = items[index].ticker || items[index].name;
                                centerText.style.color = bgColors[index];
                            } else {
                                centerText.innerText = defaultCenterTicker;
                                centerText.style.color = "#fff";
                            }
                        }
                    }
                });

                // 초기 중앙 텍스트 세팅
                document.getElementById('chart_center_val').innerText = defaultCenterTicker;
            }
        }

        // 메인 데이터 동기화 함수
        async function syncData(manualTrigger = false) {
            if (syncRequestInFlight) {
                return null;
            }
            syncRequestInFlight = true;
            const loading = document.getElementById('loading');
            loading.classList.add('active');

            try {
                const res = await fetch('/api/sync');

                if (res.status === 401) {
                    window.location.href = '/login';
                    return;
                }

                const data = await res.json();

                if (data.status === "success") {
                    const krCash = data?.domestic?.summary?.cash_balance || 0;
                    const usCash = data?.overseas?.us_summary?.usd_cash_balance || 0;
                    const jpCash = data?.overseas?.jp_summary?.jpy_cash_balance || 0;
                    let exrt = data?.overseas?.us_summary?.usd_exrt || 0;
                    if (exrt <= 0 && data?.overseas?.us_items && data.overseas.us_items.length > 0) {
                        exrt = data.overseas.us_items[0].bass_exrt || 1350;
                    }
                    if (exrt <= 0) {
                        exrt = 1350;
                    }
                    let jpExrt = data?.overseas?.jp_summary?.jpy_exrt || 0;
                    if (jpExrt <= 0 && data?.overseas?.jp_items && data.overseas.jp_items.length > 0) {
                        jpExrt = data.overseas.jp_items[0].bass_exrt || 905;
                    }
                    const hasJpyExrt = jpExrt > 0;
                    cachedKrCash = krCash;
                    cachedUsdCash = usCash;
                    cachedJpyCash = jpCash;
                    cachedExrt = exrt;
                    cachedJpExrt = jpExrt;
                    const totalCashKrw = krCash + (usCash * exrt) + (hasJpyExrt ? (jpCash * (jpExrt / 100)) : 0);

                    document.getElementById('val_total_cash').innerText = formatPlainKrw(totalCashKrw);
                    renderCashCardValues();
                    const now = new Date();
                    const hh = String(now.getHours()).padStart(2, '0');
                    const mm = String(now.getMinutes()).padStart(2, '0');
                    const ss = String(now.getSeconds()).padStart(2, '0');
                    const lastSync = document.getElementById('last-sync-text');
                    if (lastSync) lastSync.innerText = `마지막 동기화: ${hh}:${mm}:${ss}`;

                    // 리스트 취합 및 원화 평가/매입금액 계산
                    let combinedItems = [];

                    const domesticItems = Array.isArray(data?.domestic?.items) ? data.domestic.items : [];
                    const usItems = Array.isArray(data?.overseas?.us_items) ? data.overseas.us_items : [];
                    const jpItems = Array.isArray(data?.overseas?.jp_items) ? data.overseas.jp_items : [];
                    const usMarketStatus = data?.overseas?.us_market_status || null;
                    lastUsMarketStatus = usMarketStatus;

                    domesticItems.forEach(i => {
                        const evalAmt = i.qty * i.now_price;
                        const purchaseAmt = i.qty * i.avg_price;
                        const holdingRt = calcHoldingProfitRate(i.now_price, i.avg_price);
                        combinedItems.push({ ...i, type: 'KOR', evalAmtKrw: evalAmt, purchaseAmtKrw: purchaseAmt, profit_rt: holdingRt ?? 0 });
                    });

                    usItems.forEach(i => {
                        let exrt = i.bass_exrt || 1350;
                        const evalAmt = i.qty * i.now_price * exrt;
                        const purchaseAmt = i.qty * i.avg_price * exrt;
                        const holdingRt = calcHoldingProfitRate(i.now_price, i.avg_price);
                        combinedItems.push({ ...i, type: 'USA', evalAmtKrw: evalAmt, purchaseAmtKrw: purchaseAmt, profit_rt: holdingRt ?? 0 });
                    });

                    jpItems.forEach(i => {
                        let exrt = i.bass_exrt || 905;
                        const evalAmt = i.qty * i.now_price * (exrt / 100);
                        const purchaseAmt = i.qty * i.avg_price * (exrt / 100);
                        const holdingRt = calcHoldingProfitRate(i.now_price, i.avg_price);
                        combinedItems.push({ ...i, type: 'JPN', evalAmtKrw: evalAmt, purchaseAmtKrw: purchaseAmt, profit_rt: holdingRt ?? 0 });
                    });

                    rebuildPortfolioView(combinedItems, usMarketStatus);

                    if (usMarketStatus?.session === 'day_market') {
                        startUsQuotePollingWindow(usMarketStatus);
                    } else if (manualTrigger) {
                        stopUsQuotePolling();
                    }

                    if (manualTrigger) {
                        fetchRealizedProfitSummary(true);
                        const modal = document.getElementById('realizedProfitModal');
                        if (modal && modal.classList.contains('active')) {
                            const start = document.getElementById('realizedProfitStart')?.value;
                            const end = document.getElementById('realizedProfitEnd')?.value;
                            if (start && end) {
                                loadRealizedProfitDetail(start, end, true);
                            }
                        }
                    }

                    // Static mode only.
                    return data;
                }
            } catch (err) {
                console.error(err);
                alert("데이터를 불러오는 중 오류가 발생했습니다.");
                return null;
            } finally {
                syncRequestInFlight = false;
                loading.classList.remove('active');
            }
        }

        function renderRealizedProfitSummary(summaryPayload) {
            const valueEl = document.getElementById('val_realized_profit_month');
            const subEl = document.getElementById('val_realized_profit_month_sub');
            if (!valueEl || !subEl) return;

            renderRealizedProfitMonthNav();

            if (!summaryPayload || summaryPayload.status !== 'success') {
                valueEl.innerText = '조회 실패';
                valueEl.className = 'summary-value';
                subEl.innerText = '실현 손익을 가져오지 못했습니다';
                return;
            }

            const total = Number(summaryPayload.summary?.total_realized_profit_krw || 0);
            valueEl.innerText = formatSignedKrw(total);
            valueEl.className = `summary-value ${profitClassName(total)}`.trim();
            subEl.innerText = `${formatDisplayDate(summaryPayload.period.start)} ~ ${formatDisplayDate(summaryPayload.period.end)} 누적`;
        }

        function getTodayMonthKey() {
            const today = new Date();
            return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
        }

        function formatSummaryMonthLabel(monthKey) {
            if (!monthKey || !/^\d{4}-\d{2}$/.test(monthKey)) return '';
            return monthKey.replace('-', '.');
        }

        function shiftSummaryMonth(monthKey, offset) {
            const [year, month] = monthKey.split('-').map(Number);
            const shifted = new Date(year, month - 1 + offset, 1);
            return `${shifted.getFullYear()}-${String(shifted.getMonth() + 1).padStart(2, '0')}`;
        }

        function renderRealizedProfitMonthNav() {
            const labelEl = document.getElementById('val_realized_profit_month_label');
            const nextBtn = document.getElementById('realizedProfitNextMonthBtn');
            if (labelEl) {
                labelEl.innerText = formatSummaryMonthLabel(activeRealizedSummaryMonth);
            }
            if (nextBtn) {
                nextBtn.disabled = activeRealizedSummaryMonth >= getTodayMonthKey();
            }
        }

        function changeRealizedProfitMonth(offset, event) {
            if (event) {
                event.preventDefault();
                event.stopPropagation();
            }
            const nextMonth = shiftSummaryMonth(activeRealizedSummaryMonth, offset);
            if (nextMonth > getTodayMonthKey()) {
                return;
            }
            activeRealizedSummaryMonth = nextMonth;
            fetchRealizedProfitSummary();
        }

        async function fetchRealizedProfitSummary(force = false) {
            const month = activeRealizedSummaryMonth;
            const cached = getFreshRealizedCacheEntry(realizedProfitSummaryCache, month);
            if (realizedProfitSummaryLoading) return cached || null;
            if (cached && !force) {
                renderRealizedProfitSummary(cached);
                return cached;
            }

            realizedProfitSummaryLoading = true;
            document.getElementById('val_realized_profit_month').innerText = '조회 중...';
            document.getElementById('val_realized_profit_month').className = 'summary-value';
            document.getElementById('val_realized_profit_month_sub').innerText = '실현 손익을 불러오는 중입니다';
            renderRealizedProfitMonthNav();

            try {
                const res = await fetch(`/api/realized-profit/summary?month=${month}`);
                if (res.status === 401) {
                    window.location.href = '/login';
                    return null;
                }
                const data = await res.json();
                setRealizedCacheEntry(realizedProfitSummaryCache, month, data);
                renderRealizedProfitSummary(data);
                return data;
            } catch (err) {
                console.error('fetchRealizedProfitSummary error', err);
                const errorPayload = { status: 'error' };
                setRealizedCacheEntry(realizedProfitSummaryCache, month, errorPayload);
                renderRealizedProfitSummary(errorPayload);
                return errorPayload;
            } finally {
                realizedProfitSummaryLoading = false;
            }
        }

        function setProfitCardFace(showRealized) {
            const card = document.getElementById('profitSummaryCard');
            if (!card) return;
            profitCardShowingRealized = !!showRealized;
            card.classList.toggle('is-flipped', profitCardShowingRealized);
            if (profitCardShowingRealized) {
                const cached = getFreshRealizedCacheEntry(realizedProfitSummaryCache, activeRealizedSummaryMonth);
                if (!cached) {
                    fetchRealizedProfitSummary();
                } else if (cached.status === 'error') {
                    fetchRealizedProfitSummary(true);
                } else {
                    renderRealizedProfitSummary(cached);
                }
            }
        }

        function toggleProfitCard() {
            setProfitCardFace(!profitCardShowingRealized);
        }

        function handleProfitCardKeydown(event) {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                toggleProfitCard();
            }
        }

        function renderCashCardValues() {
            const krwEl = document.getElementById('val_krw_cash');
            const usdEl = document.getElementById('val_usd_cash');
            const jpyEl = document.getElementById('val_jpy_cash');
            if (krwEl) {
                krwEl.innerText = formatPlainKrw(cachedKrCash);
            }
            if (usdEl) {
                usdEl.innerText = formatUsd(cachedUsdCash);
            }
            if (jpyEl) {
                jpyEl.innerText = formatJpy(cachedJpyCash);
            }
        }

        function renderAssetCardValues() {
            const krwEl = document.getElementById('val_total_assets_krw');
            const usdEl = document.getElementById('val_total_assets_usd');
            if (krwEl) {
                krwEl.innerText = `₩${formatNumber(Math.round(cachedDomesticEvalKrw || 0))}`;
            }
            if (usdEl) {
                usdEl.innerText = formatUsd(cachedForeignEvalUsd);
            }
        }

        function setAssetCardFace(showForeign) {
            const card = document.getElementById('assetSummaryCard');
            if (!card) return;
            assetCardShowingForeign = !!showForeign;
            card.classList.toggle('is-flipped', assetCardShowingForeign);
            if (assetCardShowingForeign) {
                renderAssetCardValues();
            }
        }

        function toggleAssetCard() {
            setAssetCardFace(!assetCardShowingForeign);
        }

        function handleAssetCardKeydown(event) {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                toggleAssetCard();
            }
        }

        function setCashCardFace(showForeign) {
            const card = document.getElementById('cashSummaryCard');
            if (!card) return;
            cashCardShowingForeign = !!showForeign;
            card.classList.toggle('is-flipped', cashCardShowingForeign);
            card.setAttribute('aria-pressed', cashCardShowingForeign ? 'true' : 'false');
            if (cashCardShowingForeign) {
                renderCashCardValues();
            }
        }

        function toggleCashCard() {
            setCashCardFace(!cashCardShowingForeign);
        }

        function handleCashCardKeydown(event) {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                toggleCashCard();
            }
        }

        function setRealizedPreset(preset, shouldFetch = false) {
            activeRealizedPreset = preset;
            realizedProfitBuyPage = 1;
            realizedProfitSellPage = 1;
            const presetButtons = document.querySelectorAll('[data-profit-preset]');
            presetButtons.forEach((button) => {
                button.classList.toggle('active', button.dataset.profitPreset === preset);
            });

            let range;
            if (preset === 'lastMonth') range = getLastMonthRange();
            else if (preset === 'threeMonths') range = getRecentMonthsRange(3);
            else if (preset === 'sixMonths') range = getRecentMonthsRange(6);
            else range = getThisMonthRange();

            document.getElementById('realizedProfitStart').value = range.start;
            document.getElementById('realizedProfitEnd').value = range.end;

            if (shouldFetch) {
                loadRealizedProfitDetail(range.start, range.end);
            }
        }

        function renderCapitalGainsTaxEstimate() {
            const valueEl = document.getElementById('realizedProfitTaxPopupValue');
            const noteEl = document.getElementById('realizedProfitTaxPopupNote');
            if (!valueEl || !noteEl) return;

            if (!realizedProfitTaxEstimate) {
                valueEl.innerText = '-';
                valueEl.className = 'profit-tax-popover-value';
                noteEl.innerText = `총 실현 손익이 ${formatPlainKrw(CAPITAL_GAINS_TAX_THRESHOLD_KRW)}를 넘는지 확인한 뒤 계산합니다.`;
                return;
            }

            valueEl.innerText = formatPlainKrw(realizedProfitTaxEstimate.tax_krw);
            valueEl.className = `profit-tax-popover-value ${profitClassName(-Math.abs(realizedProfitTaxEstimate.tax_krw))}`.trim();

            if (realizedProfitTaxEstimate.tax_krw > 0) {
                noteEl.innerText = `${realizedProfitTaxEstimate.year}년 누적 실현 손익 ${formatSignedKrw(realizedProfitTaxEstimate.total_profit_krw)} × 22% 기준 추정치입니다.`;
            } else {
                noteEl.innerText = `${realizedProfitTaxEstimate.year}년 누적 실현 손익이 ${formatPlainKrw(CAPITAL_GAINS_TAX_THRESHOLD_KRW)} 이하라서 양도소득세를 0원으로 표시합니다.`;
            }
        }

        function setCapitalGainsTaxPopover(open) {
            realizedProfitTaxPopoverOpen = !!open;
            const popover = document.getElementById('realizedProfitTaxPopover');
            if (!popover) return;
            popover.classList.toggle('active', realizedProfitTaxPopoverOpen);
        }

        function paginateTrades(trades, page) {
            const totalPages = Math.max(1, Math.ceil((trades?.length || 0) / REALIZED_PROFIT_PAGE_SIZE));
            const safePage = Math.min(Math.max(1, page || 1), totalPages);
            const startIndex = (safePage - 1) * REALIZED_PROFIT_PAGE_SIZE;
            return {
                page: safePage,
                totalPages,
                items: (trades || []).slice(startIndex, startIndex + REALIZED_PROFIT_PAGE_SIZE),
            };
        }

        function renderTradePagination(prefix, totalItems, page, totalPages) {
            const root = document.getElementById(`${prefix}HistoryPagination`);
            const label = document.getElementById(`${prefix}HistoryPageLabel`);
            const prev = document.getElementById(`${prefix}HistoryPrevPage`);
            const next = document.getElementById(`${prefix}HistoryNextPage`);
            if (!root || !label || !prev || !next) return;

            if (!totalItems || totalPages <= 1) {
                root.classList.add('hidden');
                label.innerText = '';
                prev.disabled = true;
                next.disabled = true;
                return;
            }

            root.classList.remove('hidden');
            label.innerText = `${page} / ${totalPages}`;
            prev.disabled = page <= 1;
            next.disabled = page >= totalPages;
        }

        function changeTradeHistoryPage(kind, delta) {
            if (kind === 'sell') {
                realizedProfitSellPage = Math.max(1, realizedProfitSellPage + delta);
            } else {
                realizedProfitBuyPage = Math.max(1, realizedProfitBuyPage + delta);
            }
            renderRealizedProfitDetail(currentRealizedProfitDetail);
        }

        async function getRealizedProfitDetailPayload(start, end, force = false) {
            const cacheKey = `${start}:${end}`;
            const cached = getFreshRealizedCacheEntry(realizedProfitDetailCache, cacheKey);
            if (!force && cached) {
                return cached;
            }
            const res = await fetch(`/api/realized-profit/detail?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`);
            if (res.status === 401) {
                window.location.href = '/login';
                return null;
            }
            const data = await res.json();
            setRealizedCacheEntry(realizedProfitDetailCache, cacheKey, data);
            return data;
        }

        async function calculateCapitalGainsTax() {
            if (!currentRealizedProfitDetail || currentRealizedProfitDetail.status !== 'success') {
                realizedProfitTaxEstimate = null;
                renderCapitalGainsTaxEstimate();
                setCapitalGainsTaxPopover(true);
                return;
            }

            const valueEl = document.getElementById('realizedProfitTaxPopupValue');
            const noteEl = document.getElementById('realizedProfitTaxPopupNote');
            const endInput = document.getElementById('realizedProfitEnd')?.value || '';
            const startInput = document.getElementById('realizedProfitStart')?.value || '';
            const baseDate = endInput || startInput || getTodayIso();
            const year = String(baseDate).slice(0, 4);
            const currentYear = String(new Date().getFullYear());
            const yearStart = `${year}-01-01`;
            const yearEnd = year === currentYear ? getTodayIso() : `${year}-12-31`;
            setCapitalGainsTaxPopover(true);

            if (valueEl && noteEl) {
                valueEl.innerText = '계산 중...';
                valueEl.className = 'profit-tax-popover-value';
                noteEl.innerText = `${year}년 전체 실현 손익을 불러오는 중입니다.`;
            }

            try {
                const yearPayload = await getRealizedProfitDetailPayload(yearStart, yearEnd);
                if (!yearPayload || yearPayload.status !== 'success') {
                    realizedProfitTaxEstimate = null;
                    renderCapitalGainsTaxEstimate();
                    if (noteEl) {
                        noteEl.innerText = `${year}년 양도소득세 계산에 필요한 데이터를 불러오지 못했습니다.`;
                    }
                    return;
                }

                const totalProfit = Number(yearPayload.summary?.total_realized_profit_krw || 0);
                const taxable = totalProfit > CAPITAL_GAINS_TAX_THRESHOLD_KRW;
                realizedProfitTaxEstimate = {
                    year,
                    total_profit_krw: totalProfit,
                    tax_krw: taxable ? Math.round(totalProfit * CAPITAL_GAINS_TAX_RATE) : 0,
                };
                renderCapitalGainsTaxEstimate();
            } catch (err) {
                console.error('calculateCapitalGainsTax error', err);
                realizedProfitTaxEstimate = null;
                renderCapitalGainsTaxEstimate();
                if (noteEl) {
                    noteEl.innerText = `${year}년 양도소득세 계산 중 오류가 발생했습니다.`;
                }
            }
        }

        function renderRealizedProfitDetail(detailPayload) {
            const totalEl = document.getElementById('realizedProfitTotal');
            const domesticEl = document.getElementById('realizedProfitDomestic');
            const overseasEl = document.getElementById('realizedProfitOverseas');
            const rateEl = document.getElementById('realizedProfitRate');
            const captionEl = document.getElementById('realizedProfitPeriodLabel');
            const buyRowsEl = document.getElementById('buyHistoryRows');
            const buyEmptyEl = document.getElementById('buyHistoryEmpty');
            const sellRowsEl = document.getElementById('sellHistoryRows');
            const sellEmptyEl = document.getElementById('sellHistoryEmpty');
            currentRealizedProfitDetail = detailPayload;
            realizedProfitTaxEstimate = null;
            renderCapitalGainsTaxEstimate();
            setCapitalGainsTaxPopover(false);

            if (!detailPayload || detailPayload.status !== 'success') {
                totalEl.innerText = '조회 실패';
                totalEl.className = 'profit-stat-value';
                domesticEl.innerText = '-';
                overseasEl.innerText = '-';
                rateEl.innerText = '-';
                rateEl.className = 'profit-stat-value';
                captionEl.innerText = '기간 정보를 불러오지 못했습니다.';
                buyRowsEl.innerHTML = '';
                sellRowsEl.innerHTML = '';
                buyEmptyEl.classList.add('active');
                renderTradePagination('buy', 0, 1, 1);
                sellEmptyEl.classList.add('active');
                renderTradePagination('sell', 0, 1, 1);
                buyEmptyEl.innerText = '매수 거래내역을 불러오지 못했습니다.';
                sellEmptyEl.innerText = '매도 거래내역을 불러오지 못했습니다.';
                return;
            }

            const summary = detailPayload.summary || {};
            const total = Number(summary.total_realized_profit_krw || 0);
            const domestic = Number(summary.domestic_realized_profit_krw || 0);
            const overseas = Number(summary.overseas_realized_profit_krw || 0);
            const totalRate = Number(summary.total_realized_return_rate || 0);

            totalEl.innerText = formatSignedKrw(total);
            totalEl.className = `profit-stat-value ${profitClassName(total)}`.trim();
            domesticEl.innerText = formatSignedKrw(domestic);
            domesticEl.className = `profit-stat-value ${profitClassName(domestic)}`.trim();
            overseasEl.innerText = formatSignedKrw(overseas);
            overseasEl.className = `profit-stat-value ${profitClassName(overseas)}`.trim();
            rateEl.innerText = formatSignedPercent(totalRate);
            rateEl.className = `profit-stat-value ${profitClassName(totalRate)}`.trim();
            captionEl.innerText = `${formatDisplayDate(detailPayload.period.start)} ~ ${formatDisplayDate(detailPayload.period.end)}`;

            const trades = Array.isArray(detailPayload.trades) ? detailPayload.trades : [];
            const marketFilteredTrades = trades.filter((trade) => {
                if (activeProfitMarketFilter === 'domestic') return trade.market === 'KOR';
                if (activeProfitMarketFilter === 'overseas') return trade.market !== 'KOR';
                return true;
            });
            const buyTrades = marketFilteredTrades.filter((trade) => trade.side === '매수');
            const sellTrades = marketFilteredTrades.filter((trade) => trade.side === '매도');
            const marketLabel = activeProfitMarketFilter === 'domestic' ? '국내' : activeProfitMarketFilter === 'overseas' ? '해외' : '선택한 조건';

            const buyPageData = paginateTrades(buyTrades, realizedProfitBuyPage);
            const sellPageData = paginateTrades(sellTrades, realizedProfitSellPage);
            realizedProfitBuyPage = buyPageData.page;
            realizedProfitSellPage = sellPageData.page;

            if (!buyTrades.length) {
                buyRowsEl.innerHTML = '';
                buyEmptyEl.classList.add('active');
                buyEmptyEl.innerText = `${marketLabel} 매수 거래내역이 없습니다.`;
            } else {
                buyEmptyEl.classList.remove('active');
                buyRowsEl.innerHTML = buyPageData.items.map((trade) => `
                    <tr>
                        <td>${formatDisplayDate(trade.date)}</td>
                        <td>${trade.ticker || trade.symbol || '-'}</td>
                        <td>${trade.name || trade.symbol || '-'}</td>
                        <td>${formatNumber(Number(trade.quantity || 0))}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.unit_price) : `${trade.currency || ''} ${formatNumber(Number(trade.unit_price || 0).toFixed(2))}`}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.amount) : `${trade.currency || ''} ${formatNumber(Number(trade.amount || 0).toFixed(2))}`}</td>
                    </tr>
                `).join('');
                renderTradePagination('buy', buyTrades.length, buyPageData.page, buyPageData.totalPages);
            }

            if (!sellTrades.length) {
                sellRowsEl.innerHTML = '';
                sellEmptyEl.classList.add('active');
                sellEmptyEl.innerText = `${marketLabel} 매도 거래내역이 없습니다.`;
            } else {
                sellEmptyEl.classList.remove('active');
                sellRowsEl.innerHTML = sellPageData.items.map((trade) => `
                    <tr>
                        <td>${formatDisplayDate(trade.date)}</td>
                        <td>${trade.ticker || trade.symbol || '-'}</td>
                        <td>${trade.name || trade.symbol || '-'}</td>
                        <td>${formatNumber(Number(trade.quantity || 0))}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.unit_price) : `${trade.currency || ''} ${formatNumber(Number(trade.unit_price || 0).toFixed(2))}`}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.amount) : `${trade.currency || ''} ${formatNumber(Number(trade.amount || 0).toFixed(2))}`}</td>
                        <td class="${profitClassName(trade.realized_profit_krw)}">${trade.realized_profit_krw == null ? '-' : formatSignedKrw(trade.realized_profit_krw)}</td>
                        <td class="${profitClassName(trade.realized_return_rate)}">${trade.realized_return_rate == null ? '-' : formatSignedPercent(trade.realized_return_rate)}</td>
                    </tr>
                `).join('');
                renderTradePagination('sell', sellTrades.length, sellPageData.page, sellPageData.totalPages);
            }
        }

        async function loadRealizedProfitDetail(start, end, force = false) {
            const loadingEl = document.getElementById('realizedProfitLoading');
            realizedProfitDetailLoading = true;
            loadingEl.classList.add('active');
            document.getElementById('buyHistoryEmpty').classList.remove('active');
            document.getElementById('sellHistoryEmpty').classList.remove('active');

            try {
                const data = await getRealizedProfitDetailPayload(start, end, force);
                renderRealizedProfitDetail(data);
                return data;
            } catch (err) {
                console.error('loadRealizedProfitDetail error', err);
                const errorData = { status: 'error' };
                renderRealizedProfitDetail(errorData);
                return errorData;
            } finally {
                realizedProfitDetailLoading = false;
                loadingEl.classList.remove('active');
            }
        }

        function openRealizedProfitModal(event) {
            if (event) {
                event.stopPropagation();
                event.preventDefault();
            }

            const overlay = document.getElementById('realizedProfitModal');
            overlay.classList.add('active');
            setProfitModalTab(activeProfitModalTab || 'buy');
            setProfitMarketFilter(activeProfitMarketFilter || 'all');
            if (!getFreshRealizedCacheEntry(realizedProfitSummaryCache, activeRealizedSummaryMonth)) {
                fetchRealizedProfitSummary();
            }
            setRealizedPreset(activeRealizedPreset || 'thisMonth', true);
        }

        function closeRealizedProfitModal() {
            setCapitalGainsTaxPopover(false);
            document.getElementById('realizedProfitModal').classList.remove('active');
        }

        function handleRealizedProfitOverlayClick(event) {
            if (event.target.id === 'realizedProfitModal') {
                closeRealizedProfitModal();
            }
        }

        function handleRealizedProfitModalClick(event) {
            if (!realizedProfitTaxPopoverOpen) return;
            if (event.target.closest('.profit-detail-header-actions')) return;
            setCapitalGainsTaxPopover(false);
        }

        function onRealizedRangeInputChange() {
            activeRealizedPreset = 'custom';
            const presetButtons = document.querySelectorAll('[data-profit-preset]');
            presetButtons.forEach((button) => button.classList.remove('active'));
            realizedProfitBuyPage = 1;
            realizedProfitSellPage = 1;
        }

        function setProfitModalTab(tab) {
            activeProfitModalTab = tab === 'sell' ? 'sell' : 'buy';
            if (activeProfitModalTab === 'sell') realizedProfitSellPage = 1;
            else realizedProfitBuyPage = 1;
            document.querySelectorAll('[data-profit-tab]').forEach((button) => {
                button.classList.toggle('active', button.dataset.profitTab === activeProfitModalTab);
            });
            document.getElementById('buyHistoryPanel').classList.toggle('active', activeProfitModalTab === 'buy');
            document.getElementById('sellHistoryPanel').classList.toggle('active', activeProfitModalTab === 'sell');
        }

        function setProfitMarketFilter(filter) {
            activeProfitMarketFilter = ['domestic', 'overseas'].includes(filter) ? filter : 'all';
            realizedProfitBuyPage = 1;
            realizedProfitSellPage = 1;
            document.querySelectorAll('[data-profit-market]').forEach((button) => {
                button.classList.toggle('active', button.dataset.profitMarket === activeProfitMarketFilter);
            });

            const start = document.getElementById('realizedProfitStart').value;
            const end = document.getElementById('realizedProfitEnd').value;
            if (!start || !end) return;

            const cacheKey = `${start}:${end}`;
            const cached = getFreshRealizedCacheEntry(realizedProfitDetailCache, cacheKey);
            if (cached) {
                renderRealizedProfitDetail(cached);
            }
        }

        function fetchRealizedProfitFromInputs() {
            const start = document.getElementById('realizedProfitStart').value;
            const end = document.getElementById('realizedProfitEnd').value;
            if (!start || !end) return;
            if (start > end) {
                alert('조회 시작일은 종료일보다 늦을 수 없습니다.');
                return;
            }
            loadRealizedProfitDetail(start, end, true);
        }

        // ========== 종목 검색 ==========
        let searchTimer = null;
        let lastSearchResults = [];
        async function searchStock(query) {
            clearTimeout(searchTimer);
            const dropdown = document.getElementById('searchDropdown');
            if (!query || query.length < 1) {
                lastSearchResults = [];
                dropdown.style.display = 'none';
                return;
            }
            searchTimer = setTimeout(async () => {
                try {
                    const res = await fetch(`/api/stock-search?q=${encodeURIComponent(query)}`);
                    const result = await res.json();
                    if (result.status === 'success' && result.data.length > 0) {
                        lastSearchResults = result.data;
                        let html = '';
                        result.data.forEach(item => {
                            const mktBadge = item.market === 'KOR' ? '🇰🇷' : (item.market === 'JPN' ? '🇯🇵' : '🇺🇸');
                            const safeTicker = encodeURIComponent(item.ticker || '');
                            const safeMarket = item.market || 'USA';
                            html += `<div data-ticker="${safeTicker}" data-market="${safeMarket}" onclick="selectSearchResult(decodeURIComponent(this.dataset.ticker), this.dataset.market)"
                                style="padding:10px 14px; cursor:pointer; display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid rgba(255,255,255,0.04); transition:background 0.15s;"
                                onmouseover="this.style.background='rgba(255,255,255,0.06)'" onmouseout="this.style.background='transparent'">
                                <div>
                                    <div style="font-size:13px; font-weight:600; color:#fff;">${item.name}</div>
                                    <div style="font-size:11px; color:var(--text-muted);">${item.ticker}</div>
                                </div>
                                <span style="font-size:14px;">${mktBadge}</span>
                            </div>`;
                        });
                        dropdown.innerHTML = html;
                        dropdown.style.display = 'block';
                    } else {
                        lastSearchResults = [];
                        dropdown.innerHTML = '<div style="padding:14px; text-align:center; color:var(--text-muted); font-size:13px;">검색 결과 없음</div>';
                        dropdown.style.display = 'block';
                    }
                } catch (err) {
                    lastSearchResults = [];
                    console.error('Search failed:', err);
                }
            }, 300);
        }

        function handleSearchKeydown(event) {
            if (event.key !== 'Enter') return;
            const dropdown = document.getElementById('searchDropdown');
            const firstItem = dropdown.querySelector('[data-ticker][data-market]');
            if (firstItem) {
                event.preventDefault();
                selectSearchResult(decodeURIComponent(firstItem.dataset.ticker), firstItem.dataset.market);
                return;
            }
            if (lastSearchResults.length > 0) {
                event.preventDefault();
                const top = lastSearchResults[0];
                selectSearchResult(top.ticker, top.market);
            }
        }

        function selectSearchResult(ticker, market) {
            document.getElementById('stockSearchInput').value = '';
            document.getElementById('searchDropdown').style.display = 'none';
            fetchAssetInsight(ticker, market);
        }

        async function fetchMarketCalendar() {
            try {
                const res = await fetch('/api/market-calendar');
                const result = await res.json();
                if (result.status === 'success' && result.data && result.data.length > 0) {
                    const listContainer = document.getElementById('calendar_list');
                    listContainer.innerHTML = '';

                    result.data.forEach(item => {
                        // Create stars representation
                        let stars = '';
                        for (let i = 0; i < item.importance; i++) {
                            stars += '<span style="color:var(--accent-gold);">★</span>';
                        }

                        let details = '';
                        if ((item.actual && item.actual !== 'None') || (item.forecast && item.forecast !== 'None') || (item.previous && item.previous !== 'None')) {
                            details = `<div style = "color: var(--text-muted); font-size: 12px; margin-top: 4px;" > `;
                            details += `실제: <span style="color:#fff;">${item.actual && item.actual !== 'None' ? item.actual : 'None'}</span> <span style="margin:0 4px; color:rgba(255,255,255,0.2);">/</span> `;
                            details += `예측: <span style="color:#fff;">${item.forecast && item.forecast !== 'None' ? item.forecast : 'None'}</span> <span style="margin:0 4px; color:rgba(255,255,255,0.2);">/</span> `;
                            details += `이전: <span style="color:#fff;">${item.previous && item.previous !== 'None' ? item.previous : 'None'}</span>`;
                            details += `</div> `;
                        } else {
                            details = `<div style = "color: var(--text-muted); font-size: 12px; margin-top: 4px;" > `;
                            details += `실제: <span style="color:#fff;">None</span> <span style="margin:0 4px; color:rgba(255,255,255,0.2);">/</span> `;
                            details += `예측: <span style="color:#fff;">None</span> <span style="margin:0 4px; color:rgba(255,255,255,0.2);">/</span> `;
                            details += `이전: <span style="color:#fff;">None</span>`;
                            details += `</div> `;
                        }

                        let titleText = item.event;
                        if (titleText.startsWith(item.currency + " - ")) {
                            titleText = titleText.substring(item.currency.length + 3);
                        }

                        listContainer.innerHTML += `
                                <div class="cal-item" >
                                <span class="cal-date" style="min-width: 85px; padding-top: 2px;">${item.time}</span>
                                <div class="cal-desc" style="display: flex; flex-direction: column; flex: 1; min-width: 0;">
                                    <div style="display: flex; align-items: flex-start; gap: 6px;">
                                        <div style="font-size: 10px; letter-spacing: 1px; flex-shrink: 0; padding-top: 3px;">${stars}</div>
                                        <div style="font-weight: 500; color: #f8fafc; line-height: 1.4; word-break: keep-all;">
                                            <span style="color: var(--text-muted);"></span> ${titleText}
                                        </div>
                                    </div>
                                    ${details}
                                </div>
                            </div>
                                `;
                    });
                } else {
                    document.getElementById('calendar_list').innerHTML = `
                                <div style = "text-align: center; color: var(--text-muted); font-size: 13px; padding-top: 20px;" >
                                    오늘 예정된 주요 일정이 없습니다.
                        </div>
                                `;
                }
            } catch (error) {
                console.error('Failed to fetch market calendar', error);
                document.getElementById('calendar_list').innerHTML = `
                                <div style = "text-align: center; color: var(--loss); font-size: 13px; padding-top: 20px;" >
                                    캘린더 데이터를 불러오는데 실패했습니다.
                    </div>
                                `;
            }
        }

        // Asset Insight Fetcher
        async function fetchAssetInsight(ticker, marketType) {
            marketType = marketType || 'USA';
            const insightContainer = document.getElementById('insight_content');
            openInsightPane();
            insightContainer.innerHTML = `
                                <div style = "display:flex; flex-direction:column; align-items:center; justify-content:center; height:100%; gap:12px; color:var(--text-muted);" >
                    <div class="spinner" style="width:32px; height:32px; margin-bottom:0;"></div>
                    <span>데이터를 분석 중입니다...</span>
                </div>
                                `;

            try {
                // Determine clean ticker for TradingView
                let tvTicker = ticker;
                if (marketType === 'KOR') {
                    tvTicker = 'KRX:' + ticker;
                } else if (marketType === 'JPN') {
                    tvTicker = 'TSE:' + ticker;
                } else if (ticker.endsWith('.KS') || ticker.endsWith('.KQ')) {
                    tvTicker = 'KRX:' + ticker.split('.')[0];
                }

                const res = await fetch(`/api/asset-insight?ticker=${ticker}&market_type=${marketType}`);
                const result = await res.json();

                if (result.status === 'success') {
                    const data = result.data;
                    const fin = data.financials;
                    const opt = data.options;
                    const news = data.news;

                    // Pricing color
                    const rc = fin.currentPrice; // can be float

                    // TradingView Logo resolution logic
                    let tvLogoUrl = '';
                    let cleanTicker = ticker;
                    if (ticker.endsWith('.KS') || ticker.endsWith('.KQ')) {
                        cleanTicker = ticker.split('.')[0];
                        tvLogoUrl = `https://s3-symbol-logo.tradingview.com/${cleanTicker}--big.svg`;
                    } else if (!ticker.includes('.')) {
                        tvLogoUrl = `https://s3-symbol-logo.tradingview.com/${cleanTicker.toLowerCase()}--big.svg`;
                    }

                    let fallbackAvatar = `https://ui-avatars.com/api/?name=${encodeURIComponent(fin.shortName || ticker)}&background=random&color=fff&size=64`;

                    let imgHtml = '';
                    if (tvLogoUrl) {
                        imgHtml = `<img src="${tvLogoUrl}" alt="logo" 
                            onerror="this.onerror=function(){this.onerror=null; this.src='${fallbackAvatar}';}; this.src='${fin.logo_url ? fin.logo_url : fallbackAvatar}';" 
                            style="width: 40px; height: 40px; border-radius: 50%; object-fit: contain; background: #fff; padding: 2px;">`;
                    } else if (fin.logo_url) {
                        imgHtml = `<img src="${fin.logo_url}" alt="logo" 
                            onerror="this.onerror=null; this.src='${fallbackAvatar}';" 
                            style="width: 40px; height: 40px; border-radius: 50%; object-fit: contain; background: #fff; padding: 2px;">`;
                    } else {
                        imgHtml = `<img src="${fallbackAvatar}" alt="logo" 
                            style="width: 40px; height: 40px; border-radius: 50%; object-fit: contain; background: #fff; padding: 2px;">`;
                    }

                    let html = `
                        <div class="insight-top-bar animate-enter">
                            <div style="display:flex; align-items:center; gap: 12px;">
                                ${imgHtml}
                                <div style="display:flex; flex-direction:column;">
                                    <span class="insight-title">${fin.shortName}</span>
                                    <span class="insight-ticker">${ticker}</span>
                                </div>
                            </div>
                            <div style="display:flex; align-items:center; gap:10px;">
                                <div class="insight-price">${fin.currency} ${rc}</div>
                            </div>
                        </div>

                        <div class="fin-grid animate-enter" style="animation-delay: 0.1s;">
                            <div class="fin-card">
                                <span>Forward P/E <span style="text-transform:none; opacity:0.6;">선행 PER</span></span>
                                <span>${fin.forwardPE !== "N/A" ? parseFloat(fin.forwardPE).toFixed(2) : "N/A"}</span>
                            </div>
                            <div class="fin-card">
                                <span>ROE <span style="text-transform:none; opacity:0.6;">자기자본이익률</span></span>
                                <span>${fin.returnOnEquity !== "N/A" ? (parseFloat(fin.returnOnEquity) * 100).toFixed(2) + '%' : "N/A"}</span>
                            </div>
                            <div class="fin-card">
                                <span>D/E <span style="text-transform:none; opacity:0.6;">부채비율</span></span>
                                <span>${fin.debtToEquity !== "N/A" ? parseFloat(fin.debtToEquity).toFixed(2) : "N/A"}</span>
                            </div>
                            <div class="fin-card">
                                <span>Beta <span style="text-transform:none; opacity:0.6;">변동성</span></span>
                                <span>${fin.beta !== "N/A" ? parseFloat(fin.beta).toFixed(2) : "N/A"}</span>
                            </div>
                            <div class="fin-card">
                                <span>Mkt Cap <span style="text-transform:none; opacity:0.6;">시가총액</span></span>
                                <span>${fin.marketCap !== "N/A" ? (fin.marketCap >= 1e12 ? (fin.marketCap / 1e12).toFixed(2) + 'T' : fin.marketCap >= 1e9 ? (fin.marketCap / 1e9).toFixed(2) + 'B' : (fin.marketCap / 1e6).toFixed(0) + 'M') : "N/A"}</span>
                            </div>
                            <div class="fin-card">
                                <span>Short <span style="text-transform:none; opacity:0.6;">공매도비중</span></span>
                                <span style="color: ${fin.shortPercentOfFloat !== 'N/A' && parseFloat(fin.shortPercentOfFloat) > 0.1 ? 'var(--loss)' : '#fff'}">${fin.shortPercentOfFloat !== "N/A" ? (parseFloat(fin.shortPercentOfFloat) * 100).toFixed(2) + '%' : "N/A"}</span>
                            </div>
                            <div class="fin-card">
                                <span>Target <span style="text-transform:none; opacity:0.6;">목표가</span></span>
                                <span style="color: ${fin.targetMeanPrice !== 'N/A' && parseFloat(fin.targetMeanPrice) > rc ? 'var(--profit)' : (fin.targetMeanPrice !== 'N/A' ? 'var(--loss)' : '#fff')}">${fin.targetMeanPrice !== "N/A" ? fin.currency + ' ' + parseFloat(fin.targetMeanPrice).toFixed(2) : "N/A"}</span>
                            </div>
                            <div class="fin-card">
                                <span style="text-transform: none;">Analyst <span style="opacity:0.6;">분석가 평가</span></span>
                                <span style="text-transform: capitalize; color: ${fin.recommendation === 'buy' || fin.recommendation === 'strong_buy' ? 'var(--profit)' : (fin.recommendation === 'sell' || fin.recommendation === 'strong_sell' ? 'var(--loss)' : '#fff')}">${(fin.recommendation || 'N/A').replace('_', ' ')}</span>
                            </div>
                        </div>
                    `;

                    // 52-week range bar
                    if (fin.fiftyTwoWeekLow !== 'N/A' && fin.fiftyTwoWeekHigh !== 'N/A') {
                        const low52 = parseFloat(fin.fiftyTwoWeekLow);
                        const high52 = parseFloat(fin.fiftyTwoWeekHigh);
                        const range52 = high52 - low52;
                        const pos52 = range52 > 0 ? Math.min(100, Math.max(0, ((rc - low52) / range52) * 100)) : 50;
                        html += `
                            <div class="animate-enter" style="animation-delay: 0.15s; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.05); border-radius: 10px; padding: 10px 14px;">
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 6px;">
                                    <span style="font-size:10px; color:var(--text-muted); text-transform:uppercase; letter-spacing:0.5px;">52주 범위</span>
                                    <span style="font-size:11px; color:var(--text-sub);">${fin.currency} ${low52.toFixed(2)} — ${high52.toFixed(2)}</span>
                                </div>
                                <div style="position:relative; width:100%; height:6px; background: linear-gradient(90deg, var(--loss), var(--accent-gold), var(--profit)); border-radius:3px;">
                                    <div style="position:absolute; top:-3px; left:${pos52}%; transform:translateX(-50%); width:12px; height:12px; background:#fff; border-radius:50%; box-shadow: 0 0 6px rgba(255,255,255,0.5);"></div>
                                </div>
                            </div>
                        `;
                    }

                    // TradingView Chart
                    html += `
                        <div class="tv-wrapper animate-enter" style="animation-delay: 0.2s;" id="tv_chart_container"></div>
                    `;

                    // Split bottom area for News and Options

                    let newsHtml = ``;
                    if (news && news.length > 0) {
                        news.forEach(n => {
                            newsHtml += `
                                <a href="${n.link}" target="_blank" class="news-item">
                                    <span class="news-title">${n.title}</span>
                                    <span class="news-meta">${n.publisher}</span>
                                </a>
                            `;
                        });
                    } else {
                        newsHtml = `<div style="color:var(--text-muted); font-size:13px; padding:12px;">최신 뉴스가 없습니다.</div>`;
                    }

                    let optHtml = ``;
                    if (opt) {
                        const callVol = opt.calls_volume;
                        const putVol = opt.puts_volume;
                        const total = callVol + putVol;
                        const callPct = total > 0 ? Math.round((callVol / total) * 100) : 50;
                        const putPct = total > 0 ? 100 - callPct : 50;
                        const pcrRaw = opt.pcr;
                        const pcr = Number.isFinite(pcrRaw) ? Number(pcrRaw).toFixed(2) : (pcrRaw === 'High' ? 'High' : 'N/A');
                        const pcrBasis = opt.pcr_basis || 'Volume';
                        const callsOiNum = Number(opt.calls_oi || 0);
                        const putsOiNum = Number(opt.puts_oi || 0);
                        const oiAvailable = opt.oi_available === true || (callsOiNum + putsOiNum) > 0;
                        const inferredStrikeBasis = (callsOiNum > 0 && putsOiNum > 0) ? 'OI' : 'Volume';
                        const strikeBasis = opt.strike_basis || inferredStrikeBasis;
                        const ivText = opt.atm_iv ? opt.atm_iv + '%' : 'N/A';
                        const strikePrefix = fin.currency ? `${fin.currency} ` : '';
                        const maxCallLabel = strikeBasis === 'OI' ? 'Max OI Call' : 'Max Vol Call';
                        const maxPutLabel = strikeBasis === 'OI' ? 'Max OI Put' : 'Max Vol Put';
                        const oiCallText = oiAvailable ? `${formatNumber(opt.calls_oi)}계약` : '데이터 미제공';
                        const oiPutText = oiAvailable ? `${formatNumber(opt.puts_oi)}계약` : '데이터 미제공';
                        const oiHint = oiAvailable ? '' : `<div style="font-size:10px; color:var(--text-muted); margin-top:6px; text-align:right;">OI 데이터 미제공 (공급원 기준)</div>`;

                        optHtml = `
                            <div style="background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.05); border-radius: 12px; padding: 24px; display: flex; flex-direction: column; gap: 18px;">
                                <div style="display:flex; justify-content:space-between; align-items:center;">
                                    <div>
                                        <div style="font-size: 11px; color: var(--text-muted); margin-bottom: 2px;">만기일</div>
                                        <div style="font-size: 15px; font-weight: 700; color: #fff;">${opt.date}</div>
                                    </div>
                                    <div style="text-align:right;">
                                        <div style="font-size: 11px; color: var(--text-muted); margin-bottom: 2px;">ATM IV</div>
                                        <div style="font-size: 15px; font-weight: 700; color: ${opt.atm_iv && opt.atm_iv > 50 ? '#eab308' : '#fff'};">${ivText}</div>
                                    </div>
                                </div>
                                
                                <div>
                                    <div style="display:flex; justify-content:space-between; font-size:12px; font-weight: 500; margin-bottom:6px;">
                                        <span style="color:var(--profit);">콜 ${callPct}% <span style="font-size: 10px; opacity: 0.7;">(${formatNumber(callVol)}계약)</span></span>
                                        <span style="font-size:11px; color:var(--text-muted);">PCR(${pcrBasis}) ${pcr}</span>
                                        <span style="color:var(--loss);">풋 ${putPct}% <span style="font-size: 10px; opacity: 0.7;">(${formatNumber(putVol)}계약)</span></span>
                                    </div>
                                    <div style="width:100%; height:8px; background:rgba(255,255,255,0.1); border-radius:4px; display:flex; overflow:hidden;">
                                        <div style="width:${callPct}%; background:var(--profit);"></div>
                                        <div style="width:${putPct}%; background:var(--loss);"></div>
                                    </div>
                                </div>
                                
                                <div style="display:grid; grid-template-columns: 1fr 1fr; gap:10px; background: rgba(0,0,0,0.2); padding: 10px; border-radius: 8px;">
                                    <div>
                                        <div style="font-size:10px; color:var(--text-muted); margin-bottom: 2px;">OI (Call)</div>
                                        <div style="font-size:14px; font-weight:700; color:#fff;">${oiCallText}</div>
                                    </div>
                                    <div style="text-align:right;">
                                        <div style="font-size:10px; color:var(--text-muted); margin-bottom: 2px;">OI (Put)</div>
                                        <div style="font-size:14px; font-weight:700; color:#fff;">${oiPutText}</div>
                                    </div>
                                </div>
                                ${oiHint}

                                <div style="display:flex; flex-direction: column; gap:6px; border-top: 1px solid rgba(255,255,255,0.05); padding-top: 10px;">
                                    <div style="display:flex; justify-content:space-between; align-items:center;">
                                        <div style="font-size:11px; color:var(--text-muted);">Max Pain</div>
                                        <div style="font-size:13px; font-weight:700; color:#eab308;">${opt.max_pain ? strikePrefix + formatNumber(opt.max_pain) : 'N/A'}</div>
                                    </div>
                                    <div style="display:flex; justify-content:space-between; align-items:center;">
                                        <div style="font-size:11px; color:var(--text-muted);">${maxCallLabel}</div>
                                        <div style="font-size:13px; font-weight:700; color:var(--profit);">${opt.max_call_oi_strike ? strikePrefix + formatNumber(opt.max_call_oi_strike) : 'N/A'}</div>
                                    </div>
                                    <div style="display:flex; justify-content:space-between; align-items:center;">
                                        <div style="font-size:11px; color:var(--text-muted);">${maxPutLabel}</div>
                                        <div style="font-size:13px; font-weight:700; color:var(--loss);">${opt.max_put_oi_strike ? strikePrefix + formatNumber(opt.max_put_oi_strike) : 'N/A'}</div>
                                    </div>
                                </div>
                            </div>
                        `;
                    } else {
                        optHtml = `<div style="color:var(--text-muted); font-size:13px; padding:12px;">옵션 데이터가 없습니다.</div>`;
                    }

                    html += `
                        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: stretch;" class="animate-enter" style="animation-delay: 0.25s;">
                            <!-- Left: News -->
                            <div style="display: flex; flex-direction: column;">
                                <div class="news-header">관련 뉴스</div>
                                <div class="news-list" style="flex: 1;">
                                    ${newsHtml}
                                </div>
                            </div>
                            <!-- Right: Options -->
                            <div style="display: flex; flex-direction: column;">
                                <div class="news-header">단기 옵션 현황</div>
                                ${optHtml}
                            </div>
                        </div>
                    `;

                    // Inject
                    insightContainer.innerHTML = html;

                    // Init Chart
                    if (marketType === 'USA') {
                        // US stocks: use TradingView widget
                        try {
                            new TradingView.widget({
                                "autosize": true,
                                "symbol": tvTicker,
                                "interval": "D",
                                "timezone": "Asia/Seoul",
                                "theme": "dark",
                                "style": "1",
                                "locale": "kr",
                                "enable_publishing": false,
                                "backgroundColor": "rgba(0,0,0,0)",
                                "gridColor": "rgba(255,255,255,0.05)",
                                "hide_top_toolbar": false,
                                "hide_legend": false,
                                "save_image": false,
                                "allow_symbol_change": true,
                                "container_id": "tv_chart_container"
                            });
                        } catch (tvErr) {
                            console.warn('TradingView widget failed:', tvErr);
                        }
                    } else {
                        // KOR/JPN stocks: use Lightweight Charts with yfinance data
                        const historyData = data.history || [];
                        const tvContainer = document.getElementById('tv_chart_container');
                        if (historyData.length > 0 && tvContainer && typeof LightweightCharts !== 'undefined') {
                            tvContainer.innerHTML = '';
                            const chart = LightweightCharts.createChart(tvContainer, {
                                width: tvContainer.clientWidth,
                                height: tvContainer.clientHeight,
                                layout: {
                                    background: { type: 'solid', color: 'transparent' },
                                    textColor: 'rgba(255,255,255,0.6)',
                                    fontSize: 11,
                                },
                                grid: {
                                    vertLines: { color: 'rgba(255,255,255,0.04)' },
                                    horzLines: { color: 'rgba(255,255,255,0.04)' },
                                },
                                crosshair: { mode: 0 },
                                rightPriceScale: {
                                    borderColor: 'rgba(255,255,255,0.1)',
                                },
                                timeScale: {
                                    borderColor: 'rgba(255,255,255,0.1)',
                                    timeVisible: false,
                                },
                            });
                            const candleSeries = chart.addCandlestickSeries({
                                upColor: '#26a69a',
                                downColor: '#ef5350',
                                borderDownColor: '#ef5350',
                                borderUpColor: '#26a69a',
                                wickDownColor: '#ef5350',
                                wickUpColor: '#26a69a',
                            });
                            candleSeries.setData(historyData);

                            const volumeSeries = chart.addHistogramSeries({
                                color: 'rgba(255,255,255,0.15)',
                                priceFormat: { type: 'volume' },
                                priceScaleId: '',
                            });
                            volumeSeries.priceScale().applyOptions({
                                scaleMargins: { top: 0.85, bottom: 0 },
                            });
                            volumeSeries.setData(historyData.map(d => ({
                                time: d.time,
                                value: d.volume,
                                color: d.close >= d.open ? 'rgba(38,166,154,0.3)' : 'rgba(239,83,80,0.3)',
                            })));

                            chart.timeScale().fitContent();
                            // Resize handler
                            const resizeObserver = new ResizeObserver(() => {
                                chart.applyOptions({ width: tvContainer.clientWidth });
                            });
                            resizeObserver.observe(tvContainer);
                        } else {
                            tvContainer.innerHTML = `
                                <div style="display:flex; flex-direction:column; align-items:center; justify-content:center; height:100%; gap:8px; color:var(--text-muted); font-size:13px;">
                                    <span>차트 데이터를 불러올 수 없습니다.</span>
                                </div>
                            `;
                        }
                    }

                } else {
                    insightContainer.innerHTML = `
                        <div style="color:var(--loss); text-align:center; padding:20px;">
                            ${result.message || '정보를 불러오는 데 실패했습니다.'}
                        </div>
                    `;
                }
            } catch (err) {
                console.error("fetchAssetInsight error", err);
                insightContainer.innerHTML = `
                    <div style="color:var(--loss); text-align:center; padding:20px;">
                        정보를 불러오는 데 실패했습니다.
                    </div>
                `;
            }
        }

        let modalConfirmAction = null;

        function showModal(opts) {
            const overlay = document.getElementById('customModal');
            document.getElementById('modalTitle').innerText = opts.title;
            document.getElementById('modalDesc').innerText = opts.desc;

            const iconEl = document.getElementById('modalIcon');
            iconEl.innerHTML = opts.iconHtml;
            if (opts.isDanger) {
                iconEl.classList.add('warning');
            } else {
                iconEl.classList.remove('warning');
            }

            const confirmBtn = document.getElementById('modalConfirmBtn');
            confirmBtn.innerText = opts.confirmText || '확인';
            confirmBtn.className = 'btn-modal ' + (opts.isDanger ? 'btn-danger' : 'btn-confirm');

            modalConfirmAction = opts.onConfirm;
            overlay.classList.add('active');
        }

        function closeModal() {
            document.getElementById('customModal').classList.remove('active');
            modalConfirmAction = null;
        }

        function executeModalConfirm() {
            if (modalConfirmAction) {
                modalConfirmAction();
            }
            closeModal();
        }

        function lockScreen() {
            showModal({
                title: '화면 잠금',
                desc: '화면을 잠그시겠습니까?\n잠금 해제를 위해 간편비밀번호를 다시 입력해야 합니다.',
                confirmText: '잠금',
                isDanger: false,
                iconHtml: `<svg viewBox="0 0 24 24" width="24" height="24" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>`,
                onConfirm: async () => {
                    try {
                        await fetch('/api/logout', { method: 'POST' });
                        window.location.href = '/login';
                    } catch (err) {
                        console.error("Lock error", err);
                    }
                }
            });
        }

        function logoutAndReset() {
            showModal({
                title: '로그아웃 및 설정 초기화',
                desc: '정말 로그아웃 하시겠습니까?\n등록된 한국투자증권 API 정보와 간편비밀번호가 기기에서 완전히 삭제되며 처음부터 다시 설정해야 합니다.',
                confirmText: '초기화 및 로그아웃',
                isDanger: true,
                iconHtml: `<svg viewBox="0 0 24 24" width="24" height="24" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>`,
                onConfirm: async () => {
                    try {
                        await fetch('/api/reset', { method: 'POST' });
                        window.location.href = '/login';
                    } catch (err) {
                        console.error("Reset error", err);
                    }
                }
            });
        }

        function runDeferredBootTasks() {
            fetchMarketCalendar();
            fetchRealizedProfitSummary();
        }

        function scheduleDeferredBootTasks() {
            if (typeof window.requestIdleCallback === 'function') {
                window.requestIdleCallback(() => runDeferredBootTasks(), { timeout: 1500 });
                return;
            }
            window.setTimeout(runDeferredBootTasks, 300);
        }

        document.addEventListener('visibilitychange', () => {
            if (document.hidden) {
                stopUsQuotePolling();
            }
        });

        // 앱 초기화
        window.addEventListener('DOMContentLoaded', async () => {
            const savedLayoutMode = localStorage.getItem(LAYOUT_STORAGE_KEY) || 'mode2';
            applyLayoutMode(savedLayoutMode, false);
            setRightPaneState('widgets');

            // Set today's date
            const today = new Date();
            const year = today.getFullYear();
            const month = String(today.getMonth() + 1).padStart(2, '0');
            const day = String(today.getDate()).padStart(2, '0');
            const days = ['일', '월', '화', '수', '목', '금', '토'];
            const dayOfWeek = days[today.getDay()];

            document.getElementById('today_date').innerText = `${year}년 ${month}월 ${day}일 (${dayOfWeek})`;

            await syncData(false);
            scheduleDeferredBootTasks();
        });
