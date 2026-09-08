        let myChart = null;
        let currentCurrencyMode = 'local'; // 'local' or 'krw'
        let cachedAllItems = [];
        let cachedItems = [];
        let cachedAccounts = [];
        let activePortfolioAccountId = 'all';
        let activeProfitAccountId = 'all';
        let cachedKrCash = 0;
        let cachedUsdCash = 0;
        let cachedJpyCash = 0;
        let cachedExrt = 1350;
        let cachedJpExrt = 905;
        let marketOverviewMountTimer = null;
        let marketOverviewVerifyTimer = null;
        let currentLayoutMode = 'mode2';
        let rightPaneState = 'widgets';
        let assetCardShowingForeign = false;
        let cashCardShowingForeign = false;
        let profitCardShowingRealized = false;
        let realizedProfitSummaryCache = new Map();
        let realizedProfitDetailCache = new Map();
        let realizedProfitSummaryInFlight = new Map();
        let realizedProfitDetailInFlight = new Map();
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
        const REALIZED_PROFIT_CACHE_TTL_MS = 10 * 1000;
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
        let currentInsightState = null;
        let currentInsightRequestSeq = 0;
        let insightChart = null;
        let insightChartKind = null;
        let insightChartObserver = null;
        let insightCandleSeries = null;
        let insightVolumeSeries = null;
        let marketOverviewGeneration = 0;
        let marketOverviewLoadTimer = null;

        function themeColor(name) {
            return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
        }

        function resolvedTheme() {
            return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
        }

        function disposeInsightChart() {
            disposeInsightDetailCharts();
            insightChartObserver?.disconnect();
            insightChartObserver = null;
            if (insightChart && typeof insightChart.remove === 'function') {
                try { insightChart.remove(); } catch (error) { console.warn('Chart cleanup failed:', error); }
            }
            insightChart = null;
            insightChartKind = null;
            insightCandleSeries = null;
            insightVolumeSeries = null;
        }

        function insightChartThemeOptions() {
            return {
                layout: { background: { type: 'solid', color: 'transparent' }, textColor: themeColor('--chart-text'), fontSize: 11 },
                grid: { vertLines: { color: themeColor('--chart-grid') }, horzLines: { color: themeColor('--chart-grid') } },
                rightPriceScale: { borderColor: themeColor('--chart-border') },
                timeScale: { borderColor: themeColor('--chart-border'), timeVisible: false },
            };
        }

        function candleThemeOptions() {
            return {
                upColor: themeColor('--chart-up'), downColor: themeColor('--chart-down'),
                borderUpColor: themeColor('--chart-up'), borderDownColor: themeColor('--chart-down'),
                wickUpColor: themeColor('--chart-up'), wickDownColor: themeColor('--chart-down'),
            };
        }

        function volumeChartData(history) {
            return history.map((item) => ({ time: item.time, value: item.volume,
                color: themeColor(item.close >= item.open ? '--chart-volume-up' : '--chart-volume-down') }));
        }

        function portfolioTooltipTheme() {
            return { backgroundColor: themeColor('--chart-tooltip-bg'), titleColor: themeColor('--chart-tooltip-text'),
                bodyColor: themeColor('--chart-tooltip-text'), borderColor: themeColor('--chart-border') };
        }

        function refreshChartThemes() {
            if (myChart) {
                const colors = allocationColors();
                myChart.data.datasets[0].backgroundColor = myChart.data.labels.map((_, index) => colors[index % colors.length]);
                document.querySelectorAll('.alloc-color').forEach((element, index) => {
                    element.style.color = colors[index % colors.length];
                    element.style.backgroundColor = colors[index % colors.length];
                });
                myChart.options.color = themeColor('--chart-text');
                Object.assign(myChart.options.plugins.tooltip, portfolioTooltipTheme());
                myChart.update('none');
            }
            if (insightChartKind === 'lightweight' && insightChart) {
                insightChart.applyOptions(insightChartThemeOptions());
                insightCandleSeries.applyOptions(candleThemeOptions());
                insightVolumeSeries.setData(volumeChartData(currentInsightState?.data?.history || []));
            } else if (insightChartKind === 'tradingview') {
                // Replace only the embedded chart, retaining the insight content and scroll position.
                initializeInsightChart(currentInsightState);
            }
            initializeInsightDetailCharts(currentInsightState);
            scheduleMarketOverviewMount(true);
        }

        window.addEventListener('dashboard:themechange', refreshChartThemes);
        const US_QUOTE_POLL_INTERVAL_MS = 3000;
        const US_QUOTE_POLL_WINDOW_MS = 1 * 60 * 1000;
        const LIVE_CHART_UPDATE_MIN_INTERVAL_MS = 15000;

        const LAYOUT_STORAGE_KEY = 'dashboard_layout_mode';
        const MARKET_OVERVIEW_WIDGET_CONFIG = {
            dateRange: '1M',
            showChart: true,
            locale: 'kr',
            largeChartUrl: '',
            isTransparent: true,
            showSymbolLogo: true,
            showFloatingTooltip: true,
            width: '100%',
            height: '100%',
            tabs: [
                {
                    title: 'Indices',
                    symbols: [
                        { s: 'FOREXCOM:SPXUSD', d: 'S&P 500' },
                        { s: 'FOREXCOM:NSXUSD', d: 'NASDAQ' },
                        { s: 'INDEX:DXY', d: 'Dollar Index' },
                        { s: 'FOREXCOM:XAUUSD', d: 'Gold' },
                        { s: 'BITSTAMP:BTCUSD', d: 'Bitcoin' },
                        { s: 'CAPITALCOM:VIX', d: 'VIX' },
                        { s: 'FRED:DGS10', d: 'US 10Y Yield' },
                    ],
                },
            ],
        };

        function allocationColors() {
            return Array.from({ length: 9 }, (_, index) => themeColor(`--chart-tone-${index + 1}`));
        }

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

        function formatDisplayDateTime(value) {
            if (!value) return '-';
            const parsed = new Date(value);
            if (Number.isNaN(parsed.getTime())) {
                return String(value).replace('T', ' ').slice(0, 16);
            }
            const year = parsed.getFullYear();
            const month = String(parsed.getMonth() + 1).padStart(2, '0');
            const day = String(parsed.getDate()).padStart(2, '0');
            const hour = String(parsed.getHours()).padStart(2, '0');
            const minute = String(parsed.getMinutes()).padStart(2, '0');
            return `${year}.${month}.${day} ${hour}:${minute}`;
        }

        function formatInsightPrice(fin, marketType) {
            const price = Number(fin?.currentPrice);
            if (!Number.isFinite(price)) {
                const fallback = String(fin?.currentPrice || '-').trim();
                return fin?.currency ? `${fin.currency} ${fallback}` : fallback;
            }
            if (marketType === 'KOR' || fin?.currency === 'KRW') {
                return formatPlainKrw(price);
            }
            if (marketType === 'JPN' || fin?.currency === 'JPY') {
                return formatJpy(price);
            }
            return `${fin?.currency || ''} ${formatNumber(price.toFixed(2))}`.trim();
        }

        function getInsightMarketLabel(marketType) {
            if (marketType === 'KOR') return '국내';
            if (marketType === 'JPN') return '일본';
            return '해외';
        }

        async function readApiErrorMessage(response, fallbackMessage) {
            try {
                const payload = await response.json();
                return payload?.detail || payload?.message || fallbackMessage;
            } catch (_err) {
                return fallbackMessage;
            }
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
                ? `<span class="profit-badge" style="background: var(--surface-subtle); color: var(--text-main);">0.00%</span>`
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

        function escapeHtml(value) {
            return String(value ?? '')
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#39;');
        }

        function escapeAttributeValue(value) {
            return String(value ?? '')
                .replace(/\\/g, '\\\\')
                .replace(/"/g, '\\"');
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

        function accountFilterLabel(account) {
            const label = String(account?.label || '').trim();
            if (label) return label;
            return String(account?.broker_name || '계좌');
        }

        function populateAccountFilterSelect(selectId, selectedValue) {
            const select = document.getElementById(selectId);
            if (!select) return 'all';
            const availableIds = new Set(cachedAccounts.map(account => String(account.account_id || '')));
            const safeSelected = selectedValue !== 'all' && availableIds.has(selectedValue) ? selectedValue : 'all';
            select.innerHTML = '';
            const integrated = document.createElement('option');
            integrated.value = 'all';
            integrated.textContent = '통합';
            select.appendChild(integrated);
            cachedAccounts.forEach((account) => {
                const option = document.createElement('option');
                option.value = String(account.account_id || '');
                option.textContent = accountFilterLabel(account);
                select.appendChild(option);
            });
            select.value = safeSelected;
            return safeSelected;
        }

        function updateAccountFilterOptions(accounts) {
            cachedAccounts = Array.isArray(accounts) ? accounts : [];
            activePortfolioAccountId = populateAccountFilterSelect('portfolioAccountFilter', activePortfolioAccountId);
            activeProfitAccountId = populateAccountFilterSelect('realizedProfitAccountFilter', activeProfitAccountId);
        }

        function applyPortfolioAccountFilter() {
            cachedItems = cachedAllItems
                .filter((item) => activePortfolioAccountId === 'all' || String(item.account_id || '') === activePortfolioAccountId)
                .sort((a, b) => b.evalAmtKrw - a.evalAmtKrw);
            renderPortfolioSummary(cachedItems);
            renderTable();
            updateChart(cachedItems, cachedTotalEvalKrw);
        }

        function setPortfolioAccountFilter(accountId) {
            activePortfolioAccountId = String(accountId || 'all');
            applyPortfolioAccountFilter();
        }

        function rebuildPortfolioView(combinedItems, usMarketStatus) {
            lastUsMarketStatus = usMarketStatus || lastUsMarketStatus;
            cachedAllItems = [...combinedItems];
            applyPortfolioAccountFilter();
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
            const tickerText = String(item.ticker || '');
            const nameText = String(item.name || '-');
            const accountLabel = String(item.account_label || '').trim();
            const normalizedTicker = normalizeTicker(tickerText);
            const safeTicker = escapeHtml(tickerText);
            const safeName = escapeHtml(nameText);
            const safeTickerAttr = escapeAttributeValue(normalizedTicker);
            const onClickArgs = `${JSON.stringify(tickerText)}, ${JSON.stringify(String(item.type || ''))}`;

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
                const isUsDayMarket = item.quote_session === 'day_market'
                    || lastUsMarketStatus?.session === 'day_market';
                if (isUsDayMarket && item.quote_stale !== false) {
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
                <tr data-ticker="${safeTickerAttr}" onclick='fetchAssetInsight(${onClickArgs})' style="cursor: pointer; transition: background 0.2s;">
                    <td>
                        <div class="ticker-cell">
                            <span class="ticker-name">${safeName}</span>
                            <div class="ticker-meta-row">
                                ${badgeHtml}
                                ${quoteBadgeHtml}
                                ${accountLabel ? `<span class="badge badge-account" title="출처 계좌: ${escapeAttributeValue(accountLabel)}">${escapeHtml(accountLabel)}</span>` : ''}
                                ${tickerText ? `<span class="ticker-symbol">${safeTicker}</span>` : ''}
                            </div>
                        </div>
                    </td>
                    <td>${formatNumber(item.qty)}</td>
                    <td class="js-eval" style="color:var(--text-main); font-weight:600;">${pricePrefix}${formatNumber(totalValLocal.toFixed(dp))}</td>
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
                const selectorTicker = escapeAttributeValue(ticker);
                const row = document.querySelector(`#all_list tr[data-ticker="${selectorTicker}"]`);
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
            const mergedItems = cachedAllItems.map(item => {
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

            cachedAllItems = mergedItems;
            if (changedTickers.length > 0) {
                applyPortfolioAccountFilter();
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

        function setMarketOverviewStatus(message, isError = false) {
            const statusEl = document.getElementById('marketOverviewStatus');
            if (!statusEl) return;
            statusEl.textContent = message;
            statusEl.classList.toggle('is-error', isError);
            statusEl.classList.remove('is-hidden');
        }

        function hideMarketOverviewStatus() {
            const statusEl = document.getElementById('marketOverviewStatus');
            if (!statusEl) return;
            statusEl.classList.add('is-hidden');
            statusEl.classList.remove('is-error');
        }

        function mountMarketOverviewWidget(force = false) {
            const container = document.getElementById('marketOverviewWidgetContainer');
            const widget = document.getElementById('marketOverviewWidget');
            if (!container || !widget) return;

            const hasFrame = !!widget.querySelector('iframe');
            if (!force && hasFrame && widget.dataset.theme === resolvedTheme()) {
                hideMarketOverviewStatus();
                return;
            }

            if (container.offsetWidth <= 0 || container.offsetHeight <= 0) {
                return;
            }

            if (marketOverviewVerifyTimer) {
                clearTimeout(marketOverviewVerifyTimer);
                marketOverviewVerifyTimer = null;
            }

            widget.dataset.theme = resolvedTheme();
            const generation = ++marketOverviewGeneration;
            clearTimeout(marketOverviewLoadTimer);
            widget.innerHTML = '';
            setMarketOverviewStatus('주요 지표 위젯을 불러오는 중입니다...');

            const script = document.createElement('script');
            script.type = 'text/javascript';
            script.src = 'https://s3.tradingview.com/external-embedding/embed-widget-market-overview.js';
            script.async = true;
            script.textContent = JSON.stringify({ ...MARKET_OVERVIEW_WIDGET_CONFIG, colorTheme: resolvedTheme() });
            script.onload = () => {
                if (generation !== marketOverviewGeneration) return;
                marketOverviewLoadTimer = setTimeout(() => {
                    if (generation !== marketOverviewGeneration) return;
                    if (widget.querySelector('iframe')) {
                        hideMarketOverviewStatus();
                    }
                }, 150);
            };
            script.onerror = () => {
                if (generation !== marketOverviewGeneration) return;
                setMarketOverviewStatus('주요 지표 위젯을 불러오지 못했습니다. 네트워크 또는 광고 차단 설정을 확인한 뒤 새로고침해 주세요.', true);
            };
            widget.appendChild(script);

            marketOverviewVerifyTimer = setTimeout(() => {
                marketOverviewVerifyTimer = null;
                if (generation !== marketOverviewGeneration) return;
                if (widget.querySelector('iframe')) {
                    hideMarketOverviewStatus();
                    return;
                }
                setMarketOverviewStatus('주요 지표 위젯 응답이 지연되고 있습니다. 잠시 후 자동으로 다시 시도합니다.', true);
                scheduleMarketOverviewMount(true, 2500);
            }, 7000);
        }

        function scheduleMarketOverviewMount(force = false, delayMs = 80) {
            if (marketOverviewMountTimer) {
                clearTimeout(marketOverviewMountTimer);
            }
            marketOverviewMountTimer = setTimeout(() => {
                marketOverviewMountTimer = null;
                mountMarketOverviewWidget(force);
            }, delayMs);
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
                scheduleMarketOverviewMount(false);
            }
        }

        function applyLayoutMode(mode, persist = true) {
            currentLayoutMode = mode === 'mode1' ? 'mode1' : 'mode2';
            document.body.classList.remove('layout-mode-1', 'layout-mode-2');
            document.body.classList.add(currentLayoutMode === 'mode1' ? 'layout-mode-1' : 'layout-mode-2');

            setRightPaneState(rightPaneState);
            updateLayoutModeUI();
            scheduleMarketOverviewMount(true);

            if (persist) {
                try { localStorage.setItem(LAYOUT_STORAGE_KEY, currentLayoutMode); } catch (_err) { /* Storage is optional. */ }
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
            const chartColors = allocationColors();

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
                            <div class="alloc-name" title="${escapeAttributeValue(i.name)}">${escapeHtml(i.name)}</div>
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
                Chart.defaults.color = themeColor('--chart-text');
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
                                ...portfolioTooltipTheme(),
                                bodyFont: { size: 14, weight: 'bold' },
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
                                centerText.style.color = "var(--text-main)";
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
                const syncUrl = manualTrigger ? '/api/sync?manual_refresh=1' : '/api/sync';
                const res = await fetch(syncUrl);

                if (res.status === 401) {
                    window.location.href = '/login';
                    return;
                }

                const data = await res.json();

                if (data.status === "success") {
                    updateAccountFilterOptions(data.accounts);
                    const accountSyncWarning = document.getElementById('accountSyncWarning');
                    const accountErrors = Array.isArray(data.account_errors) ? data.account_errors : [];
                    if (accountSyncWarning) {
                        if (accountErrors.length > 0) {
                            const labels = accountErrors.map(error => error.account_label || '계좌').join(', ');
                            accountSyncWarning.textContent = `${labels} 동기화에 실패해 합계에서 제외되었습니다. 계좌 설정을 확인해 주세요.`;
                            accountSyncWarning.hidden = false;
                        } else {
                            accountSyncWarning.textContent = '';
                            accountSyncWarning.hidden = true;
                        }
                    }
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
                        if (profitCardShowingRealized) {
                            fetchRealizedProfitSummary(true);
                        }
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

        function realizedProfitCoverageNote(payload) {
            if (!payload || payload.status !== 'success') return '';
            const notes = [];
            if (payload.profit_estimated === true) {
                notes.push('토스 추정 손익 포함');
            }
            if (payload.profit_complete === false) {
                const missing = Number(payload.unpriced_sell_count || 0);
                notes.push(missing > 0 ? `원가 부족 ${missing}건 미산출` : '일부 계좌 손익 미완성');
            }
            return notes.length ? ` · ${notes.join(' · ')}` : '';
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

            if (summaryPayload.profit_available === false) {
                valueEl.innerText = '-';
                valueEl.className = 'summary-value';
                subEl.innerText = '토스 매수 원가 이력이 부족해 추정 손익을 산출하지 못했습니다';
                return;
            }

            const total = Number(summaryPayload.summary?.total_realized_profit_krw || 0);
            valueEl.innerText = formatSignedKrw(total);
            valueEl.className = `summary-value ${profitClassName(total)}`.trim();
            const coverageNote = realizedProfitCoverageNote(summaryPayload);
            subEl.innerText = `${formatDisplayDate(summaryPayload.period.start)} ~ ${formatDisplayDate(summaryPayload.period.end)} 누적${coverageNote}`;
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
            const cacheKey = `${activeProfitAccountId}:${month}`;
            const cached = getFreshRealizedCacheEntry(realizedProfitSummaryCache, cacheKey);
            if (cached && !force) {
                renderRealizedProfitSummary(cached);
                return cached;
            }
            const inFlight = realizedProfitSummaryInFlight.get(cacheKey);
            if (inFlight) {
                return inFlight;
            }

            realizedProfitSummaryLoading = true;
            document.getElementById('val_realized_profit_month').innerText = '조회 중...';
            document.getElementById('val_realized_profit_month').className = 'summary-value';
            document.getElementById('val_realized_profit_month_sub').innerText = '실현 손익을 불러오는 중입니다';
            renderRealizedProfitMonthNav();

            const request = (async () => {
                try {
                    const params = new URLSearchParams({ month });
                    if (activeProfitAccountId !== 'all') params.set('account_id', activeProfitAccountId);
                    if (force) params.set('force_refresh', '1');
                    const res = await fetch(`/api/realized-profit/summary?${params.toString()}`);
                    if (res.status === 401) {
                        window.location.href = '/login';
                        return null;
                    }
                    const data = await res.json();
                    setRealizedCacheEntry(realizedProfitSummaryCache, cacheKey, data);
                    renderRealizedProfitSummary(data);
                    return data;
                } catch (err) {
                    console.error('fetchRealizedProfitSummary error', err);
                    const errorPayload = { status: 'error' };
                    setRealizedCacheEntry(realizedProfitSummaryCache, cacheKey, errorPayload);
                    renderRealizedProfitSummary(errorPayload);
                    return errorPayload;
                } finally {
                    realizedProfitSummaryLoading = false;
                    realizedProfitSummaryInFlight.delete(cacheKey);
                }
            })();
            realizedProfitSummaryInFlight.set(cacheKey, request);
            return request;
        }

        function setProfitCardFace(showRealized) {
            const card = document.getElementById('profitSummaryCard');
            if (!card) return;
            profitCardShowingRealized = !!showRealized;
            card.classList.toggle('is-flipped', profitCardShowingRealized);
            if (profitCardShowingRealized) {
                const cacheKey = `${activeProfitAccountId}:${activeRealizedSummaryMonth}`;
                const cached = getFreshRealizedCacheEntry(realizedProfitSummaryCache, cacheKey);
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
            else if (preset === 'oneYear') range = getRecentMonthsRange(12);
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
                noteEl.innerText = `${realizedProfitTaxEstimate.year}년 누적 실현 손익 ${formatSignedKrw(realizedProfitTaxEstimate.total_profit_krw)} × 22% 기준 추정치입니다.${realizedProfitTaxEstimate.includes_estimate ? ' 토스 거래내역 기반 추정 손익이 포함됐습니다.' : ''}`;
            } else {
                noteEl.innerText = `${realizedProfitTaxEstimate.year}년 누적 실현 손익이 ${formatPlainKrw(CAPITAL_GAINS_TAX_THRESHOLD_KRW)} 이하라서 양도소득세를 0원으로 표시합니다.${realizedProfitTaxEstimate.includes_estimate ? ' 토스 거래내역 기반 추정 손익이 포함됐습니다.' : ''}`;
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

        async function changeTradeHistoryPage(kind, delta) {
            if (realizedProfitDetailLoading) return;

            const start = document.getElementById('realizedProfitStart')?.value;
            const end = document.getElementById('realizedProfitEnd')?.value;
            if (!start || !end) return;

            const isSell = kind === 'sell';
            const currentPage = Math.max(1, Number(isSell ? realizedProfitSellPage : realizedProfitBuyPage) || 1);
            const pagination = currentRealizedProfitDetail?.pagination || {};
            const totalPages = Math.max(1, Number(pagination.total_pages || 1) || 1);
            const nextPage = Math.min(Math.max(1, currentPage + delta), totalPages);
            if (nextPage === currentPage) return;

            if (isSell) {
                realizedProfitSellPage = nextPage;
            } else {
                realizedProfitBuyPage = nextPage;
            }

            const payload = await loadRealizedProfitDetail(start, end);
            if (!payload || payload.status !== 'success') {
                if (isSell) {
                    realizedProfitSellPage = currentPage;
                } else {
                    realizedProfitBuyPage = currentPage;
                }
            }
        }

        function getActiveTradeHistoryPage() {
            return activeProfitModalTab === 'sell' ? realizedProfitSellPage : realizedProfitBuyPage;
        }

        function buildRealizedProfitDetailCacheKey(start, end, options = {}) {
            const side = options.side || activeProfitModalTab || 'buy';
            const market = options.market || activeProfitMarketFilter || 'all';
            const page = options.page || getActiveTradeHistoryPage();
            const pageSize = options.pageSize || REALIZED_PROFIT_PAGE_SIZE;
            const includeTrades = options.includeTrades !== false;
            return `${activeProfitAccountId}:${start}:${end}:${side}:${market}:${page}:${pageSize}:${includeTrades ? '1' : '0'}`;
        }

        async function getRealizedProfitDetailPayload(start, end, options = {}, force = false) {
            const side = options.side || activeProfitModalTab || 'buy';
            const market = options.market || activeProfitMarketFilter || 'all';
            const page = options.page || getActiveTradeHistoryPage();
            const pageSize = options.pageSize || REALIZED_PROFIT_PAGE_SIZE;
            const includeTrades = options.includeTrades !== false;
            const cacheKey = buildRealizedProfitDetailCacheKey(start, end, { side, market, page, pageSize, includeTrades });
            const cached = getFreshRealizedCacheEntry(realizedProfitDetailCache, cacheKey);
            if (!force && cached) {
                return cached;
            }
            const inFlight = realizedProfitDetailInFlight.get(cacheKey);
            if (inFlight) {
                return inFlight;
            }
            const request = (async () => {
                try {
                    const params = new URLSearchParams({
                        start,
                        end,
                        side,
                        market,
                        page: String(page),
                        page_size: String(pageSize),
                        include_trades: includeTrades ? '1' : '0',
                    });
                    if (activeProfitAccountId !== 'all') params.set('account_id', activeProfitAccountId);
                    if (force) params.set('force_refresh', '1');
                    const res = await fetch(`/api/realized-profit/detail?${params.toString()}`);
                    if (res.status === 401) {
                        window.location.href = '/login';
                        return null;
                    }
                    const data = await res.json();
                    setRealizedCacheEntry(realizedProfitDetailCache, cacheKey, data);
                    return data;
                } finally {
                    realizedProfitDetailInFlight.delete(cacheKey);
                }
            })();
            realizedProfitDetailInFlight.set(cacheKey, request);
            return request;
        }

        async function calculateCapitalGainsTax() {
            if (!currentRealizedProfitDetail || currentRealizedProfitDetail.status !== 'success') {
                realizedProfitTaxEstimate = null;
                renderCapitalGainsTaxEstimate();
                setCapitalGainsTaxPopover(true);
                return;
            }

            if (currentRealizedProfitDetail.profit_available === false || currentRealizedProfitDetail.profit_complete === false) {
                realizedProfitTaxEstimate = null;
                const unavailableValueEl = document.getElementById('realizedProfitTaxPopupValue');
                const unavailableNoteEl = document.getElementById('realizedProfitTaxPopupNote');
                if (unavailableValueEl) unavailableValueEl.innerText = '-';
                if (unavailableNoteEl) unavailableNoteEl.innerText = '매수 원가 이력이 부족한 거래가 있어 세금 추정에서 제외했습니다.';
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
                const yearPayload = await getRealizedProfitDetailPayload(yearStart, yearEnd, { includeTrades: false });
                if (!yearPayload || yearPayload.status !== 'success') {
                    realizedProfitTaxEstimate = null;
                    renderCapitalGainsTaxEstimate();
                    if (noteEl) {
                        noteEl.innerText = `${year}년 양도소득세 계산에 필요한 데이터를 불러오지 못했습니다.`;
                    }
                    return;
                }
                if (yearPayload.profit_available === false || yearPayload.profit_complete === false) {
                    realizedProfitTaxEstimate = null;
                    if (valueEl) valueEl.innerText = '-';
                    if (noteEl) noteEl.innerText = `${year}년 거래 중 매수 원가를 확인할 수 없는 건이 있어 세금을 계산하지 않았습니다.`;
                    return;
                }

                const totalProfit = Number(yearPayload.summary?.total_realized_profit_krw || 0);
                const taxable = totalProfit > CAPITAL_GAINS_TAX_THRESHOLD_KRW;
                realizedProfitTaxEstimate = {
                    year,
                    total_profit_krw: totalProfit,
                    tax_krw: taxable ? Math.round(totalProfit * CAPITAL_GAINS_TAX_RATE) : 0,
                    includes_estimate: yearPayload.profit_estimated === true,
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

        function tradeAccountBadgeHtml(trade, fallbackSide) {
            const side = String(trade?.side || fallbackSide || '').trim();
            const isBuy = side === '매수' || side.toUpperCase() === 'BUY';
            const sideLabel = isBuy ? '매수' : '매도';
            const sideClass = isBuy ? 'badge-trade-buy' : 'badge-trade-sell';
            const accountLabel = String(trade?.account_label || '계좌').trim();
            return `<div class="trade-account-cell"><span class="badge ${sideClass}">${sideLabel}</span><span class="badge badge-account" title="${escapeAttributeValue(accountLabel)}">${escapeHtml(accountLabel)}</span></div>`;
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

            if (detailPayload.profit_available === false) {
                [totalEl, domesticEl, overseasEl, rateEl].forEach((element) => {
                    element.innerText = '-';
                    element.className = 'profit-stat-value';
                });
            } else {
                totalEl.innerText = formatSignedKrw(total);
                totalEl.className = `profit-stat-value ${profitClassName(total)}`.trim();
                domesticEl.innerText = formatSignedKrw(domestic);
                domesticEl.className = `profit-stat-value ${profitClassName(domestic)}`.trim();
                overseasEl.innerText = formatSignedKrw(overseas);
                overseasEl.className = `profit-stat-value ${profitClassName(overseas)}`.trim();
                rateEl.innerText = formatSignedPercent(totalRate);
                rateEl.className = `profit-stat-value ${profitClassName(totalRate)}`.trim();
            }
            const coverageNote = detailPayload.profit_available === false
                ? ' · 토스 매수 원가 이력 부족'
                : realizedProfitCoverageNote(detailPayload);
            captionEl.innerText = `${formatDisplayDate(detailPayload.period.start)} ~ ${formatDisplayDate(detailPayload.period.end)}${coverageNote}`;

            const trades = Array.isArray(detailPayload.trades) ? detailPayload.trades : [];
            const filters = detailPayload.filters || {};
            const pagination = detailPayload.pagination || { page: 1, total_pages: 1, total_items: trades.length };
            const activeSide = filters.side === 'sell' ? 'sell' : 'buy';
            const marketLabel = activeProfitMarketFilter === 'domestic' ? '국내' : activeProfitMarketFilter === 'overseas' ? '해외' : '선택한 조건';

            if (activeSide === 'buy') {
                realizedProfitBuyPage = Number(pagination.page || 1);
                if (!trades.length) {
                    buyRowsEl.innerHTML = '';
                    buyEmptyEl.classList.add('active');
                    buyEmptyEl.innerText = `${marketLabel} 매수 거래내역이 없습니다.`;
                } else {
                    buyEmptyEl.classList.remove('active');
                    buyRowsEl.innerHTML = trades.map((trade) => `
                    <tr>
                        <td>${formatDisplayDate(trade.date)}</td>
                        <td>${tradeAccountBadgeHtml(trade, '매수')}</td>
                        <td>${escapeHtml(trade.ticker || trade.symbol || '-')}</td>
                        <td>${escapeHtml(trade.name || trade.symbol || '-')}</td>
                        <td>${formatNumber(Number(trade.quantity || 0))}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.unit_price) : `${trade.currency || ''} ${formatNumber(Number(trade.unit_price || 0).toFixed(2))}`}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.amount) : `${trade.currency || ''} ${formatNumber(Number(trade.amount || 0).toFixed(2))}`}</td>
                    </tr>
                `).join('');
                }
                renderTradePagination('buy', Number(pagination.total_items || 0), realizedProfitBuyPage, Number(pagination.total_pages || 1));
                renderTradePagination('sell', 0, 1, 1);
            } else {
                realizedProfitSellPage = Number(pagination.page || 1);
                if (!trades.length) {
                    sellRowsEl.innerHTML = '';
                    sellEmptyEl.classList.add('active');
                    sellEmptyEl.innerText = `${marketLabel} 매도 거래내역이 없습니다.`;
                } else {
                    sellEmptyEl.classList.remove('active');
                    sellRowsEl.innerHTML = trades.map((trade) => `
                    <tr>
                        <td>${formatDisplayDate(trade.date)}</td>
                        <td>${tradeAccountBadgeHtml(trade, '매도')}</td>
                        <td>${escapeHtml(trade.ticker || trade.symbol || '-')}</td>
                        <td>${escapeHtml(trade.name || trade.symbol || '-')}</td>
                        <td>${formatNumber(Number(trade.quantity || 0))}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.unit_price) : `${trade.currency || ''} ${formatNumber(Number(trade.unit_price || 0).toFixed(2))}`}</td>
                        <td>${trade.currency === 'KRW' ? formatPlainKrw(trade.amount) : `${trade.currency || ''} ${formatNumber(Number(trade.amount || 0).toFixed(2))}`}</td>
                        <td class="${profitClassName(trade.realized_profit_krw)}">${trade.realized_profit_krw == null ? '-' : `${formatSignedKrw(trade.realized_profit_krw)}${trade.realized_profit_estimated ? '<span class="profit-estimate-chip">추정</span>' : ''}`}</td>
                        <td class="${profitClassName(trade.realized_return_rate)}">${trade.realized_return_rate == null ? '-' : formatSignedPercent(trade.realized_return_rate)}</td>
                    </tr>
                `).join('');
                }
                renderTradePagination('sell', Number(pagination.total_items || 0), realizedProfitSellPage, Number(pagination.total_pages || 1));
                renderTradePagination('buy', 0, 1, 1);
            }
        }

        async function loadRealizedProfitDetail(start, end, force = false) {
            const loadingEl = document.getElementById('realizedProfitLoading');
            realizedProfitDetailLoading = true;
            loadingEl.classList.add('active');
            document.getElementById('buyHistoryEmpty').classList.remove('active');
            document.getElementById('sellHistoryEmpty').classList.remove('active');

            try {
                const data = await getRealizedProfitDetailPayload(start, end, {
                    side: activeProfitModalTab || 'buy',
                    market: activeProfitMarketFilter || 'all',
                    page: getActiveTradeHistoryPage(),
                    pageSize: REALIZED_PROFIT_PAGE_SIZE,
                }, force);
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
            setProfitModalTab(activeProfitModalTab || 'buy', false);
            setProfitMarketFilter(activeProfitMarketFilter || 'all', false);
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
            presetButtons.forEach((button) => {
                button.classList.remove('active');
            });
            realizedProfitBuyPage = 1;
            realizedProfitSellPage = 1;
        }

        function setProfitModalTab(tab, shouldFetch = true) {
            activeProfitModalTab = tab === 'sell' ? 'sell' : 'buy';
            if (activeProfitModalTab === 'sell') realizedProfitSellPage = 1;
            else realizedProfitBuyPage = 1;
            document.querySelectorAll('[data-profit-tab]').forEach((button) => {
                button.classList.toggle('active', button.dataset.profitTab === activeProfitModalTab);
            });
            document.getElementById('buyHistoryPanel').classList.toggle('active', activeProfitModalTab === 'buy');
            document.getElementById('sellHistoryPanel').classList.toggle('active', activeProfitModalTab === 'sell');
            if (!shouldFetch) return;
            const start = document.getElementById('realizedProfitStart').value;
            const end = document.getElementById('realizedProfitEnd').value;
            if (start && end) {
                loadRealizedProfitDetail(start, end);
            }
        }

        function setProfitMarketFilter(filter, shouldFetch = true) {
            activeProfitMarketFilter = ['domestic', 'overseas'].includes(filter) ? filter : 'all';
            realizedProfitBuyPage = 1;
            realizedProfitSellPage = 1;
            document.querySelectorAll('[data-profit-market]').forEach((button) => {
                button.classList.toggle('active', button.dataset.profitMarket === activeProfitMarketFilter);
            });

            const start = document.getElementById('realizedProfitStart').value;
            const end = document.getElementById('realizedProfitEnd').value;
            if (!start || !end) return;
            if (shouldFetch) {
                loadRealizedProfitDetail(start, end);
            }
        }

        function setRealizedProfitAccountFilter(accountId, shouldFetch = true) {
            const requested = String(accountId || 'all');
            const exists = requested === 'all' || cachedAccounts.some(account => String(account.account_id || '') === requested);
            activeProfitAccountId = exists ? requested : 'all';
            const select = document.getElementById('realizedProfitAccountFilter');
            if (select) select.value = activeProfitAccountId;
            realizedProfitBuyPage = 1;
            realizedProfitSellPage = 1;
            realizedProfitTaxEstimate = null;
            renderCapitalGainsTaxEstimate();
            if (!shouldFetch) return;
            fetchRealizedProfitSummary();
            const start = document.getElementById('realizedProfitStart')?.value;
            const end = document.getElementById('realizedProfitEnd')?.value;
            if (start && end) {
                loadRealizedProfitDetail(start, end);
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
                                style="padding:10px 14px; cursor:pointer; display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border-subtle); transition:background 0.15s;"
                                onmouseover="this.style.background='var(--surface-hover)'" onmouseout="this.style.background='transparent'">
                                <div>
                                    <div style="font-size:13px; font-weight:600; color:var(--text-main);">${item.name}</div>
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
                            details += `실제: <span style="color:var(--text-main);">${item.actual && item.actual !== 'None' ? item.actual : 'None'}</span> <span style="margin:0 4px; color:var(--text-muted);">/</span> `;
                            details += `예측: <span style="color:var(--text-main);">${item.forecast && item.forecast !== 'None' ? item.forecast : 'None'}</span> <span style="margin:0 4px; color:var(--text-muted);">/</span> `;
                            details += `이전: <span style="color:var(--text-main);">${item.previous && item.previous !== 'None' ? item.previous : 'None'}</span>`;
                            details += `</div> `;
                        } else {
                            details = `<div style = "color: var(--text-muted); font-size: 12px; margin-top: 4px;" > `;
                            details += `실제: <span style="color:var(--text-main);">None</span> <span style="margin:0 4px; color:var(--text-muted);">/</span> `;
                            details += `예측: <span style="color:var(--text-main);">None</span> <span style="margin:0 4px; color:var(--text-muted);">/</span> `;
                            details += `이전: <span style="color:var(--text-main);">None</span>`;
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
                                        <div style="font-weight: 500; color: var(--text-main); line-height: 1.4; word-break: keep-all;">
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

        function getInsightDisplayName(state) {
            if (state?.status === 'success') {
                const providerName = state.data?.financials?.shortName;
                if (state.data?.source === 'saveticker' && (!providerName || providerName === state.ticker)) {
                    const holding = cachedItems.find(item => item.type === state.marketType && normalizeTicker(item.ticker) === normalizeTicker(state.ticker));
                    if (holding?.name) return holding.name;
                }
                return providerName || state.ticker;
            }
            return state?.ticker || '-';
        }

        function getInsightDisplayPrice(state) {
            if (!state) return '-';
            if (state.status === 'success') {
                return formatInsightPrice(state.data?.financials, state.marketType);
            }
            if (state.status === 'loading') {
                return '조회 중...';
            }
            return `${getInsightMarketLabel(state.marketType)} 종목`;
        }

        function buildInsightIdentityHtml(state) {
            const ticker = escapeHtml(state?.ticker || '-');
            const title = escapeHtml(getInsightDisplayName(state));
            const price = escapeHtml(getInsightDisplayPrice(state));
            const logoHtml = state?.imgHtml || `<div style="width:40px; height:40px; border-radius:50%; background:var(--surface-subtle); display:flex; align-items:center; justify-content:center; color:var(--text-sub); font-size:13px; font-weight:700;">${escapeHtml(String(state?.ticker || '?').slice(0, 2))}</div>`;
            return `
                <div class="insight-top-bar animate-enter">
                    <div style="display:flex; align-items:center; gap:12px; min-width:0;">
                        ${logoHtml}
                        <div style="display:flex; flex-direction:column; min-width:0;">
                            <span class="insight-title">${title}</span>
                            <span class="insight-ticker">${ticker}</span>
                        </div>
                    </div>
                    <div style="display:flex; align-items:center; gap:10px;">
                        <div class="insight-price">${price}</div>
                    </div>
                </div>
            `;
        }

        function buildInsightLoadingHtml(state) {
            return `
                ${buildInsightIdentityHtml(state)}
                <div style="display:flex; flex-direction:column; align-items:center; justify-content:center; min-height:280px; gap:12px; color:var(--text-muted);">
                    <div class="spinner" style="width:32px; height:32px; margin-bottom:0;"></div>
                    <span>데이터를 분석 중입니다...</span>
                </div>
            `;
        }

        function buildInsightErrorHtml(state) {
            return `
                ${buildInsightIdentityHtml(state)}
                <div class="form-notice form-notice--error active animate-enter" style="animation-delay:0.05s;">
                    ${escapeHtml(state?.message || '정보를 불러오는 데 실패했습니다.')}
                </div>
            `;
        }

        function buildInsightChartHtml(extraClasses = '', inlineStyle = '') {
            const className = ['tv-wrapper', extraClasses].filter(Boolean).join(' ');
            const styleAttr = inlineStyle ? ` style="${inlineStyle}"` : '';
            return `<div class="${className}" id="tv_chart_container"${styleAttr}></div>`;
        }

        // SaveTicker percentages are already expressed as percent, not fractions.
        function insightFinite(value) {
            if (!['number', 'string'].includes(typeof value) || (typeof value === 'string' && !value.trim())) return null;
            const number = Number(value);
            return Number.isFinite(number) ? number : null;
        }

        function insightNumber(value, suffix = '', compact = false) {
            const number = insightFinite(value);
            return number === null ? '—' : escapeHtml(number.toLocaleString('en-US', {
                maximumFractionDigits: 2, ...(compact ? { notation: 'compact' } : {}),
            }) + suffix);
        }

        function insightDate(value) {
            if (typeof value === 'string') {
                // Calendar dates describe reporting periods; do not shift them by timezone.
                const day = value.slice(0, 10);
                if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return '—';
                const calendar = new Date(day + 'T00:00:00Z');
                if (!Number.isFinite(calendar.getTime()) || calendar.toISOString().slice(0, 10) !== day) return '—';
                if (value === day) return escapeHtml(day);
                if (!/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.test(value)) return '—';
            } else if (typeof value !== 'number' || !Number.isFinite(value)) return '—';
            const timestamp = new Date(value).getTime();
            const koreanTime = new Date(timestamp + 9 * 60 * 60 * 1000);
            if (!Number.isFinite(timestamp) || !Number.isFinite(koreanTime.getTime())) return '—';
            return escapeHtml(koreanTime.toISOString().slice(0, 16).replace('T', ' ') + ' KST');
        }

        function insightSafeUrl(value) {
            try {
                const url = new URL(String(value));
                return ['https:', 'http:'].includes(url.protocol) ? escapeHtml(url.href) : '';
            } catch (_) { return ''; }
        }

        let insightDetailCharts = [];

        function disposeInsightDetailCharts() {
            insightDetailCharts.forEach(chart => { if (chart.canvas) chart.canvas.onkeydown = null; try { chart.destroy(); } catch (_) { /* Already detached. */ } });
            insightDetailCharts = [];
        }

        function insightExact(value, suffix = '') {
            const number = insightFinite(value);
            return number === null ? '—' : escapeHtml(number.toLocaleString('en-US', { maximumFractionDigits: 2 }) + suffix);
        }

        function saveTickerQuarters(section) {
            const rows = Array.isArray(section?.quarters) ? section.quarters.filter(q => q && typeof q === 'object').slice(0, 5).reverse() : [];
            const rank = label => {
                const match = String(label).match(/^'?(\d{2}|\d{4})\s+Q([1-4])$/);
                return match ? (Number(match[1]) + (match[1].length === 2 ? 2000 : 0)) * 4 + Number(match[2]) : null;
            };
            return rows.sort((a, b) => rank(a.label) !== null && rank(b.label) !== null ? rank(a.label) - rank(b.label) : 0);
        }

        function saveTickerDetailChartSpecs(state) {
            if (state?.status !== 'success' || !['available', 'partial'].includes(state.data?.saveticker?.status)) return [];
            const s = state.data.saveticker.sections || {};
            const specs = [];
            const nonnegative = value => { const n = insightFinite(value); return n !== null && n >= 0 ? n : null; };
            const count = value => { const n = nonnegative(value); return Number.isInteger(n) ? n : null; };
            const quarters = saveTickerQuarters(s.revenue);
            if (quarters.some(q => nonnegative(q.revenue) !== null)) specs.push({ id: 'revenue', title: '분기별 매출 · USD', labels: quarters.map(q => String(q.label ?? '—')), unit: 'USD', datasets: [{ label: '매출', data: quarters.map(q => nonnegative(q.revenue)), color: 'blue' }] });
            const a = s.analyst;
            const counts = ['buy', 'hold', 'sell'].map(key => count(a?.dist?.[key]));
            const total = counts.reduce((sum, n) => sum + (n ?? 0), 0);
            if (counts.every(n => n !== null) && total > 0 && count(a?.analystCount) === total) specs.push({ id: 'consensus', title: `애널리스트 의견 · 총 ${total}명`, labels: ['의견 비중'], horizontal: true, stacked: true, unit: '%', datasets: counts.map((n, i) => ({ label: `${['매수', '보유', '매도'][i]} ${n}명`, data: [n / total * 100], color: ['teal', 'neutral', 'coral'][i] })) });
            const low = nonnegative(a?.target?.low), mean = nonnegative(a?.target?.mean), high = nonnegative(a?.target?.high);
            const current = nonnegative(s.header?.price ?? state.data?.financials?.currentPrice);
            if (low !== null && mean !== null && high !== null && low <= mean && mean <= high) {
                specs.push({ id: 'targets', title: '목표가 범위와 현재가 · USD', scatter: true, unit: 'USD', datasets: [
                    { label: '목표가 범위', data: [{ x: low, y: 0 }, { x: high, y: 0 }], color: 'neutral', showLine: true, pointStyle: 'rect' },
                    { label: '평균 목표가', data: [{ x: mean, y: 0 }], color: 'blue', pointStyle: 'circle' },
                    ...(current === null ? [] : [{ label: '현재가', data: [{ x: current, y: 0.4 }], color: 'teal', pointStyle: 'triangle' }]),
                ] });
            }
            const o = s.options;
            if (o && o.optionable !== false) {
                const shares = [['거래량', o.volumeShare], ['미결제약정', o.openInterestShare], ['프리미엄', o.premiumShare]].filter(([, pair]) => {
                    const call = nonnegative(pair?.call), put = nonnegative(pair?.put);
                    return call !== null && put !== null && call <= 100 && put <= 100 && Math.abs(call + put - 100) <= 0.05;
                });
                if (shares.length) specs.push({ id: 'shares', title: '옵션 콜·풋 비중', labels: shares.map(([label]) => label), horizontal: true, stacked: true, unit: '%', datasets: ['call', 'put'].map((key, i) => ({ label: i ? '풋' : '콜', data: shares.map(([, pair]) => Number(pair[key])), color: i ? 'coral' : 'teal' })) });
                const ratios = ['d3', 'd7', 'd30'].map(key => ({ label: `${key.slice(1)}일 평균`, value: o.optionVolumeVsAvg?.windows?.[key]?.available === true ? nonnegative(o.optionVolumeVsAvg.windows[key].ratioPct) : null }));
                if (ratios.some(r => r.value !== null)) specs.push({ id: 'volume-comparison', title: '동일 시각 누적 거래량 · 평균 대비', labels: ratios.map(r => r.label), horizontal: true, unit: '%', reference: 100, datasets: [{ label: '평균 대비', data: ratios.map(r => r.value), color: 'blue' }] });
            }
            const buys = count(s.insider?.buyCount), sells = count(s.insider?.sellCount);
            if (buys !== null && sells !== null) specs.push({ id: 'insider-counts', title: `최근 ${insightFinite(s.insider?.window) ?? '—'}일 내부자 거래 건수`, labels: ['매수', '매도'], horizontal: true, unit: '건', datasets: [{ label: '거래 건수', data: [buys, sells], color: ['teal', 'coral'] }] });
            return specs;
        }

        function initializeInsightDetailCharts(state) {
            disposeInsightDetailCharts();
            if (typeof Chart === 'undefined') return;
            const palette = resolvedTheme() === 'light'
                ? { blue: '#2865b4', teal: '#087d72', coral: '#bc4d42', neutral: '#7b808a' }
                : { blue: '#69aaff', teal: '#42c7b4', coral: '#f38b7c', neutral: '#969eac' };
            const textColor = themeColor('--chart-text');
            const gridColor = themeColor('--chart-grid');
            for (const spec of saveTickerDetailChartSpecs(state)) {
                const canvas = document.getElementById(`st-chart-${spec.id}`);
                if (!canvas) continue;
                const valueAxis = spec.horizontal || spec.scatter ? 'x' : 'y';
                const format = n => Number(n).toLocaleString('en-US', { maximumFractionDigits: 2, notation: 'compact' }) + (spec.unit === 'USD' ? ' USD' : spec.unit);
                const config = {
                    type: spec.scatter ? 'scatter' : 'bar',
                    data: { labels: spec.labels, datasets: spec.datasets.map(d => ({ ...d, backgroundColor: Array.isArray(d.color) ? d.color.map(c => palette[c]) : palette[d.color], borderColor: palette[d.color] || palette.neutral, borderWidth: spec.scatter ? 2 : 0, pointRadius: spec.scatter ? 5 : undefined, pointHoverRadius: 7, borderRadius: 2, maxBarThickness: 30 })) },
                    options: {
                        responsive: true, maintainAspectRatio: false, indexAxis: spec.horizontal ? 'y' : 'x', color: textColor,
                        animation: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? false : { duration: 250 },
                        interaction: { mode: 'nearest', intersect: false },
                        plugins: {
                            legend: { display: !!(spec.stacked || spec.scatter), position: 'bottom', labels: { color: textColor, usePointStyle: true, boxWidth: 8, font: { size: 10 } } },
                            tooltip: { ...portfolioTooltipTheme(), callbacks: { label: ctx => `${ctx.dataset.label}: ${Number(spec.scatter ? ctx.parsed.x : ctx.parsed[valueAxis]).toLocaleString('en-US', { maximumFractionDigits: 2 })} ${spec.unit}` } },
                        },
                        scales: {
                            x: { stacked: !!spec.stacked, ticks: { color: textColor, font: { size: 10 } }, grid: { color: gridColor } },
                            y: { stacked: !!spec.stacked, ticks: { color: textColor, font: { size: 10 } }, grid: { color: gridColor } },
                        },
                    },
                };
                Object.assign(config.options.scales[valueAxis], { beginAtZero: !spec.scatter, min: spec.scatter ? undefined : 0, max: spec.stacked ? 100 : undefined, suggestedMax: spec.reference ? 120 : undefined, ticks: { color: textColor, font: { size: 10 }, callback: format } });
                if (spec.scatter) config.options.scales.y = { display: false, min: -0.4, max: 0.8 };
                if (spec.unit === '건') config.options.scales[valueAxis].ticks.precision = 0;
                if (spec.horizontal) config.plugins = [{ id: 'valueLabels', afterDatasetsDraw(chart) {
                    const ctx = chart.ctx; ctx.save(); ctx.font = '10px sans-serif'; ctx.textBaseline = 'middle';
                    chart.data.datasets.forEach((dataset, datasetIndex) => {
                        chart.getDatasetMeta(datasetIndex).data.forEach((bar, index) => {
                            const value = dataset.data[index];
                            if (value === null || (spec.stacked && bar.width < 32)) return;
                            const label = Number(value).toLocaleString('en-US', { maximumFractionDigits: 1 }) + spec.unit;
                            const inside = bar.width > ctx.measureText(label).width + 14;
                            ctx.fillStyle = inside ? (resolvedTheme() === 'light' ? '#ffffff' : '#17212d') : textColor;
                            ctx.textAlign = spec.stacked ? 'center' : (inside ? 'right' : 'left');
                            ctx.fillText(label, spec.stacked ? (bar.base + bar.x) / 2 : bar.x + (inside ? -6 : 5), bar.y);
                        });
                    });
                    ctx.restore();
                } }];
                if (spec.reference) config.plugins = [...(config.plugins || []), { id: 'referenceLine', afterDatasetsDraw(chart) {
                    const x = chart.scales.x.getPixelForValue(spec.reference), { top, bottom } = chart.chartArea;
                    const ctx = chart.ctx; ctx.save(); ctx.strokeStyle = textColor; ctx.setLineDash([4, 4]); ctx.beginPath(); ctx.moveTo(x, top); ctx.lineTo(x, bottom); ctx.stroke(); ctx.restore();
                } }];
                try {
                    const chart = new Chart(canvas, config);
                    insightDetailCharts.push(chart);
                    let focused = -1;
                    canvas.onkeydown = event => {
                        if (!['ArrowLeft', 'ArrowRight', 'Escape'].includes(event.key)) return;
                        event.preventDefault();
                        const points = chart.data.datasets.flatMap((dataset, datasetIndex) => dataset.data.map((value, index) => value === null ? null : { datasetIndex, index }).filter(Boolean));
                        if (!points.length) return;
                        focused = event.key === 'Escape' ? -1 : (focused + (event.key === 'ArrowLeft' ? -1 : 1) + points.length) % points.length;
                        const active = focused < 0 ? [] : [points[focused]];
                        chart.setActiveElements(active);
                        const point = active.length ? chart.getDatasetMeta(active[0].datasetIndex).data[active[0].index].getCenterPoint() : { x: 0, y: 0 };
                        chart.tooltip?.setActiveElements(active, point);
                        chart.update('none');
                    };
                } catch (error) {
                    canvas.closest('.st-chart-figure')?.remove();
                    console.warn('Insight detail chart unavailable:', spec.id);
                }
            }
        }

        function buildSaveTickerInsightHtml(state) {
            const data = state.data || {};
            const st = data.saveticker || {};
            const sections = st.sections || {};
            const h = sections.header || {};
            const k = sections.key_metrics || {};
            const money = value => insightFinite(value) === null ? '—' : '$' + insightNumber(value);
            const text = value => value === null || value === undefined || value === '' ? '—' : escapeHtml(value);
            const module = (title, body, meta = '') => `<section class="st-section"><div class="st-heading"><h3>${title}</h3>${meta ? `<span>${meta}</span>` : ''}</div>${body}</section>`;
            const unavailable = '<p class="st-note">이 항목은 현재 제공되지 않습니다.</p>';
            const exactMoney = value => { const n = insightFinite(value); return n === null ? '—' : (n < 0 ? '-$' : '$') + insightExact(Math.abs(n)); };
            const table = (id, headings, rows) => `<div class="st-table-wrap"><table class="st-table st-data-table" id="st-table-${id}"><thead><tr>${headings.map(h => `<th scope="col">${h}</th>`).join('')}</tr></thead><tbody>${rows.map(row => `<tr>${row.map((cell, i) => i === 0 ? `<th scope="row">${cell}</th>` : `<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
            const chartSpecs = saveTickerDetailChartSpecs(state);
            const chart = id => {
                const spec = chartSpecs.find(s => s.id === id);
                if (typeof Chart === 'undefined' || !spec) return '';
                return `<figure class="st-chart-figure"><figcaption>${escapeHtml(spec.title)}</figcaption><div class="st-chart-wrap${spec.scatter || spec.id === 'consensus' ? ' st-chart-wrap--short' : ''}"><canvas id="st-chart-${id}" role="img" tabindex="0" aria-label="${escapeHtml(spec.title)}. 화살표 키로 값 탐색, Escape로 해제. 정확한 값은 다음 표에 제공됩니다." aria-describedby="st-table-${id}"></canvas></div></figure>`;
            };
            const price = insightFinite(h.price) ?? insightFinite(data.financials?.currentPrice);
            const identityState = { ...state, imgHtml: '', data: { ...data, financials: { ...data.financials, currentPrice: price ?? '—' } } };
            const statuses = { open: '정규장', closed: '장 마감', pre: '프리마켓', post: '애프터마켓', premarket: '프리마켓', afterhours: '애프터마켓' };
            let html = `<div class="st-insight">${buildInsightChartHtml()}${buildInsightIdentityHtml(identityState)}
                <div class="st-source"><strong>SaveTicker${st.status === 'partial' ? ' · 일부 제공' : ''}</strong><span>${text(statuses[h.marketStatus] || h.marketStatus)} · 전일 종가 대비 ${insightNumber(h.changePercent, '%')}</span></div>
                <p class="st-note st-freshness">시세 ${insightDate(h.asOf)} · 조회 ${insightDate(st.fetched_at)} · 최대 5분 캐시</p>`;
            const metricRows = [
                ['PER', insightExact(k.per?.value, '배'), `업종 평균 ${insightExact(k.per?.compare, '배')}`],
                ['EPS', exactMoney(k.eps?.value), `전년 대비 ${insightExact(k.eps?.compare, '%')}`],
                ['ROE', insightExact(k.roe?.value, '%'), text(k.roe?.sectorPercentile)],
                ['매출', exactMoney(k.revenueTtm?.value), `${text(k.periodLabel)} · 전년 대비 ${insightExact(k.revenueTtm?.compare, '%')}`],
                ['배당 수익률', insightExact(k.dividendYield?.value, '%'), `분기 배당 ${exactMoney(k.dividendYield?.compare)}`],
                ['시가총액', exactMoney(h.marketCap), `시세 ${insightDate(h.asOf)}`],
                ['공매도 비중', insightExact(k.shortInterestPct?.value, '%'), `2주 변화 ${insightExact(k.shortInterestPct?.compare, '%p')}${k.shortBasis === 'float' ? ' · 유통주식 기준' : ''}`],
                ['Days to cover', insightExact(k.daysToCover?.value, '일'), `2주 변화 ${insightExact(k.daysToCover?.compare, '일')}`],
            ];
            html += module('핵심 지표', sections.key_metrics ? `<div class="st-core-tables">${table('core-a', ['지표', '값', '비교 · 기준'], metricRows.slice(0, 4))}${table('core-b', ['지표', '값', '비교 · 기준'], metricRows.slice(4))}</div><p class="st-note">지표 기준 ${insightDate(k.asOf)} · 공매도 기준 ${insightDate(k.shortAsOf)}</p>` : unavailable);
            const range = (label, values) => {
                const low = insightFinite(values?.low), high = insightFinite(values?.high), current = insightFinite(values?.current) ?? price;
                const pos = low !== null && high !== null && current !== null && high > low ? Math.min(100, Math.max(0, (current - low) / (high - low) * 100)) : null;
                return `<div class="st-range"><div><span>${label}</span><strong>${money(low)} — ${money(high)}</strong></div>${pos === null ? '' : `<div class="st-range-track" aria-hidden="true"><i style="left:${pos}%"></i></div>`}</div>`;
            };
            html += `<div class="st-ranges">${range('당일 범위', h.dayRange)}${range('52주 범위', h.week52Range)}</div>`;
            // Keep the existing single chart mount and its disposal/theme lifecycle.
            html += '<div class="st-details">';
            const revenue = sections.revenue;
            const quarters = saveTickerQuarters(revenue);
            const revenueSection = module('분기 매출', quarters.length ? chart('revenue') + table('revenue', ['회계 분기', '매출 (USD)', '전년 대비'], quarters.map(q => [text(q.label), exactMoney(q.revenue), insightExact(q.yoy, '%')])) : unavailable, text(revenue?.source));
            const a = sections.analyst;
            const firms = Array.isArray(a?.recent) ? a.recent.filter(f => f && typeof f === 'object') : [];
            const analystSection = module('애널리스트', a ? chart('consensus') + table('consensus', ['의견', '인원'], [
                ['매수', insightExact(a.dist?.buy, '명')], ['보유', insightExact(a.dist?.hold, '명')], ['매도', insightExact(a.dist?.sell, '명')], ['제공 총원', insightExact(a.analystCount, '명')],
            ]) + chart('targets') + table('targets', ['가격 기준', 'USD'], [
                ['최저 목표가', exactMoney(a.target?.low)], ['평균 목표가', exactMoney(a.target?.mean)], ['최고 목표가', exactMoney(a.target?.high)], ['현재가', exactMoney(price)],
            ]) + (firms.length ? table('analysts', ['증권사 / 날짜', '평가', '이전 목표가', '현재 목표가'], firms.map(f => [`${text(f.firmKo || f.firm)}<small>${insightDate(f.at)}</small>`, text(f.rating), exactMoney(f.prevTarget), exactMoney(f.target)])) : '') : unavailable, a ? `${text(a.provider)} · ${insightDate(a.asOf)}` : '');
            const o = sections.options;
            let optionsHtml = unavailable;
            if (o?.optionable === false) optionsHtml = '<p class="st-note">옵션이 제공되지 않는 종목입니다.</p>';
            else if (o) {
                optionsHtml = `<p class="st-note">스냅샷 ${insightDate(o.snapshotDate)}${o.snapshotIsPriorDay ? ' · 전일 스냅샷' : ''}<br>시세 기준 ${insightDate(o.asOf)}<br>배치 ${insightDate(o.batchDate)}${o.batchIsPriorDay ? ' · 전일 배치' : ''}${o.batchIsProvisional ? ' · 잠정 집계' : ''}</p>`;
                optionsHtml += table('option-summary', ['거래 지표', '값'], [
                    ['당일 거래량 · 전체 만기', insightExact(o.volume, '계약')], ['PCR · 거래량', insightExact(o.putCallRatioVolume)], ['PCR · 미결제약정', insightExact(o.putCallRatioOpenInterest)],
                ]);
                optionsHtml += chart('shares') + table('shares', ['구분', '콜', '풋'], [['거래량 비중', o.volumeShare], ['미결제약정 비중', o.openInterestShare], ['프리미엄 비중', o.premiumShare]].map(([label, pair]) => [label, insightExact(pair?.call, '%'), insightExact(pair?.put, '%')]));
                if (chartSpecs.find(spec => spec.id === 'shares')?.labels.length !== 3) optionsHtml += '<p class="st-note">일부 비중은 데이터가 부족해 차트에서 제외했습니다.</p>';
                optionsHtml += chart('volume-comparison') + table('volume-comparison', ['동일 시각 평균', '누적 거래량 대비'], ['d3', 'd7', 'd30'].map(key => [`${key.slice(1)}일 평균 대비`, o.optionVolumeVsAvg?.windows?.[key]?.available === true ? insightExact(o.optionVolumeVsAvg.windows[key].ratioPct, '%') : '—']));
                optionsHtml += `<p class="st-note">현재 시각까지의 누적 거래량 / 과거 동일 시각 평균 · 점선 100%가 평소 수준</p><p class="st-note">최근 만기 ${insightDate(o.nearestExpiry)} · 잔여 ${insightNumber(o.daysToExpiry, '일')}</p>`;
                optionsHtml += table('option-levels', ['가격 수준', 'USD'], [['옵션 기준 주가', o.referencePrice], ['Max Pain', o.maxPain], ['Call Wall', o.callWall], ['Put Wall', o.putWall], ['Gamma Flip', o.gammaFlip]].map(([label, value]) => [label, exactMoney(value)]));
                optionsHtml += `<p class="st-gex">Net GEX <strong>${exactMoney(o.gammaPer1Pct)}</strong><small>주가 1% 변동 기준 · USD</small></p>`;
            }
            const optionsSection = module('옵션 수급', optionsHtml);
            const insider = sections.insider;
            const transactions = Array.isArray(insider?.recent) ? insider.recent.filter(t => t && typeof t === 'object') : [];
            const insiderSection = module('내부자 거래', insider ? `<p class="st-note">최근 ${insightNumber(insider.window, '일')} · ${text(insider.label)}</p>` + chart('insider-counts') + table('insider-counts', ['거래 집계', '값'], [
                ['매수 건수', insightExact(insider.buyCount, '건')], ['매도 건수', insightExact(insider.sellCount, '건')], ['기간 순거래 금액 (USD)', exactMoney(insider.netValue)],
            ]) + (transactions.length ? table('insider-transactions', ['거래일 / 이름', '거래 유형', '금액 (USD)'], transactions.map(t => [`${insightDate(t.transactionDate)}<small>${text(t.name)} · ${text(t.title)}</small>`, text(({ P: '매수 (P)', S: '매도 (S)' })[t.transactionCode] || t.transactionCode), exactMoney(t.value)])) : '') : unavailable, insider ? `SEC · ${insightDate(insider.asOf)}` : '');
            html += `<div class="st-detail-column">${revenueSection}${optionsSection}</div><div class="st-detail-column">${analystSection}${insiderSection}</div>`;
            const headlines = Array.isArray(sections.news?.items) ? sections.news.items.filter(n => n && typeof n === 'object').slice(0, 6) : [];
            if (headlines.length) {
                html += module('관련 뉴스', `<ul class="st-list st-news">${headlines.map(n => {
                    let href = '';
                    try {
                        const url = new URL(String(n.link));
                        if (url.origin === 'https://saveticker.com' && !url.username && !url.password && /^\/news\/[^/]+$/.test(url.pathname)) href = escapeHtml(url.href);
                    } catch (_) { /* Invalid provider links render as plain text. */ }
                    const title = href ? `<a href="${href}" target="_blank" rel="noopener noreferrer">${text(n.title)}</a>` : `<strong>${text(n.title)}</strong>`;
                    return `<li><div>${title}<small>${text(n.publisher)} · ${insightDate(n.published_at)}</small></div></li>`;
                }).join('')}</ul>`, 'SaveTicker');
            }
            return html + '</div></div>';
        }

        function buildInsightInfoHtml(state) {
            const data = state.data || {};
            if (['available', 'partial'].includes(data.saveticker?.status)) return buildSaveTickerInsightHtml(state);
            const fin = data.financials || {};
            const opt = data.options;
            const news = data.news;
            const rc = Number(fin.currentPrice || 0);
            let html = `<div class="insight-flat">${buildInsightChartHtml()}${buildInsightIdentityHtml(state)}
                <p class="st-note">출처: Yahoo Finance${data.saveticker ? ' · SaveTicker 미제공' : ''}</p>
                <div class="fin-grid animate-enter" style="animation-delay: 0.1s;">
                    <div class="fin-card">
                        <span>Forward P/E <span style="text-transform:none; opacity:0.6;">선행 PER</span></span>
                        <span>${fin.forwardPE !== 'N/A' ? parseFloat(fin.forwardPE).toFixed(2) : 'N/A'}</span>
                    </div>
                    <div class="fin-card">
                        <span>ROE <span style="text-transform:none; opacity:0.6;">자기자본이익률</span></span>
                        <span>${fin.returnOnEquity !== 'N/A' ? (parseFloat(fin.returnOnEquity) * 100).toFixed(2) + '%' : 'N/A'}</span>
                    </div>
                    <div class="fin-card">
                        <span>D/E <span style="text-transform:none; opacity:0.6;">부채비율</span></span>
                        <span>${fin.debtToEquity !== 'N/A' ? parseFloat(fin.debtToEquity).toFixed(2) : 'N/A'}</span>
                    </div>
                    <div class="fin-card">
                        <span>Beta <span style="text-transform:none; opacity:0.6;">변동성</span></span>
                        <span>${fin.beta !== 'N/A' ? parseFloat(fin.beta).toFixed(2) : 'N/A'}</span>
                    </div>
                    <div class="fin-card">
                        <span>Mkt Cap <span style="text-transform:none; opacity:0.6;">시가총액</span></span>
                        <span>${fin.marketCap !== 'N/A' ? (fin.marketCap >= 1e12 ? (fin.marketCap / 1e12).toFixed(2) + 'T' : fin.marketCap >= 1e9 ? (fin.marketCap / 1e9).toFixed(2) + 'B' : (fin.marketCap / 1e6).toFixed(0) + 'M') : 'N/A'}</span>
                    </div>
                    <div class="fin-card">
                        <span>Short <span style="text-transform:none; opacity:0.6;">공매도비중</span></span>
                        <span style="color: ${fin.shortPercentOfFloat !== 'N/A' && parseFloat(fin.shortPercentOfFloat) > 0.1 ? 'var(--loss)' : 'var(--text-main)'}">${fin.shortPercentOfFloat !== 'N/A' ? (parseFloat(fin.shortPercentOfFloat) * 100).toFixed(2) + '%' : 'N/A'}</span>
                    </div>
                    <div class="fin-card">
                        <span>Target <span style="text-transform:none; opacity:0.6;">목표가</span></span>
                        <span style="color: ${fin.targetMeanPrice !== 'N/A' && parseFloat(fin.targetMeanPrice) > rc ? 'var(--profit)' : (fin.targetMeanPrice !== 'N/A' ? 'var(--loss)' : 'var(--text-main)')}">${fin.targetMeanPrice !== 'N/A' ? fin.currency + ' ' + parseFloat(fin.targetMeanPrice).toFixed(2) : 'N/A'}</span>
                    </div>
                    <div class="fin-card">
                        <span style="text-transform: none;">Analyst <span style="opacity:0.6;">분석가 평가</span></span>
                        <span style="text-transform: capitalize; color: ${fin.recommendation === 'buy' || fin.recommendation === 'strong_buy' ? 'var(--profit)' : (fin.recommendation === 'sell' || fin.recommendation === 'strong_sell' ? 'var(--loss)' : 'var(--text-main)')}">${(fin.recommendation || 'N/A').replace('_', ' ')}</span>
                    </div>
                </div>`;

            if (fin.fiftyTwoWeekLow !== 'N/A' && fin.fiftyTwoWeekHigh !== 'N/A') {
                const low52 = parseFloat(fin.fiftyTwoWeekLow);
                const high52 = parseFloat(fin.fiftyTwoWeekHigh);
                const range52 = high52 - low52;
                const pos52 = range52 > 0 ? Math.min(100, Math.max(0, ((rc - low52) / range52) * 100)) : 50;
                html += `
                    <div class="animate-enter" style="animation-delay: 0.15s; padding: 10px 0;">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 6px;">
                            <span style="font-size:10px; color:var(--text-muted); text-transform:uppercase; letter-spacing:0.5px;">52주 범위</span>
                            <span style="font-size:11px; color:var(--text-sub);">${fin.currency} ${low52.toFixed(2)} — ${high52.toFixed(2)}</span>
                        </div>
                        <div style="position:relative; width:100%; height:6px; background: linear-gradient(90deg, var(--loss), var(--accent-gold), var(--profit)); border-radius:3px;">
                            <div style="position:absolute; top:-3px; left:${pos52}%; transform:translateX(-50%); width:12px; height:12px; background:var(--chart-marker); border-radius:50%; box-shadow: 0 0 6px var(--border-subtle);"></div>
                        </div>
                    </div>
                `;
            }


            let newsHtml = '';
            if (news && news.length > 0) {
                news.forEach((n) => {
                    newsHtml += `
                        <a href="${insightSafeUrl(n.link)}" target="_blank" rel="noopener noreferrer" class="news-item">
                            <span class="news-title">${escapeHtml(n.title)}</span>
                            <span class="news-meta">${escapeHtml(n.publisher)}</span>
                        </a>
                    `;
                });
            } else {
                newsHtml = `<div style="color:var(--text-muted); font-size:13px; padding:12px;">최신 뉴스가 없습니다.</div>`;
            }

            let optHtml = '';
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
                const oiConfidence = opt.oi_confidence || { level: oiAvailable ? 'low' : 'none', label: oiAvailable ? '낮음' : '미산출', reason: oiAvailable ? '근월물 OI 규모가 작습니다.' : '근월물 OI 데이터가 없습니다.' };
                const maxPainConfidence = opt.max_pain_confidence || { level: opt.max_pain_available ? 'low' : 'none', label: opt.max_pain_available ? '낮음' : '미산출', reason: opt.max_pain_available ? '근월물 OI를 참고 계산했습니다.' : '근월물 OI가 부족해 맥스페인을 계산할 수 없습니다.' };
                const confidenceColors = {
                    high: 'rgba(16,185,129,0.18)',
                    medium: 'rgba(245,158,11,0.18)',
                    low: 'rgba(239,68,68,0.18)',
                    none: 'rgba(148,163,184,0.16)',
                };
                const confidenceTextColors = {
                    high: 'var(--profit)',
                    medium: 'var(--accent-gold)',
                    low: 'var(--loss)',
                    none: 'var(--text-sub)',
                };
                const renderConfidenceBadge = (meta) => {
                    const level = meta && meta.level ? meta.level : 'none';
                    const label = meta && meta.label ? meta.label : '미산출';
                    const background = confidenceColors[level] || confidenceColors.none;
                    const color = confidenceTextColors[level] || confidenceTextColors.none;
                    return `<span style="display:inline-flex; align-items:center; padding:3px 8px; border-radius:999px; background:${background}; color:${color}; font-size:10px; font-weight:700; letter-spacing:0.02em;">${label}</span>`;
                };
                const maxPainAvailable = opt.max_pain_available === true || (Number.isFinite(Number(opt.max_pain)) && Number(opt.max_pain) > 0);
                const maxPainText = maxPainAvailable ? strikePrefix + formatNumber(opt.max_pain) : '미산출';
                const oiCallText = oiAvailable ? `${formatNumber(opt.calls_oi)}계약` : '데이터 미제공';
                const oiPutText = oiAvailable ? `${formatNumber(opt.puts_oi)}계약` : '데이터 미제공';
                const oiHint = `<div style="display:flex; justify-content:space-between; gap:12px; align-items:flex-start; margin-top:8px;"><div style="font-size:10px; color:var(--text-muted); line-height:1.45;">${oiConfidence.reason || '근월물 OI 품질을 기준으로 해석합니다.'}</div>${renderConfidenceBadge(oiConfidence)}</div>`;
                const maxPainHint = `<div style="font-size:10px; color:var(--text-muted); line-height:1.45; text-align:right;">${maxPainConfidence.reason || '근월물 OI 기반으로 계산합니다.'}</div>`;

                optHtml = `
                    <div style="padding: 0; display: flex; flex-direction: column; gap: 18px;">
                        <div style="display:flex; justify-content:space-between; align-items:center;">
                            <div>
                                <div style="font-size: 11px; color: var(--text-muted); margin-bottom: 2px;">만기일</div>
                                <div style="font-size: 15px; font-weight: 700; color: var(--text-main);">${opt.date}</div>
                            </div>
                            <div style="text-align:right;">
                                <div style="font-size: 11px; color: var(--text-muted); margin-bottom: 2px;">ATM IV</div>
                                <div style="font-size: 15px; font-weight: 700; color: ${opt.atm_iv && opt.atm_iv > 50 ? 'var(--accent-gold)' : 'var(--text-main)'};">${ivText}</div>
                            </div>
                        </div>
                        <div>
                            <div style="display:flex; justify-content:space-between; font-size:12px; font-weight: 500; margin-bottom:6px;">
                                <span style="color:var(--profit);">콜 ${callPct}% <span style="font-size: 10px; opacity: 0.7;">(${formatNumber(callVol)}계약)</span></span>
                                <span style="font-size:11px; color:var(--text-muted);">PCR(${pcrBasis}) ${pcr}</span>
                                <span style="color:var(--loss);">풋 ${putPct}% <span style="font-size: 10px; opacity: 0.7;">(${formatNumber(putVol)}계약)</span></span>
                            </div>
                            <div style="width:100%; height:8px; background:var(--surface-subtle); border-radius:4px; display:flex; overflow:hidden;">
                                <div style="width:${callPct}%; background:var(--profit);"></div>
                                <div style="width:${putPct}%; background:var(--loss);"></div>
                            </div>
                        </div>
                        <div style="display:grid; grid-template-columns: 1fr 1fr; gap:10px; padding: 10px 0;">
                            <div>
                                <div style="font-size:10px; color:var(--text-muted); margin-bottom: 2px;">OI (Call)</div>
                                <div style="font-size:14px; font-weight:700; color:var(--text-main);">${oiCallText}</div>
                            </div>
                            <div style="text-align:right;">
                                <div style="font-size:10px; color:var(--text-muted); margin-bottom: 2px;">OI (Put)</div>
                                <div style="font-size:14px; font-weight:700; color:var(--text-main);">${oiPutText}</div>
                            </div>
                        </div>
                        ${oiHint}
                        <div style="display:flex; flex-direction: column; gap:6px; border-top: 1px solid var(--border-subtle); padding-top: 10px;">
                            <div style="display:flex; justify-content:space-between; align-items:center;">
                                <div style="display:flex; align-items:center; gap:8px; min-width:0;">
                                    <div style="font-size:11px; color:var(--text-muted);">Max Pain</div>
                                    ${renderConfidenceBadge(maxPainConfidence)}
                                </div>
                                <div style="font-size:13px; font-weight:700; color:${maxPainAvailable ? 'var(--accent-gold)' : 'var(--text-sub)'};">${maxPainText}</div>
                            </div>
                            ${maxPainHint}
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
                <div class="animate-enter" style="display:grid; grid-template-columns:1fr 1fr; gap:20px; align-items:stretch; animation-delay:0.25s;">
                    <div style="display:flex; flex-direction:column;">
                        <div class="news-header">관련 뉴스</div>
                        <div class="news-list" style="flex:1;">
                            ${newsHtml}
                        </div>
                    </div>
                    <div style="display:flex; flex-direction:column;">
                        <div class="news-header">단기 옵션 현황</div>
                        ${optHtml}
                    </div>
                </div>
            `;
            return html + '</div>';
        }

        function initializeInsightChart(state) {
            disposeInsightChart();
            if (!state || state.status !== 'success') return;
            const chartContainer = document.getElementById('tv_chart_container');
            if (!chartContainer) return;
            chartContainer.innerHTML = '';
            const { marketType, tvTicker, data } = state;
            if (marketType === 'USA') {
                try {
                    insightChartKind = 'tradingview';
                    insightChart = new TradingView.widget({
                        autosize: true,
                        symbol: tvTicker,
                        interval: 'D',
                        timezone: 'Asia/Seoul',
                        theme: resolvedTheme(),
                        style: '1',
                        locale: 'kr',
                        enable_publishing: false,
                        backgroundColor: 'rgba(0,0,0,0)',
                        gridColor: themeColor('--chart-grid'),
                        hide_top_toolbar: false,
                        hide_legend: false,
                        save_image: false,
                        allow_symbol_change: true,
                        container_id: 'tv_chart_container',
                    });
                } catch (tvErr) {
                    console.warn('TradingView widget failed:', tvErr);
                }
                return;
            }

            const historyData = data.history || [];
            const tvContainer = document.getElementById('tv_chart_container');
            if (historyData.length > 0 && tvContainer && typeof LightweightCharts !== 'undefined') {
                tvContainer.innerHTML = '';
                const chart = LightweightCharts.createChart(tvContainer, {
                    width: tvContainer.clientWidth,
                    height: tvContainer.clientHeight,
                    ...insightChartThemeOptions(),
                    crosshair: { mode: 0 },
                });
                insightChart = chart;
                insightChartKind = 'lightweight';
                const candleSeries = chart.addCandlestickSeries(candleThemeOptions());
                insightCandleSeries = candleSeries;
                candleSeries.setData(historyData);

                const volumeSeries = chart.addHistogramSeries({
                    color: themeColor('--chart-volume-up'),
                    priceFormat: { type: 'volume' },
                    priceScaleId: '',
                });
                volumeSeries.priceScale().applyOptions({
                    scaleMargins: { top: 0.85, bottom: 0 },
                });
                insightVolumeSeries = volumeSeries;
                volumeSeries.setData(volumeChartData(historyData));
                chart.timeScale().fitContent();
                const resizeObserver = new ResizeObserver(() => {
                    if (insightChart === chart && tvContainer.isConnected) chart.applyOptions({ width: tvContainer.clientWidth, height: tvContainer.clientHeight });
                });
                insightChartObserver = resizeObserver;
                resizeObserver.observe(tvContainer);
                return;
            }

            if (tvContainer) {
                tvContainer.innerHTML = `
                    <div style="display:flex; flex-direction:column; align-items:center; justify-content:center; height:100%; gap:8px; color:var(--text-muted); font-size:13px;">
                        <span>차트 데이터를 불러올 수 없습니다.</span>
                    </div>
                `;
            }
        }

        function updateInsightSourceLink(state) {
            const link = document.getElementById('insightSaveTickerLink');
            if (!link) return;
            const available = state?.marketType === 'USA' && !!state.ticker;
            link.hidden = !available;
            if (available) link.href = `https://saveticker.com/company/${encodeURIComponent(state.ticker)}`;
        }

        function renderCurrentInsightContent() {
            updateInsightSourceLink(currentInsightState);
            const insightContainer = document.getElementById('insight_content');
            if (!insightContainer) return;
            disposeInsightChart();


            if (!currentInsightState) {
                insightContainer.innerHTML = `
                    <div style="color:var(--text-muted); text-align:center; padding:20px;">
                        종목을 선택하면 상세 정보가 표시됩니다.
                    </div>
                `;
                return;
            }

            if (currentInsightState.status === 'loading') {
                insightContainer.innerHTML = buildInsightLoadingHtml(currentInsightState);
                return;
            }

            if (currentInsightState.status === 'error') {
                insightContainer.innerHTML = buildInsightErrorHtml(currentInsightState);
                return;
            }

            insightContainer.innerHTML = buildInsightInfoHtml(currentInsightState);
            initializeInsightChart(currentInsightState);
            initializeInsightDetailCharts(currentInsightState);
        }

        // Asset Insight Fetcher
        async function fetchAssetInsight(ticker, marketType) {
            marketType = marketType || 'USA';
            openInsightPane();
            currentInsightRequestSeq += 1;
            const requestSeq = currentInsightRequestSeq;

            let tvTicker = ticker;
            if (marketType === 'KOR') {
                tvTicker = 'KRX:' + normalizeTicker(ticker);
            } else if (marketType === 'JPN') {
                tvTicker = 'TSE:' + normalizeTicker(ticker);
            } else if (ticker.endsWith('.KS') || ticker.endsWith('.KQ')) {
                tvTicker = 'KRX:' + ticker.split('.')[0];
            }

            currentInsightState = {
                status: 'loading',
                ticker,
                marketType,
                tvTicker,
            };
            renderCurrentInsightContent();

            try {
                const res = await fetch(`/api/asset-insight?ticker=${ticker}&market_type=${marketType}`);
                const result = await res.json();
                if (requestSeq !== currentInsightRequestSeq) {
                    return;
                }

                if (result.status === 'success') {
                    const data = result.data;
                    const fin = data.financials || {};
                    let tvLogoUrl = '';
                    let cleanTicker = ticker;
                    if (ticker.endsWith('.KS') || ticker.endsWith('.KQ')) {
                        cleanTicker = ticker.split('.')[0];
                        tvLogoUrl = `https://s3-symbol-logo.tradingview.com/${cleanTicker}--big.svg`;
                    } else if (!ticker.includes('.')) {
                        tvLogoUrl = `https://s3-symbol-logo.tradingview.com/${cleanTicker.toLowerCase()}--big.svg`;
                    }

                    const fallbackAvatar = `https://ui-avatars.com/api/?name=${encodeURIComponent(fin.shortName || ticker)}&background=random&color=fff&size=64`;
                    let imgHtml = '';
                    if (tvLogoUrl) {
                        imgHtml = `<img src="${tvLogoUrl}" alt="${escapeHtml(fin.shortName || ticker)} 로고" onerror="this.onerror=function(){this.onerror=null; this.src='${fallbackAvatar}';}; this.src='${fin.logo_url ? fin.logo_url : fallbackAvatar}';" style="width: 40px; height: 40px; border-radius: 50%; object-fit: contain; background: var(--logo-background); padding: 2px;">`;
                    } else if (fin.logo_url) {
                        imgHtml = `<img src="${fin.logo_url}" alt="${escapeHtml(fin.shortName || ticker)} 로고" onerror="this.onerror=null; this.src='${fallbackAvatar}';" style="width: 40px; height: 40px; border-radius: 50%; object-fit: contain; background: var(--logo-background); padding: 2px;">`;
                    } else {
                        imgHtml = `<img src="${fallbackAvatar}" alt="${escapeHtml(fin.shortName || ticker)} 로고" style="width: 40px; height: 40px; border-radius: 50%; object-fit: contain; background: var(--logo-background); padding: 2px;">`;
                    }

                    currentInsightState = {
                        status: 'success',
                        ticker,
                        marketType,
                        tvTicker,
                        data,
                        imgHtml,
                    };
                    renderCurrentInsightContent();
                    return;
                }

                currentInsightState = {
                    status: 'error',
                    ticker,
                    marketType,
                    tvTicker,
                    message: result.message || '정보를 불러오는 데 실패했습니다.',
                };
                renderCurrentInsightContent();
            } catch (err) {
                if (requestSeq !== currentInsightRequestSeq) {
                    return;
                }
                console.error('fetchAssetInsight error', err);
                currentInsightState = {
                    status: 'error',
                    ticker,
                    marketType,
                    tvTicker,
                    message: '정보를 불러오는 데 실패했습니다.',
                };
                renderCurrentInsightContent();
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

        // 계좌 관리
        let accountList = [];
        let accountDeleteTarget = null;
        let accountEditTarget = null;
        const dashboardTossLookupTimers = new Map();

        function sanitizeAccountNumberInput(input) {
            const digits = input.value.replace(/\D/g, '').slice(0, 19);
            if (input.value !== digits) {
                input.value = digits;
            }
        }

        function parseAccountNumberInput(raw) {
            const digits = String(raw || '').replace(/\D/g, '');
            const editBroker = document.getElementById('edit_account_broker')?.value;
            const editModalActive = document.getElementById('accountEditModal')?.classList.contains('active');
            if (editModalActive && editBroker === 'toss') {
                return digits && Number(digits) > 0
                    ? { cano: digits, acnt_prdt_cd: '' }
                    : null;
            }
            if (digits.length !== 8 && digits.length !== 10) return null;
            return {
                cano: digits.slice(0, 8),
                acnt_prdt_cd: digits.length === 10 ? digits.slice(8) : '01'
            };
        }

        function parseBrokerAccountInput(raw, broker) {
            const digits = String(raw || '').replace(/\D/g, '');
            if (broker === 'toss') {
                return digits && Number(digits) > 0
                    ? { cano: digits, acnt_prdt_cd: '' }
                    : null;
            }
            return parseAccountNumberInput(digits);
        }

        function updateAccountFormFields(prefix) {
            const broker = document.getElementById(`${prefix}_account_broker`)?.value || 'kis';
            const isToss = broker === 'toss';
            const keyLabel = document.getElementById(`${prefix}_account_key_label`);
            const secretLabel = document.getElementById(`${prefix}_account_secret_label`);
            const refLabel = document.getElementById(`${prefix}_account_ref_label`);
            const refInput = document.getElementById(`${prefix}_account_cano`);
            const tossPicker = document.getElementById(`${prefix}_toss_account_picker`);
            if (keyLabel) keyLabel.textContent = isToss ? 'CLIENT ID' : 'APP KEY';
            if (secretLabel) secretLabel.textContent = isToss ? 'CLIENT SECRET' : 'APP SECRET';
            if (refLabel) refLabel.textContent = isToss ? '토스 계좌' : '계좌번호 (8자리 + 상품코드 2자리)';
            if (refInput) {
                refInput.maxLength = isToss ? 19 : 10;
                refInput.placeholder = isToss ? '' : '예: 1234567801';
                refInput.style.display = isToss ? 'none' : '';
            }
            if (tossPicker) tossPicker.classList.toggle('active', isToss);
        }

        function handleAccountBrokerChange(prefix) {
            document.getElementById(`${prefix}_account_app_key`).value = '';
            document.getElementById(`${prefix}_account_app_secret`).value = '';
            document.getElementById(`${prefix}_account_cano`).value = '';
            resetDashboardTossPicker(prefix);
            updateAccountFormFields(prefix);
        }

        function resetDashboardTossPicker(prefix, message = '') {
            const select = document.getElementById(`${prefix}_toss_account_select`);
            const status = document.getElementById(`${prefix}_toss_account_status`);
            if (select) {
                select.innerHTML = `<option value="">${prefix === 'edit' ? '현재 계좌를 유지합니다' : 'CLIENT ID와 SECRET을 입력하세요'}</option>`;
                select.disabled = true;
            }
            if (status) status.textContent = message;
        }

        function selectDashboardTossAccount(prefix, accountSeq) {
            const input = document.getElementById(`${prefix}_account_cano`);
            if (input) input.value = String(accountSeq || '');
        }

        function scheduleDashboardTossLookup(prefix, delay = 700) {
            const broker = document.getElementById(`${prefix}_account_broker`)?.value;
            if (broker !== 'toss') return;
            const input = document.getElementById(`${prefix}_account_cano`);
            if (input) input.value = '';
            resetDashboardTossPicker(prefix);
            const previous = dashboardTossLookupTimers.get(prefix);
            if (previous) window.clearTimeout(previous);
            const clientId = document.getElementById(`${prefix}_account_app_key`)?.value.trim();
            const clientSecret = document.getElementById(`${prefix}_account_app_secret`)?.value;
            if (!clientId || !clientSecret) return;
            dashboardTossLookupTimers.set(
                prefix,
                window.setTimeout(() => discoverDashboardTossAccounts(prefix), delay)
            );
        }

        async function discoverDashboardTossAccounts(prefix) {
            const broker = document.getElementById(`${prefix}_account_broker`)?.value;
            if (broker !== 'toss') return;
            const clientId = document.getElementById(`${prefix}_account_app_key`)?.value.trim() || '';
            const clientSecret = document.getElementById(`${prefix}_account_app_secret`)?.value || '';
            const pin = document.getElementById(`${prefix}_account_pin`)?.value || '';
            const currentSeq = document.getElementById(`${prefix}_account_cano`)?.value || '';
            const button = document.getElementById(`${prefix}_toss_account_lookup`);
            const status = document.getElementById(`${prefix}_toss_account_status`);
            const select = document.getElementById(`${prefix}_toss_account_select`);
            const payload = clientId && clientSecret
                ? { client_id: clientId, client_secret: clientSecret }
                : prefix === 'edit' && accountEditTarget?.accountId && pin
                    ? { account_id: accountEditTarget.accountId, pin }
                    : null;
            if (!payload) {
                if (status) {
                    status.textContent = prefix === 'edit'
                        ? '새 자격증명 또는 PIN을 입력한 뒤 계좌를 불러오세요.'
                        : 'CLIENT ID와 CLIENT SECRET을 먼저 입력하세요.';
                }
                return;
            }

            if (button) {
                button.disabled = true;
                button.textContent = '계좌 조회 중...';
            }
            if (status) status.textContent = '토스증권에서 계좌 목록을 조회하고 있습니다.';
            try {
                const response = await fetch('/api/toss/accounts/discover', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await response.json().catch(() => ({}));
                if (!response.ok) throw new Error(data.detail || '토스 계좌 조회에 실패했습니다.');
                const accounts = Array.isArray(data.accounts) ? data.accounts : [];
                if (select) {
                    select.innerHTML = '<option value="">계좌를 선택하세요</option>';
                    accounts.forEach((account) => {
                        const option = document.createElement('option');
                        option.value = String(account.account_seq || '');
                        option.textContent = String(account.display_name || `토스증권 계좌 #${account.account_seq}`);
                        select.appendChild(option);
                    });
                    select.disabled = accounts.length === 0;
                }
                const retained = accounts.some((account) => String(account.account_seq) === currentSeq)
                    ? currentSeq
                    : '';
                const selected = accounts.length === 1 ? String(accounts[0].account_seq) : retained;
                if (select) select.value = selected;
                selectDashboardTossAccount(prefix, selected);
                if (status) {
                    status.textContent = accounts.length === 1
                        ? `${accounts[0].display_name} 계좌가 자동 선택됐습니다.`
                        : accounts.length > 1
                            ? '사용할 토스 계좌를 선택하세요.'
                            : '사용 가능한 토스증권 계좌가 없습니다.';
                }
            } catch (error) {
                resetDashboardTossPicker(prefix, error.message || '토스 계좌 조회에 실패했습니다.');
            } finally {
                if (button) {
                    button.disabled = false;
                    button.textContent = '토스 계좌 불러오기';
                }
            }
        }

        function openAccountModal() {
            document.getElementById('accountModal').classList.add('active');
            document.addEventListener('keydown', handleAccountModalKeydown);
            loadAccountList();
            window.setTimeout(() => {
                const firstField = document.getElementById('new_account_label');
                if (firstField) firstField.focus();
            }, 80);
        }

        function closeAccountModal() {
            document.getElementById('accountModal').classList.remove('active');
            document.removeEventListener('keydown', handleAccountModalKeydown);
            clearAccountNotice();
            document.getElementById('accountAddForm').reset();
            resetDashboardTossPicker('new');
            updateAccountFormFields('new');
        }

        function handleAccountOverlayClick(event) {
            if (event.target.id === 'accountModal') {
                closeAccountModal();
            }
        }

        function handleAccountModalClick(_event) {
        }

        function handleAccountModalKeydown(event) {
            if (event.key !== 'Escape') return;
            const editModal = document.getElementById('accountEditModal');
            if (editModal && editModal.classList.contains('active')) {
                closeAccountEditModal();
                return;
            }
            const deleteModal = document.getElementById('accountDeleteModal');
            if (deleteModal && deleteModal.classList.contains('active')) {
                closeAccountDeleteModal();
                return;
            }
            const accountModal = document.getElementById('accountModal');
            if (accountModal && accountModal.classList.contains('active')) {
                closeAccountModal();
            }
        }

        async function loadAccountList(force = false) {
            const listEl = document.getElementById('accountList');
            const emptyEl = document.getElementById('accountListEmpty');
            const loadingEl = document.getElementById('accountListLoading');
            if (!listEl || !emptyEl || !loadingEl) return;
            listEl.innerHTML = '';
            emptyEl.classList.remove('active');
            loadingEl.classList.add('active');
            try {
                const res = await fetch('/api/accounts', { cache: force ? 'no-store' : 'default' });
                if (res.status === 401) {
                    window.location.href = '/login';
                    return;
                }
                const data = await res.json();
                if (data.status === 'success' && Array.isArray(data.accounts)) {
                    accountList = data.accounts;
                    renderAccountList();
                } else {
                    showAccountNotice(data.detail || '계좌 목록을 불러오지 못했습니다.', true);
                }
            } catch (err) {
                console.error('loadAccountList error', err);
                showAccountNotice('계좌 목록을 불러오지 못했습니다.', true);
            } finally {
                loadingEl.classList.remove('active');
            }
        }

        function renderAccountList() {
            const listEl = document.getElementById('accountList');
            const emptyEl = document.getElementById('accountListEmpty');
            if (!listEl || !emptyEl) return;
            if (!accountList.length) {
                listEl.innerHTML = '';
                emptyEl.classList.add('active');
                return;
            }
            emptyEl.classList.remove('active');
            listEl.innerHTML = accountList.map((acc) => {
                const safeId = escapeAttributeValue(String(acc.account_id || ''));
                const safeLabel = escapeHtml(String(acc.label || ''));
                const safeLabelAttr = escapeAttributeValue(String(acc.label || ''));
                const safeCano = escapeHtml(String(acc.cano_masked || ''));
                const safePrdt = escapeHtml(String(acc.acnt_prdt_cd || ''));
                const safePrdtAttr = escapeAttributeValue(String(acc.acnt_prdt_cd || ''));
                const safeCanoAttr = escapeAttributeValue(String(acc.cano || ''));
                const safeMaskedCanoAttr = escapeAttributeValue(String(acc.cano_masked || ''));
                const broker = String(acc.broker || 'kis');
                const brokerName = escapeHtml(String(acc.broker_name || (broker === 'toss' ? '토스증권' : '한국투자증권')));
                const refLabel = escapeHtml(String(acc.account_ref_label || '계좌번호'));
                const brokerBadge = `<span class="badge badge-account">${brokerName}</span>`;
                const primaryBadge = acc.is_primary
                    ? '<span class="badge badge-account-primary">기본</span>'
                    : '';
                return `
                    <div class="account-list-item">
                        <div class="account-list-item-main">
                            <div class="account-list-item-title">
                                <strong>${safeLabel}</strong>
                                ${brokerBadge}
                                ${primaryBadge}
                            </div>
                            <div class="account-list-item-meta">
                                ${refLabel} ${safeCano}${broker === 'kis' ? ` · 상품코드 ${safePrdt}` : ''}
                            </div>
                        </div>
                        <div class="account-list-item-actions">
                            <button type="button" class="btn-edit-account"
                                data-account-id="${safeId}" data-account-label="${safeLabelAttr}"
                                data-account-cano="${safeCanoAttr}" data-account-product="${safePrdtAttr}"
                                data-account-masked-cano="${safeMaskedCanoAttr}"
                                onclick="requestEditAccount(this)">수정</button>
                            <button type="button" class="btn-delete-account"
                                data-account-id="${safeId}" data-account-label="${safeLabelAttr}"
                                onclick="requestDeleteAccount(this)">삭제</button>
                        </div>
                    </div>
                `;
            }).join('');
        }

        function showAccountNotice(message, isError) {
            const notice = document.getElementById('accountModalNotice');
            if (!notice) return;
            notice.textContent = message || '';
            notice.classList.toggle('form-notice--error', !!isError);
            notice.classList.toggle('form-notice--success', !isError);
            notice.classList.add('active');
        }

        function clearAccountNotice() {
            const notice = document.getElementById('accountModalNotice');
            if (!notice) return;
            notice.textContent = '';
            notice.className = 'form-notice';
        }

        async function submitAddAccount(event) {
            event.preventDefault();
            const submitBtn = document.getElementById('accountAddSubmitBtn');
            const label = document.getElementById('new_account_label').value.trim();
            const broker = document.getElementById('new_account_broker').value;
            const appKey = document.getElementById('new_account_app_key').value.trim();
            const appSecret = document.getElementById('new_account_app_secret').value;
            const parsed = parseBrokerAccountInput(document.getElementById('new_account_cano').value, broker);
            const pin = document.getElementById('new_account_pin').value;

            const errors = [];
            if (!label) errors.push('계좌 이름을 입력하세요.');
            if (!appKey) errors.push(broker === 'toss' ? 'CLIENT ID를 입력하세요.' : 'APP KEY를 입력하세요.');
            if (!appSecret) errors.push(broker === 'toss' ? 'CLIENT SECRET을 입력하세요.' : 'APP SECRET을 입력하세요.');
            if (!parsed) errors.push(broker === 'toss' ? '토스 계좌를 불러와 선택하세요.' : '계좌번호는 숫자 8자리 또는 10자리로 입력하세요.');
            if (!/^\d{4,6}$/.test(pin)) errors.push('PIN은 4~6자리 숫자로 입력하세요.');
            if (errors.length) {
                showAccountNotice(errors.join(' '), true);
                return;
            }

            clearAccountNotice();
            submitBtn.disabled = true;
            const originalText = submitBtn.innerText;
            submitBtn.innerText = '추가 중...';
            try {
                const res = await fetch('/api/accounts', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        pin,
                        label,
                        broker,
                        app_key: appKey,
                        app_secret: appSecret,
                        cano: parsed.cano,
                        acnt_prdt_cd: parsed.acnt_prdt_cd
                    })
                });
                const data = await res.json().catch(() => ({}));
                if (res.ok && data.status === 'success') {
                    showAccountNotice(data.message || '계좌가 추가되었습니다.', false);
                    document.getElementById('accountAddForm').reset();
                    resetDashboardTossPicker('new');
                    updateAccountFormFields('new');
                    await loadAccountList(true);
                    syncData(true);
                } else {
                    showAccountNotice(data.detail || '계좌 추가에 실패했습니다.', true);
                }
            } catch (err) {
                console.error('submitAddAccount error', err);
                showAccountNotice('계좌 추가에 실패했습니다.', true);
            } finally {
                submitBtn.disabled = false;
                submitBtn.innerText = originalText;
            }
        }

        function requestDeleteAccount(btn) {
            const accountId = btn.getAttribute('data-account-id');
            const label = btn.getAttribute('data-account-label') || '계좌';
            accountDeleteTarget = { accountId, label };
            const labelEl = document.getElementById('accountDeleteLabel');
            if (labelEl) labelEl.innerText = label;
            clearAccountDeleteError();
            document.getElementById('accountDeletePin').value = '';
            document.getElementById('accountDeleteModal').classList.add('active');
            window.setTimeout(() => {
                const pinField = document.getElementById('accountDeletePin');
                if (pinField) pinField.focus();
            }, 80);
        }

        function closeAccountDeleteModal() {
            document.getElementById('accountDeleteModal').classList.remove('active');
            accountDeleteTarget = null;
            clearAccountDeleteError();
        }

        function handleAccountDeleteOverlayClick(event) {
            if (event.target.id === 'accountDeleteModal') {
                closeAccountDeleteModal();
            }
        }

        function handleAccountDeleteModalClick(_event) {
        }

        function clearAccountDeleteError() {
            const errEl = document.getElementById('accountDeleteError');
            if (!errEl) return;
            errEl.textContent = '';
            errEl.className = 'form-notice';
        }

        async function confirmDeleteAccount() {
            if (!accountDeleteTarget) return;
            const pin = document.getElementById('accountDeletePin').value;
            const errEl = document.getElementById('accountDeleteError');
            const confirmBtn = document.getElementById('accountDeleteConfirmBtn');
            if (!/^\d{4,6}$/.test(pin)) {
                errEl.textContent = 'PIN은 4~6자리 숫자로 입력하세요.';
                errEl.classList.add('form-notice--error', 'active');
                return;
            }
            clearAccountDeleteError();
            confirmBtn.disabled = true;
            const originalText = confirmBtn.innerText;
            confirmBtn.innerText = '삭제 중...';
            try {
                const res = await fetch(`/api/accounts/${encodeURIComponent(accountDeleteTarget.accountId)}/delete`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ pin })
                });
                const data = await res.json().catch(() => ({}));
                if (res.ok && data.status === 'success') {
                    closeAccountDeleteModal();
                    showAccountNotice(data.message || '계좌가 삭제되었습니다.', false);
                    await loadAccountList(true);
                    syncData(true);
                } else {
                    errEl.textContent = data.detail || '계좌 삭제에 실패했습니다.';
                    errEl.classList.add('form-notice--error', 'active');
                }
            } catch (err) {
                console.error('confirmDeleteAccount error', err);
                errEl.textContent = '계좌 삭제에 실패했습니다.';
                errEl.classList.add('form-notice--error', 'active');
            } finally {
                confirmBtn.disabled = false;
                confirmBtn.innerText = originalText;
            }
        }

        function requestEditAccount(btn) {
            const accountId = btn.getAttribute('data-account-id');
            const account = accountList.find(
                (acc) => String(acc.account_id || '') === accountId
            ) || {};
            accountEditTarget = { accountId, broker: String(account.broker || 'kis') };

            const label = String(
                account.label || btn.getAttribute('data-account-label') || '계좌'
            );
            const broker = String(account.broker || 'kis');
            const product = String(
                account.acnt_prdt_cd || btn.getAttribute('data-account-product') || '01'
            );
            const fullCano = String(
                account.cano || btn.getAttribute('data-account-cano') || ''
            );
            const maskedCano = String(
                account.cano_masked || btn.getAttribute('data-account-masked-cano') || ''
            );

            const labelHeader = document.getElementById('accountEditLabel');
            if (labelHeader) labelHeader.innerText = label;
            document.getElementById('edit_account_label').value = label;
            document.getElementById('edit_account_broker').value = broker;
            updateAccountFormFields('edit');
            document.getElementById('edit_account_app_key').value = '';
            document.getElementById('edit_account_app_secret').value = '';
            document.getElementById('edit_account_pin').value = '';

            const canoInput = document.getElementById('edit_account_cano');
            if (fullCano) {
                canoInput.value = fullCano + product;
                if (broker === 'toss') {
                    canoInput.value = fullCano;
                    resetDashboardTossPicker('edit', '현재 토스 계좌를 유지합니다. 변경하려면 계좌를 불러오세요.');
                }
                setAccountEditCanoHint('');
            } else {
                canoInput.value = '';
                setAccountEditCanoHint(
                    maskedCano
                        ? `현재: ${maskedCano} · 상품코드 ${product}`
                        : `현재 상품코드: ${product}`
                );
            }

            clearAccountEditError();
            document.getElementById('accountEditModal').classList.add('active');
            window.setTimeout(() => {
                const firstField = document.getElementById('edit_account_label');
                if (firstField) firstField.focus();
            }, 80);
        }

        function closeAccountEditModal() {
            document.getElementById('accountEditModal').classList.remove('active');
            accountEditTarget = null;
            clearAccountEditError();
            document.getElementById('accountEditForm').reset();
            resetDashboardTossPicker('edit');
            setAccountEditCanoHint('');
        }

        function handleAccountEditOverlayClick(event) {
            if (event.target.id === 'accountEditModal') {
                closeAccountEditModal();
            }
        }

        function handleAccountEditModalClick(_event) {
        }

        function clearAccountEditError() {
            const errEl = document.getElementById('accountEditError');
            if (!errEl) return;
            errEl.textContent = '';
            errEl.className = 'form-notice';
        }

        function showAccountEditError(message) {
            const errEl = document.getElementById('accountEditError');
            if (!errEl) return;
            errEl.textContent = message || '';
            errEl.classList.add('form-notice--error', 'active');
        }

        function setAccountEditCanoHint(text) {
            const hintEl = document.getElementById('edit_account_cano_hint');
            if (hintEl) hintEl.textContent = text || '';
        }

        async function submitEditAccount(event) {
            event.preventDefault();
            if (!accountEditTarget) return;
            const saveBtn = document.getElementById('accountEditSaveBtn');
            const label = document.getElementById('edit_account_label').value.trim();
            const broker = document.getElementById('edit_account_broker').value;
            const appKey = document.getElementById('edit_account_app_key').value.trim();
            const appSecret = document.getElementById('edit_account_app_secret').value;
            const accountNumber = document.getElementById('edit_account_cano').value.trim();
            const parsed = accountNumber ? parseBrokerAccountInput(accountNumber, broker) : null;
            const pin = document.getElementById('edit_account_pin').value;

            const errors = [];
            if (!label) errors.push('계좌 이름을 입력해 주세요.');
            if (accountNumber && !parsed) errors.push(broker === 'toss' ? '토스 계좌를 다시 불러와 선택해 주세요.' : '계좌번호를 숫자 8자리 또는 10자리로 입력해 주세요.');
            const brokerChanged = broker !== accountEditTarget.broker;
            const tossCredentialsChanged = broker === 'toss' && (appKey || appSecret);
            if (brokerChanged && (!appKey || !appSecret)) {
                errors.push('증권사를 변경할 때는 새 API 자격증명을 모두 입력해 주세요.');
            }
            if (tossCredentialsChanged && (!appKey || !appSecret)) {
                errors.push('토스 자격증명을 변경할 때는 CLIENT ID와 CLIENT SECRET을 모두 입력해 주세요.');
            }
            if (broker === 'toss' && (brokerChanged || tossCredentialsChanged) && !parsed) {
                errors.push('새 자격증명으로 토스 계좌를 불러와 선택해 주세요.');
            }
            if (!/^\d{4,6}$/.test(pin)) errors.push('PIN은 4~6자리 숫자로 입력해 주세요.');
            if (errors.length) {
                showAccountEditError(errors.join(' '));
                return;
            }

            const body = { label, pin, broker };
            if (parsed) {
                body.cano = parsed.cano;
                body.acnt_prdt_cd = parsed.acnt_prdt_cd;
            }
            if (appKey) body.app_key = appKey;
            if (appSecret) body.app_secret = appSecret;

            clearAccountEditError();
            saveBtn.disabled = true;
            const originalText = saveBtn.innerText;
            saveBtn.innerText = '저장 중...';
            try {
                const res = await fetch(
                    `/api/accounts/${encodeURIComponent(accountEditTarget.accountId)}`,
                    {
                        method: 'PATCH',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(body)
                    }
                );
                const data = await res.json().catch(() => ({}));
                if (res.ok && data.status === 'success') {
                    closeAccountEditModal();
                    showAccountNotice(data.message || '계좌가 수정되었습니다.', false);
                    await loadAccountList(true);
                    syncData(true);
                } else {
                    showAccountEditError(data.detail || '계좌 수정에 실패했습니다.');
                }
            } catch (err) {
                console.error('submitEditAccount error', err);
                showAccountEditError('계좌 수정에 실패했습니다.');
            } finally {
                saveBtn.disabled = false;
                saveBtn.innerText = originalText;
            }
        }

        function runDeferredBootTasks() {
            fetchMarketCalendar();
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
                return;
            }

            const hasUsHoldings = cachedItems.some((item) => item.type === 'USA');
            if (hasUsHoldings && lastUsMarketStatus?.session === 'day_market') {
                startUsQuotePollingWindow(lastUsMarketStatus);
            }
        });

        // 앱 초기화
        window.addEventListener('DOMContentLoaded', async () => {
            const defaultLayoutMode = window.matchMedia('(min-width: 2000px) and (min-aspect-ratio: 21/9)').matches ? 'mode1' : 'mode2';
            let savedLayoutMode = defaultLayoutMode;
            try { savedLayoutMode = localStorage.getItem(LAYOUT_STORAGE_KEY) || defaultLayoutMode; } catch (_err) { /* Storage is optional. */ }
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

        window.addEventListener('pagehide', () => {
            disposeInsightChart();
            stopUsQuotePolling();
            clearTimeout(marketOverviewMountTimer);
            clearTimeout(marketOverviewVerifyTimer);
            clearTimeout(marketOverviewLoadTimer);
            marketOverviewGeneration += 1;
        });

        window.addEventListener('pageshow', (event) => {
            if (event.persisted) {
                initializeInsightChart(currentInsightState);
                initializeInsightDetailCharts(currentInsightState);
                scheduleMarketOverviewMount(true);
            }
        });
