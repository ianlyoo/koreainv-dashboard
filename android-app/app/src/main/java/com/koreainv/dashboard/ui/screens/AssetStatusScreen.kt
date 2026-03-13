package com.koreainv.dashboard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.SurfaceGlassLight
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
    onBackClick: () -> Unit,
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
            TopAppBar(
                title = {
                    InlineTitleWithSync(
                        title = stringResource(R.string.asset_status),
                        lastSynced = dashboardData?.summary?.lastSynced,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextGold,
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                actions = {
                    if (isLoading && dashboardData != null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            color = TextGold,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { loadDashboard(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh), tint = Color.White)
                        }
                    }
                },
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(paddingValues),
        ) {
            when {
                isLoading && dashboardData == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && dashboardData == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { loadDashboard() },
                            colors = ButtonDefaults.buttonColors(containerColor = TextGold),
                        ) {
                            Text(stringResource(R.string.retry), color = Color.Black)
                        }
                    }
                }

                dashboardData != null -> {
                    val data = dashboardData!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
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

    PremiumCard {
        Column {
            Text(
                text = stringResource(R.string.all_assets),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "₩${formatter.format(data.summary.totalAssetsKrw)}",
                style = MaterialTheme.typography.displayMedium,
                color = TextGold,
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

    val totalCashKrw = data.summary.totalCashKrw

    PremiumGlassCard(
        modifier = Modifier.clickable { isExpanded = !isExpanded }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.cash_balance_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "₩${formatter.format(totalCashKrw)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.expand_cash),
                    tint = TextSecondary,
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Divider(color = SurfaceBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("KRW", color = TextSecondary)
                        Text("₩${formatter.format(data.summary.cashKrw)}", color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("USD", color = TextSecondary)
                        Text("$${formatter.format(data.summary.cashUsd)}", color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("JPY", color = TextSecondary)
                        Text("¥${formatter.format(data.summary.cashJpy)}", color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun AssetDistributionCard(data: DashboardResponse) {
    val colors = listOf(
        Color(0xFFD4AF37),
        Color(0xFF4FC3F7),
        Color(0xFF00E676),
        Color(0xFFFF5252),
        Color(0xFF9C27B0),
        Color(0xFFFF9800),
        Color(0xFF00BCD4),
        Color(0xFF8BC34A),
    )

    PremiumGlassCard {
        Column {
            Text(
                text = stringResource(R.string.asset_distribution),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(24.dp))

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
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(160.dp)) {
                        var startAngle = -90f
                        data.assetDistribution.forEachIndexed { index, asset ->
                            val sweepAngle = (asset.weightPercent / 100f) * 360f
                            drawArc(
                                color = colors[index % colors.size],
                                startAngle = startAngle,
                                sweepAngle = sweepAngle.toFloat(),
                                useCenter = false,
                                style = Stroke(width = 40f, cap = StrokeCap.Butt),
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
                            text = "${data.assetDistribution.size}",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    data.assetDistribution.forEachIndexed { index, asset ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors[index % colors.size]),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = asset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                            text = formatSignedPercent(asset.weightPercent).removePrefix("+"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
