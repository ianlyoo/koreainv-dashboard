package com.koreainv.dashboard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.DashboardDataSource
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.ChartTone1
import com.koreainv.dashboard.ui.theme.ChartTone2
import com.koreainv.dashboard.ui.theme.ChartTone3
import com.koreainv.dashboard.ui.theme.ChartTone4
import com.koreainv.dashboard.ui.theme.ChartTone5
import com.koreainv.dashboard.ui.theme.ChartTone6
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetStatusScreen(
    repository: DashboardDataSource,
    onManageAccountsClick: () -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var dashboardData by remember(repository) { mutableStateOf<DashboardResponse?>(null) }
    var isLoading by remember(repository) { mutableStateOf(true) }
    var errorMessage by remember(repository) { mutableStateOf<String?>(null) }

    val requestOwner = remember(repository) { ScreenRequestOwner() }
    DisposableEffect(requestOwner) {
        onDispose { requestOwner.cancel() }
    }

    fun loadDashboard(forceRefresh: Boolean = false) {
        isLoading = true
        requestOwner.launch(
            scope = coroutineScope,
            load = { repository.fetchDashboard(forceRefresh = forceRefresh) },
            onSuccess = {
                dashboardData = it
                errorMessage = null
            },
            onFailure = {
                errorMessage = dashboardErrorMessage(it)
            },
            onFinished = { isLoading = false },
        )
    }

    LaunchedEffect(repository) {
        val cached = repository.peekDashboard()
        if (cached != null) {
            dashboardData = cached
            isLoading = false
        } else {
            loadDashboard()
        }
    }

    Scaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.asset_status),
                lastSynced = dashboardData?.summary?.lastSynced,
                actions = {
                    if (isLoading && dashboardData != null) {
                        HeaderLoadingIndicator()
                    } else {
                        HeaderIconButton(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            onClick = { loadDashboard(forceRefresh = true) },
                        )
                    }
                    DashboardUtilityMenu(
                        onManageAccounts = onManageAccountsClick,
                        onCheckUpdates = onCheckUpdatesClick,
                        onLogout = onLogoutClick,
                    )
                },
            )
        },
        containerColor = Background,
    ) { paddingValues ->
        ScreenBackground(modifier = Modifier.padding(paddingValues)) {
            when {
                isLoading && dashboardData == null -> {
                    DashboardLoadingState(
                        message = "자산 현황을 불러오는 중입니다…",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                errorMessage != null && dashboardData == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DashboardErrorNotice(
                            message = errorMessage.orEmpty(),
                            onRetry = { loadDashboard(forceRefresh = true) },
                        )
                    }
                }

                dashboardData != null -> {
                    val data = dashboardData!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = dashboardBottomContentPadding()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (errorMessage != null) {
                            DashboardErrorNotice(
                                message = errorMessage.orEmpty(),
                                onRetry = { loadDashboard(forceRefresh = true) },
                                usingCachedData = true,
                            )
                        }
                        TotalAssetsCard(data)
                        CashBalanceCard(data)
                        AssetDistributionCard(data)
                    }
                }
            }
        }
    }
}

@Composable
fun TotalAssetsCard(data: DashboardResponse) {
    val formatter = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }
    val equityAmount = data.summary.totalAssetsKrw - data.summary.totalCashKrw

    HeroTopSection {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.all_assets),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            HeroHeadlineValue(
                value = "₩${formatter.format(data.summary.totalAssetsKrw)}",
                color = TextGold,
            )
            Text(
                text = "전체 계좌 · 원화 환산",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        HeroMetricGroup {
            HeroMetricRow(
                primaryLabel = stringResource(R.string.stock_evaluation_amount),
                primaryValue = "₩${formatter.format(equityAmount)}",
                secondaryLabel = stringResource(R.string.cash_balance_label),
                secondaryValue = "₩${formatter.format(data.summary.totalCashKrw)}",
            )
        }
    }
}

@Composable
fun CashBalanceCard(data: DashboardResponse) {
    val formatter = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    PremiumGlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cash_balance_label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                DashboardPillButton(
                    label = if (isExpanded) "접기" else "통화별 보기",
                    onClick = { isExpanded = !isExpanded },
                    trailingIcon = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                )
            }
            FullMonetaryValue(
                label = "전체 계좌 · 원화 환산",
                value = "₩${formatter.format(data.summary.totalCashKrw)}",
            )

            AnimatedVisibility(visible = isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    ResponsiveDetailRow(label = "KRW", value = "₩${formatter.format(data.summary.cashKrw)}")
                    ResponsiveDetailRow(
                        label = stringResource(R.string.orderable_cash),
                        value = "₩${formatter.format(data.summary.orderableCashKrw)}",
                    )
                    ResponsiveDetailRow(label = "USD", value = formatCashUsd(data.summary.cashUsd))
                    ResponsiveDetailRow(label = "JPY", value = "¥${formatter.format(data.summary.cashJpy)}")
                }
            }
        }
    }
}

@Composable
fun AssetDistributionCard(data: DashboardResponse) {
    val colors = listOf(
        ChartTone1,
        ChartTone2,
        ChartTone3,
        ChartTone4,
        ChartTone5,
        ChartTone6,
    )

    PremiumGlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.asset_distribution),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = "보유 주식 평가금액 기준",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }

            if (data.assetDistribution.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_asset_data),
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(180.dp)) {
                        var startAngle = -90f
                        data.assetDistribution.forEachIndexed { index, asset ->
                            val sweepAngle = (asset.weightPercent / 100f) * 360f
                            drawArc(
                                color = colors[index % colors.size],
                                startAngle = startAngle,
                                sweepAngle = sweepAngle.toFloat(),
                                useCenter = false,
                                style = Stroke(width = 42f, cap = StrokeCap.Round),
                            )
                            startAngle += sweepAngle.toFloat()
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "구성 항목",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                        Text(
                            text = formatWholeNumber(data.assetDistribution.size.toDouble()),
                            style = MaterialTheme.typography.displaySmall,
                            color = TextPrimary,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    data.assetDistribution.forEachIndexed { index, asset ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Canvas(modifier = Modifier.padding(top = 5.dp).size(12.dp)) {
                                drawCircle(color = colors[index % colors.size])
                            }
                            ResponsiveDetailRow(
                                label = asset.name,
                                value = formatSignedPercent(asset.weightPercent).removePrefix("+"),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatCashUsd(value: Double): String = "$" +
    NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)
