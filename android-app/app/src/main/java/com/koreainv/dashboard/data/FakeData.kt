package com.koreainv.dashboard.data

data class AssetMetrics(
    val forwardPe: Double,
    val roe: Double,
    val debtEquity: Double,
    val beta: Double,
    val marketCap: String,
    val targetPrice: Double,
    val analystRecommendation: String,
    val week52Low: Double,
    val week52High: Double
)

enum class TradeType { BUY, SELL }

data class Trade(
    val id: String,
    val symbol: String,
    val name: String,
    val type: TradeType,
    val quantity: Double,
    val price: Double,
    val date: String
) {
    val totalAmount: Double
        get() = quantity * price
}

data class Holding(
    val symbol: String,
    val name: String,
    val quantity: Double,
    val currentPrice: Double,
    val averageCost: Double,
    val currency: String = "KRW",
    val metrics: AssetMetrics? = null
) {
    val totalValue: Double
        get() = quantity * currentPrice

    val totalCost: Double
        get() = quantity * averageCost

    val profitLoss: Double
        get() = totalValue - totalCost

    val profitLossPercentage: Double
        get() = if (totalCost > 0) (profitLoss / totalCost) * 100 else 0.0
}

data class PortfolioSnapshot(
    val holdings: List<Holding>,
    val cashBalanceKrw: Double,
    val lastSyncedLabel: String,
    val trades: List<Trade> = emptyList()
) {
    val totalPortfolioValue: Double
        get() = holdings.sumOf { it.totalValue }

    val totalProfitLoss: Double
        get() = holdings.sumOf { it.profitLoss }

    val totalProfitLossPercentage: Double
        get() = holdings.sumOf { it.totalCost }
            .takeIf { it > 0 }
            ?.let { totalProfitLoss / it * 100 }
            ?: 0.0

    val holdingsCount: Int
        get() = holdings.size
}

object PinAuth {
    const val DemoPin = "1234"

    fun isValid(pin: String): Boolean = pin == DemoPin
}

object FakePortfolioRepository {
    private val fakeTrades = listOf(
        Trade("1", "005930", "Samsung Electronics", TradeType.BUY, 50.0, 65000.0, "2023-10-01"),
        Trade("2", "000660", "SK Hynix", TradeType.BUY, 20.0, 130000.0, "2023-10-05"),
        Trade("3", "035420", "NAVER", TradeType.SELL, 10.0, 220000.0, "2023-10-10"),
        Trade("4", "035720", "Kakao", TradeType.BUY, 50.0, 50000.0, "2023-10-15"),
        Trade("5", "005380", "Hyundai Motor", TradeType.BUY, 20.0, 190000.0, "2023-10-20"),
        Trade("6", "005930", "Samsung Electronics", TradeType.SELL, 20.0, 75000.0, "2023-10-25")
    )

    private val fakeMetrics = mapOf(
        "005930" to AssetMetrics(15.2, 12.5, 35.0, 1.1, "440T", 90000.0, "Buy", 58000.0, 80000.0),
        "000660" to AssetMetrics(18.5, 10.2, 45.0, 1.3, "110T", 180000.0, "Strong Buy", 110000.0, 170000.0),
        "035420" to AssetMetrics(25.0, 15.0, 50.0, 1.2, "35T", 250000.0, "Buy", 180000.0, 240000.0),
        "035720" to AssetMetrics(30.0, 8.0, 60.0, 1.5, "25T", 70000.0, "Hold", 40000.0, 65000.0),
        "005380" to AssetMetrics(5.5, 18.0, 120.0, 0.9, "50T", 300000.0, "Strong Buy", 180000.0, 260000.0)
    )

    private val snapshots = listOf(
        PortfolioSnapshot(
            holdings = listOf(
                Holding("005930", "Samsung Electronics", 150.0, 73500.0, 68000.0, metrics = fakeMetrics["005930"]),
                Holding("000660", "SK Hynix", 50.0, 162000.0, 145000.0, metrics = fakeMetrics["000660"]),
                Holding("035420", "NAVER", 30.0, 195000.0, 210000.0, metrics = fakeMetrics["035420"]),
                Holding("035720", "Kakao", 100.0, 54000.0, 58000.0, metrics = fakeMetrics["035720"]),
                Holding("005380", "Hyundai Motor", 40.0, 245000.0, 200000.0, metrics = fakeMetrics["005380"]),
            ),
            cashBalanceKrw = 12_600_000.0,
            lastSyncedLabel = "Today 09:40",
            trades = fakeTrades
        ),
        PortfolioSnapshot(
            holdings = listOf(
                Holding("005930", "Samsung Electronics", 150.0, 74200.0, 68000.0, metrics = fakeMetrics["005930"]),
                Holding("000660", "SK Hynix", 50.0, 160500.0, 145000.0, metrics = fakeMetrics["000660"]),
                Holding("035420", "NAVER", 30.0, 198000.0, 210000.0, metrics = fakeMetrics["035420"]),
                Holding("035720", "Kakao", 100.0, 54800.0, 58000.0, metrics = fakeMetrics["035720"]),
                Holding("005380", "Hyundai Motor", 40.0, 247500.0, 200000.0, metrics = fakeMetrics["005380"]),
            ),
            cashBalanceKrw = 12_420_000.0,
            lastSyncedLabel = "Today 09:42",
            trades = fakeTrades
        ),
    )

    fun initialSnapshot(): PortfolioSnapshot = snapshots.first()

    fun nextSnapshot(current: PortfolioSnapshot): PortfolioSnapshot {
        val currentIndex = snapshots.indexOf(current)
        if (currentIndex == -1) {
            return snapshots.first()
        }
        return snapshots[(currentIndex + 1) % snapshots.size]
    }
}
