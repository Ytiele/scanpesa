package com.scanpesa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.scanpesa.app.automation.JobStore
import com.scanpesa.app.automation.ScanPesaAccessibilityService
import com.scanpesa.app.ui.AppViewModel
import com.scanpesa.app.ui.ScanPesaTheme
import com.scanpesa.app.ui.screens.AdvancedSettingsScreen
import com.scanpesa.app.ui.screens.ConfirmScreen
import com.scanpesa.app.ui.screens.DebugScreen
import com.scanpesa.app.ui.screens.HistoryScreen
import com.scanpesa.app.ui.screens.HomeScreen
import com.scanpesa.app.ui.screens.OnboardingScreen
import com.scanpesa.app.ui.screens.ScanScreen
import com.scanpesa.app.ui.screens.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    /** The three tabs: Scan (home), History, Settings. */
    const val MAIN = "main"
    const val SCAN = "scan"
    const val CONFIRM = "confirm"
    const val ADVANCED = "advanced"
    const val DEBUG = "debug"
}

private enum class Tab(val label: String, val icon: Int) {
    HOME("Scan", R.drawable.ic_scan),
    HISTORY("History", R.drawable.ic_history),
    SETTINGS("Settings", R.drawable.ic_tune),
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // Coming back to ScanPesa cancels any auto-fill that hasn't finished: SIM Toolkit is only
        // ever driven right after Go, never later in the background.
        val job = JobStore.current
        if (job != null && System.currentTimeMillis() - job.createdAt > 1500) {
            ScanPesaAccessibilityService.instance?.cancelPending("Cancelled: you went back to ScanPesa")
                ?: JobStore.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScanPesaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    val start = if (vm.prefs.onboardingDone) Routes.MAIN else Routes.ONBOARDING
                    NavHost(nav, startDestination = start) {
                        composable(Routes.ONBOARDING) {
                            OnboardingScreen(onDone = {
                                vm.prefs.onboardingDone = true
                                nav.navigate(Routes.MAIN) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                            })
                        }
                        composable(Routes.MAIN) {
                            var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                            Scaffold(
                                containerColor = MaterialTheme.colorScheme.background,
                                contentWindowInsets = WindowInsets(0),
                                bottomBar = {
                                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                                        Tab.entries.forEach { t ->
                                            NavigationBarItem(
                                                selected = tab == t,
                                                onClick = { tab = t },
                                                icon = { Icon(painterResource(t.icon), null) },
                                                label = { Text(t.label) },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                ),
                                            )
                                        }
                                    }
                                },
                            ) { pad ->
                                Box(Modifier.padding(pad)) {
                                    when (tab) {
                                        Tab.HOME -> HomeScreen(onScan = { nav.navigate(Routes.SCAN) })
                                        Tab.HISTORY -> HistoryScreen(vm = vm, onPick = { vm.reuse(it); nav.navigate(Routes.CONFIRM) })
                                        Tab.SETTINGS -> SettingsScreen(vm = vm, onAdvanced = { nav.navigate(Routes.ADVANCED) })
                                    }
                                }
                            }
                        }
                        composable(Routes.SCAN) {
                            ScanScreen(
                                vm = vm,
                                onPhotoRead = { nav.navigate(Routes.CONFIRM) { popUpTo(Routes.SCAN) { inclusive = true } } },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.CONFIRM) {
                            ConfirmScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onRescan = { nav.navigate(Routes.SCAN) { popUpTo(Routes.MAIN) } },
                            )
                        }
                        composable(Routes.ADVANCED) {
                            AdvancedSettingsScreen(vm = vm, onBack = { nav.popBackStack() }, onDebug = { nav.navigate(Routes.DEBUG) })
                        }
                        composable(Routes.DEBUG) { DebugScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
