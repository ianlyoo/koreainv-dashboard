package com.koreainv.dashboard.ui.screens

/** Shared presentation policy; never changes repository filters or summary calculations. */
internal const val DashboardContentMaxWidth = 840

internal fun portfolioScopeDescription(filtered: Boolean): String =
    if (filtered) "요약은 전체 계좌 · 아래 목록은 선택한 계좌 기준입니다."
    else "전체 계좌 · 주식 평가금액 기준입니다."

internal fun feedbackTitle(usingCachedData: Boolean): String =
    if (usingCachedData) "새로고침하지 못했습니다" else "정보를 불러오지 못했습니다"
