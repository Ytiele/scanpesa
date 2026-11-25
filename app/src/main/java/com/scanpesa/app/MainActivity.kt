package com.scanpesa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
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
import com.scanpesa.app.ui.screens.ConfirmScreen
import com.scanpesa.app.ui.screens.DebugScreen
import com.scanpesa.app.ui.screens.HistoryScreen
import com.scanpesa.app.ui.screens.HomeScreen
import com.scanpesa.app.ui.screens.OnboardingScreen
import com.scanpesa.app.ui.screens.ScanScreen
import com.scanpesa.app.ui.screens.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SCAN = "scan"
    const val CONFIRM = "confirm"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
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
                    val start = if (vm.prefs.onboardingDone) Routes.HOME else Routes.ONBOARDING
                    NavHost(nav, startDestination = start) {
                        composable(Routes.ONBOARDING) {
                            OnboardingScreen(onDone = {
                                vm.prefs.onboardingDone = true
                                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                            })
                        }
                        composable(Routes.HOME) {
                            HomeScreen(
                                onScan = { nav.navigate(Routes.SCAN) },
                                onManual = { vm.startManual(); nav.navigate(Routes.CONFIRM) },
                                onHistory = { nav.navigate(Routes.HISTORY) },
                                onSettings = { nav.navigate(Routes.SETTINGS) },
                            )
                        }
                        composable(Routes.SCAN) {
                            ScanScreen(
                                vm = vm,
                                onDetected = { nav.navigate(Routes.CONFIRM) { popUpTo(Routes.SCAN) { inclusive = true } } },
                                onManual = { vm.startManual(); nav.navigate(Routes.CONFIRM) { popUpTo(Routes.SCAN) { inclusive = true } } },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.CONFIRM) {
                            ConfirmScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onRescan = { nav.navigate(Routes.SCAN) { popUpTo(Routes.CONFIRM) { inclusive = true } } },
                            )
                        }
                        composable(Routes.HISTORY) {
                            HistoryScreen(vm = vm, onBack = { nav.popBackStack() }, onPick = {
                                vm.reuse(it); nav.navigate(Routes.CONFIRM)
                            })
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(vm = vm, onBack = { nav.popBackStack() }, onDebug = { nav.navigate(Routes.DEBUG) })
                        }
                        composable(Routes.DEBUG) { DebugScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
