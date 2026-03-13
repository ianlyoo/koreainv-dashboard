package com.koreainv.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.AppCredentials
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.network.SettingsManager
import com.koreainv.dashboard.update.AppUpdateManager
import com.koreainv.dashboard.update.InstallPreparationResult
import com.koreainv.dashboard.update.ReleaseInfo
import com.koreainv.dashboard.ui.screens.AssetStatusScreen
import com.koreainv.dashboard.ui.screens.HoldingDetailScreen
import com.koreainv.dashboard.ui.screens.PinUnlockScreen
import com.koreainv.dashboard.ui.screens.PortfolioScreen
import com.koreainv.dashboard.ui.screens.SetupScreen
import com.koreainv.dashboard.ui.screens.TradeHistoryScreen
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Surface
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Setup : Screen("setup")
    data object Unlock : Screen("unlock")
    data object Portfolio : Screen("portfolio")
    data object TradeHistory : Screen("trade_history")
    data object AssetStatus : Screen("asset_status")
    data object HoldingDetail : Screen("holding_detail/{symbol}") {
        fun createRoute(symbol: String): String = "holding_detail/$symbol"
    }
}

@Composable
fun KoreaInvApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val updateManager = remember { AppUpdateManager() }
    var unlockedCredentials by remember { mutableStateOf<AppCredentials?>(null) }
    var availableUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    val repository = remember(unlockedCredentials) { unlockedCredentials?.let { KisRepository(it) } }

    fun goHome() {
        navController.navigate(Screen.Portfolio.route) {
            launchSingleTop = true
            popUpTo(Screen.Portfolio.route) { inclusive = false }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = unlockedCredentials != null,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Surface,
                drawerContentColor = TextPrimary,
            ) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = stringResource(R.string.menu),
                        color = TextGold,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.asset_status), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                navController.navigate(Screen.AssetStatus.route) { launchSingleTop = true }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = TextPrimary
                        )
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.trade_history_title), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                navController.navigate(Screen.TradeHistory.route) { launchSingleTop = true }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = TextPrimary
                        )
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.check_for_updates), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                isCheckingUpdate = true
                                updateMessage = null
                                availableUpdate = try {
                                    updateManager.checkForUpdate(context).also {
                                        if (it == null) updateMessage = context.getString(R.string.update_up_to_date)
                                    }
                                } catch (_: Exception) {
                                    updateMessage = context.getString(R.string.update_check_failed)
                                    null
                                }
                                isCheckingUpdate = false
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.logout), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                unlockedCredentials = null
                                navController.navigate(Screen.Unlock.route) {
                                    popUpTo(Screen.Portfolio.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        },
    ) {
        NavHost(navController = navController, startDestination = Screen.Splash.route) {
            composable(Screen.Splash.route) {
                var isChecking by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    val isSetupComplete = settingsManager.isSetupCompleteFlow.first()
                    navController.navigate(if (isSetupComplete) Screen.Unlock.route else Screen.Setup.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                    isChecking = false
                }

                if (isChecking) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Background),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = TextGold)
                    }
                }
            }

            composable(Screen.Setup.route) {
                SetupScreen(
                    settingsManager = settingsManager,
                    onSetupSuccess = { credentials ->
                        unlockedCredentials = credentials
                        navController.navigate(Screen.Portfolio.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(Screen.Unlock.route) {
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var isUnlocking by remember { mutableStateOf(false) }

                PinUnlockScreen(
                    errorMessage = errorMessage,
                    isLoading = isUnlocking,
                    onUnlock = { pin ->
                        scope.launch {
                            isUnlocking = true
                            val credentials = settingsManager.unlock(pin)
                            if (credentials != null) {
                                unlockedCredentials = credentials
                                errorMessage = null
                                navController.navigate(Screen.Portfolio.route) {
                                    popUpTo(Screen.Unlock.route) { inclusive = true }
                                }
                            } else {
                                errorMessage = context.getString(R.string.invalid_pin)
                            }
                            isUnlocking = false
                        }
                    },
                )
            }

            composable(Screen.Portfolio.route) {
                val activeRepository = repository
                if (activeRepository == null) {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.Unlock.route) {
                            popUpTo(Screen.Portfolio.route) { inclusive = true }
                        }
                    }
                } else {
                    PortfolioScreen(
                        repository = activeRepository,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onHoldingClick = { symbol -> navController.navigate(Screen.HoldingDetail.createRoute(symbol)) },
                    )
                }
            }

            composable(Screen.AssetStatus.route) {
                val activeRepository = repository
                if (activeRepository == null) {
                    LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                } else {
                    AssetStatusScreen(
                        repository = activeRepository,
                        onBackClick = ::goHome,
                    )
                }
            }

            composable(Screen.TradeHistory.route) {
                val activeRepository = repository
                if (activeRepository == null) {
                    LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                } else {
                    TradeHistoryScreen(
                        repository = activeRepository,
                        onBackClick = ::goHome,
                    )
                }
            }

            composable(
                route = Screen.HoldingDetail.route,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType }),
            ) { backStackEntry ->
                val activeRepository = repository
                val symbol = backStackEntry.arguments?.getString("symbol")
                if (activeRepository == null || symbol.isNullOrBlank()) {
                    LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                } else {
                    HoldingDetailScreen(
                        repository = activeRepository,
                        symbol = symbol,
                        onBackClick = { navController.popBackStack() },
                    )
                }
            }
        }

        if (availableUpdate != null) {
            val release = availableUpdate!!
            AlertDialog(
                onDismissRequest = { availableUpdate = null },
                title = { Text(stringResource(R.string.update_available_title)) },
                text = { Text("새 버전 ${release.tagName}을 다운로드할 수 있습니다.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                isDownloadingUpdate = true
                                try {
                                    when (val result = updateManager.downloadUpdate(context, release)) {
                                        InstallPreparationResult.PermissionRequired -> {
                                            updateManager.requestInstallPermission(context)
                                            updateMessage = context.getString(R.string.install_permission_required)
                                        }
                                        is InstallPreparationResult.Ready -> {
                                            updateManager.launchInstaller(context, result.apkFile)
                                        }
                                    }
                                } catch (_: Exception) {
                                    updateMessage = context.getString(R.string.download_update_failed)
                                }
                                isDownloadingUpdate = false
                                availableUpdate = null
                            }
                        }
                    ) { Text(stringResource(R.string.update_now)) }
                },
                dismissButton = {
                    TextButton(onClick = { availableUpdate = null }) {
                        Text(stringResource(R.string.later))
                    }
                },
            )
        }

        if (updateMessage != null) {
            AlertDialog(
                onDismissRequest = { updateMessage = null },
                title = { Text(stringResource(R.string.check_for_updates)) },
                text = { Text(updateMessage!!) },
                confirmButton = {
                    TextButton(onClick = { updateMessage = null }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        if (isCheckingUpdate || isDownloadingUpdate) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = TextGold)
            }
        }
    }
}
