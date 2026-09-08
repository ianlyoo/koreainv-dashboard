package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
) {
    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            DashboardTopBar(
                title = "설정",
                lastSynced = null,
                navigationButton = {
                    HeaderIconButton(Icons.Default.ArrowBack, "뒤로", onBackClick)
                },
            )
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = dashboardBottomContentPadding()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PremiumGlassCard {
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
                PremiumGlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsHeading("계정")
                        SettingsAction("계좌 관리", onManageAccountsClick)
                        SettingsAction("로그아웃", onLogoutClick)
                    }
                }
                PremiumGlassCard {
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
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    }
}
