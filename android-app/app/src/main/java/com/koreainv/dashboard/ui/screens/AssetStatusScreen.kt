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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.KisRepository
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
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetStatusScreen(
    repository: KisRepository,
    onCheckUpdatesClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var dashboardData by remember { mutableStateOf<DashboardResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadDashboard(forceRefresh: Boolean = false) {
        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            runCatching { repository.fetchDashboard(forceRefresh = forceRefresh) }
                .onSuccess { dashboardData = it }
                .onFailure {
                    val detail = it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "unknown"
                    errorMessage = "자산 정보를 불러오지 못했습니다. [$detail]"
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
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
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && dashboardData == null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        DashboardPillButton(
                            label = stringResource(R.string.retry),
                            onClick = { loadDashboard() },
                            tone = AccentTone.Accent,
                        )
                    }
                }

                dashboardData != null -> {
                    val data = dashboardData!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 132.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
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
        SurfaceBadge(
            label = stringResource(R.string.asset_status),
            tone = AccentTone.Info,
        )
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
                text = stringResource(R.string.cash_balance_label),
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
    var isExpanded by remember { mutableStateOf(false) }

    PremiumGlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.cash_balance_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                    Text(
                        text = "₩${formatter.format(data.summary.totalCashKrw)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                }
                DashboardPillButton(
                    label = if (isExpanded) "접기" else "상세",
                    onClick = { isExpanded = !isExpanded },
                    trailingIcon = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    CurrencyBreakdownRow(label = "KRW", value = "₩${formatter.format(data.summary.cashKrw)}")
                    CurrencyBreakdownRow(label = "USD", value = "$${formatter.format(data.summary.cashUsd)}")
                    CurrencyBreakdownRow(label = "JPY", value = "¥${formatter.format(data.summary.cashJpy)}")
                }
            }
        }
    }
}

@Composable
private fun CurrencyBreakdownRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
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
                    text = stringResource(R.string.asset_status),
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
                            text = stringResource(R.string.all_assets),
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .padding(0.dp),
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawCircle(color = colors[index % colors.size])
                                    }
                                }
                                Text(
                                    text = asset.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                )
                            }
                            Text(
                                text = formatSignedPercent(asset.weightPercent).removePrefix("+"),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
