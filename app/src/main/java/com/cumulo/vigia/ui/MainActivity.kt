package com.cumulo.vigia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.cumulo.vigia.service.MqttAlarmService
import com.cumulo.vigia.ui.alarms.AlarmsScreen
import com.cumulo.vigia.ui.dashboard.DashboardScreen
import com.cumulo.vigia.ui.devices.DevicesScreen
import com.cumulo.vigia.ui.login.LoginScreen
import com.cumulo.vigia.ui.settings.SettingsScreen
import com.cumulo.vigia.ui.theme.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    object Alarms    : Screen("alarms", "Alertas", Icons.Default.Notifications)
    object Devices   : Screen("devices", "Dispositivos", Icons.Default.Memory)
    object Settings  : Screen("settings", "Ajustes", Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.Dashboard, Screen.Alarms, Screen.Devices, Screen.Settings)

class MainActivity : ComponentActivity() {

    private val viewModel: VigiaViewModel by viewModels()

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        requestNotificationPermission()
        startPollingService()

        setContent {
            VigiaTheme {
                VigiaApp(viewModel)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startPollingService() {
        val intent = Intent(this, MqttAlarmService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun VigiaApp(viewModel: VigiaViewModel) {
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val state by viewModel.dashboardState.collectAsState()
    val navController = rememberNavController()

    // Auto refresh every 15s when authenticated
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                viewModel.loadData(isRefresh = true)
            }
        }
    }

    if (!isAuthenticated) {
        LoginScreen(viewModel)
        return
    }

    Scaffold(
        containerColor = ZincBg,
        bottomBar = {
            NavigationBar(
                containerColor = ZincSurface,
                tonalElevation = 0.dp
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    val activeAlarmCount = state.activeAlarms.size

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (screen is Screen.Alarms && activeAlarmCount > 0) {
                                BadgedBox(badge = {
                                    Badge(containerColor = CriticalColor) {
                                        Text(
                                            if (activeAlarmCount > 9) "9+" else activeAlarmCount.toString(),
                                            color = Color.White,
                                            fontSize = 9.sp
                                        )
                                    }
                                }) {
                                    Icon(screen.icon, contentDescription = screen.label)
                                }
                            } else {
                                Icon(screen.icon, contentDescription = screen.label)
                            }
                        },
                        label = {
                            Text(screen.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = RedPrimary,
                            selectedTextColor = RedPrimary,
                            unselectedIconColor = ZincMuted,
                            unselectedTextColor = ZincMuted,
                            indicatorColor = RedPrimary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToAlarms = { navController.navigate(Screen.Alarms.route) },
                    onNavigateToDevices = { navController.navigate(Screen.Devices.route) }
                )
            }
            composable(Screen.Alarms.route) { AlarmsScreen(viewModel) }
            composable(Screen.Devices.route) { DevicesScreen(viewModel) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}
