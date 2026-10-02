package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.insight.*
import com.koreainv.dashboard.ui.theme.LocalDashboardColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun StockInsightScreen(
    repository: SaveTickerInsightRepository,
    symbol: String,
    marketType: String,
    onBackClick: () -> Unit,
    onConnectClick: () -> Unit,
    displayName: String = symbol,
) {
    val connection by repository.connectionState.collectAsState()
    var snapshot by remember(repository, symbol, marketType) { mutableStateOf(repository.peek(symbol, marketType)) }
    var loading by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val supportedMarket = marketType.equals("USA", ignoreCase = true)
    val mayShow = supportedMarket && connection.isUnlocked && connection.hasCredentials && connection.status != InsightConnectionStatus.EXPIRED
    LaunchedEffect(repository, symbol, marketType, request, mayShow) {
        if (!mayShow) { snapshot = null; loading = false; return@LaunchedEffect }
        loading = true
        try {
            snapshot = repository.fetch(symbol, marketType, forceRefresh = request > 0)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "인사이트를 불러오지 못했습니다. 다시 시도해 주세요."
        } finally {
            loading = false
        }
    }
    val listState = rememberLazyListState()
    var expanded by remember(symbol, marketType) { mutableStateOf(emptySet<String>()) }
    val retry: () -> Unit = remember { { if (!loading) request++ } }
    val toggle: (String) -> Unit = remember(symbol, marketType) {
        { key -> expanded = if (key in expanded) expanded - key else expanded + key }
    }
    val notice = error ?: snapshot?.message ?: connection.message
    val contentItems = remember(snapshot, expanded, notice, displayName, symbol, marketType) {
        val s = snapshot
        if (s == null) emptyList() else buildList {
            if (s.isStale || s.status == InsightSnapshotStatus.OFFLINE) {
                add(InsightListItem("offline", "overview") {
                    Text("오프라인 · 마지막 성공 데이터를 표시합니다. 원래 시점은 아래에서 확인하세요.")
                })
            }
            if (notice != null) add(InsightListItem("notice", "overview") {
                if (error != null) DashboardErrorNotice(notice, onRetry = retry, usingCachedData = true)
                else DashboardScopeNote(notice)
            })
            add(InsightListItem("chart", "overview") {
                InsightSectionNotice(s, "bars", retry)
                InsightPriceChart(s.bars)
            })
            add(InsightListItem("company", "overview") {
                InsightCompany(s, displayName, symbol, marketType)
            })
            addAll(insightSectionItems(s, expanded, toggle, retry))
        }
    }
    Box(Modifier.fillMaxSize()) {
        DashboardScaffold(topBar = {
            InsightTopBar(symbol, onBackClick, isRefreshing = loading, onRefresh = retry)
        }) { padding ->
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                    top = padding.calculateTopPadding() + 20.dp, bottom = padding.calculateBottomPadding() + 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !supportedMarket -> item("unsupported") {
                        DashboardEmptyState("지원하지 않는 시장입니다", "종목 인사이트는 현재 미국 종목(USA)에서 제공됩니다.")
                    }
                    !mayShow -> item("connect") {
                        DashboardEmptyState("인사이트 연결이 필요합니다", "SaveTicker 연결을 확인한 뒤 이 종목으로 돌아올 수 있습니다.", actionLabel = "연결 설정", onAction = onConnectClick)
                    }
                    snapshot == null -> item("loading") {
                        if (loading) DashboardLoadingState("인사이트를 불러오는 중입니다…")
                        else if (error != null) DashboardErrorNotice(error.orEmpty(), onRetry = retry)
                        else DashboardEmptyState("제공된 인사이트가 없습니다", "잠시 후 다시 확인해 주세요.", actionLabel = "다시 시도", onAction = retry)
                    }
                    else -> items(contentItems, key = { it.key }) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) { it.content() }
                    }
                }
            }
        }
        if (mayShow && snapshot != null) {
            InsightSectionNavigation(contentItems, listState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun InsightCompany(s: InsightSnapshot, displayName: String, symbol: String, marketType: String) {
    val colors = LocalDashboardColors.current
    val change = s.header?.changePercent
    val changeColor = when {
        change == null || change == 0.0 -> colors.textSecondary
        change > 0 -> colors.success
        else -> colors.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(displayName, style = MaterialTheme.typography.titleLarge)
        Text("$symbol · $marketType", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Text(insightPrice(s.header?.price), style = MaterialTheme.typography.displayLarge, modifier = Modifier.clearAndSetSemantics { contentDescription = "현재가 ${insightNumber(s.header?.price)} USD" })
            Text("USD", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                modifier = Modifier.padding(bottom=5.dp))
        }
        Text(insightSigned(change, "%"), style = MaterialTheme.typography.titleMedium, color = changeColor)
    }
}

@Composable
private fun InsightSectionNavigation(items: List<InsightListItem>, listState: LazyListState, modifier: Modifier) {
    val colors = LocalDashboardColors.current
    val scope = rememberCoroutineScope()
    val selectedSection by remember(items, listState) {
        derivedStateOf { items.getOrNull(listState.firstVisibleItemIndex)?.section ?: "overview" }
    }
    val destinations = remember(items) { insightSectionLabels.keys.associateWith { key -> items.indexOfFirst { it.key == key } } }
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
            .liquidGlass(radius = 34.dp, role = GlassRole.Navigation)
            .horizontalScroll(rememberScrollState()).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        insightSectionLabels.forEach { (key, label) ->
            TextButton(
                onClick = { destinations[key]?.takeIf { it >= 0 }?.let { index -> scope.launch { listState.scrollToItem(index) } } },
                modifier = Modifier.sizeIn(minHeight = 48.dp, minWidth = 48.dp)
                    .semantics { selected = selectedSection == key }
                    .then(if (selectedSection == key) Modifier.liquidGlass(radius = 28.dp, role = GlassRole.Control) else Modifier),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (selectedSection == key) colors.textPrimary else colors.textSecondary),
            ) { Text(label) }
        }
    }
}
