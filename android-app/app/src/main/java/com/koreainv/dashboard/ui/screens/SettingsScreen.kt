package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.ui.appearance.ThemeMode

/** Stateless so production and debug hosts can share the same appearance controls. */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    versionName: String,
    isCheckingUpdate: Boolean,
    isDownloadingUpdate: Boolean,
    onCheckUpdatesClick: () -> Unit,
    onManageAccountsClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onBackClick: () -> Unit,
    onInsightSettingsClick: () -> Unit = {},
    insightConnectionLabel: String = "연결 필요",
) {
    DashboardScaffold(
        topBar = {
            DashboardTopBar(
                title = "설정",
                lastSynced = null,
                navigationButton = {
                    HeaderIconButton(Icons.Default.ArrowBack, "뒤로", onBackClick)
                },
            )
        },
    ) { paddingValues ->
        ScreenBackground {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = dashboardBottomContentPadding()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.selectableGroup()) {
                        SettingsHeading("화면 모드")
                        Text("기기 설정을 따르거나 원하는 화면 모드를 선택하세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .selectable(selected = themeMode == mode, role = Role.RadioButton,
                                        onClick = { onThemeModeChange(mode) })
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = themeMode == mode, onClick = null)
                                Text(when (mode) {
                                    ThemeMode.SYSTEM -> "시스템 설정"
                                    ThemeMode.LIGHT -> "라이트 모드"
                                    ThemeMode.DARK -> "다크 모드"
                                }, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    SettingsHeading("연결")
                    TextButton(
                        onClick = onInsightSettingsClick,
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("SaveTicker", color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.titleMedium)
                                Text("종목 인사이트 연결", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(insightConnectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsHeading("계정")
                        SettingsAction("계좌 관리", onManageAccountsClick)
                        SettingsAction("로그아웃", onLogoutClick)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsHeading("앱 정보")
                        Text("현재 버전 $versionName", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingsAction(
                            label = when {
                                isDownloadingUpdate -> "업데이트 다운로드 중…"
                                isCheckingUpdate -> "업데이트 확인 중…"
                                else -> "업데이트 확인"
                            },
                            onClick = onCheckUpdatesClick,
                            enabled = !isCheckingUpdate && !isDownloadingUpdate,
                        )
                    }
                }
                Text(
                    text = "© 2026 Youngin",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(vertical = 12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    }
}
