package com.koreainv.dashboard.network

/** Read-only data consumed by dashboard screens, independent of credentials and transport. */
interface DashboardDataSource {
    fun peekDashboard(): DashboardResponse?
    suspend fun fetchDashboard(forceRefresh: Boolean = false): DashboardResponse
    suspend fun refreshDashboardQuotes(): DashboardResponse?
    fun peekTradeHistory(range: String = "this_month", accountId: String? = null): TradeHistoryResponse?
    suspend fun fetchTradeHistory(
        range: String = "this_month",
        accountId: String? = null,
        forceRefresh: Boolean = false,
        onSummaryReady: (suspend (TradeHistoryResponse) -> Unit)? = null,
    ): TradeHistoryResponse
}
