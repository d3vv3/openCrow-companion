package org.opencrow.app.ui.navigation

import android.util.Log
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.screens.chat.ChatScreen
import org.opencrow.app.ui.screens.qrscan.QrScanScreen
import org.opencrow.app.ui.screens.settings.SettingsScreen

object Routes {
    const val QR_SCAN = "qr_scan"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val navController = rememberNavController()
    var startDest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.container.apiClient.initialize()

        if (!app.container.apiClient.isConfigured) {
            startDest = Routes.QR_SCAN
            return@LaunchedEffect
        }

        val valid = try {
            val healthResp = app.container.apiClient.api.health()
            if (!healthResp.isSuccessful) {
                Log.w("AppNavHost", "Health check failed: ${healthResp.code()}")
                false
            } else {
                val authResp = app.container.apiClient.api.listConversations()
                authResp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("AppNavHost", "Startup validation failed: ${e.message}")
            false
        }

        startDest = if (valid) Routes.CHAT else Routes.QR_SCAN
    }

    if (startDest == null) return

    NavHost(
        navController = navController,
        startDestination = startDest!!,
        enterTransition = { fadeIn() + slideInHorizontally { it / 4 } },
        exitTransition = { fadeOut() + slideOutHorizontally { -it / 4 } },
        popEnterTransition = { fadeIn() + slideInHorizontally { -it / 4 } },
        popExitTransition = { fadeOut() + slideOutHorizontally { it / 4 } }
    ) {
        composable(Routes.QR_SCAN) {
            QrScanScreen(
                onPaired = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.QR_SCAN) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onUnpaired = {
                    navController.navigate(Routes.QR_SCAN) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onRepaired = { /* stay on settings */ },
                onUnpaired = {
                    navController.navigate(Routes.QR_SCAN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
