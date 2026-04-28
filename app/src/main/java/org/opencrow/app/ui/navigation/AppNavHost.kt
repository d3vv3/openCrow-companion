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
import org.opencrow.app.data.local.LocalToolCapabilities
import org.opencrow.app.data.remote.dto.RegisterDeviceRequest
import org.opencrow.app.ui.screens.chat.ChatScreen
import org.opencrow.app.ui.screens.onboarding.OnboardingScreen
import org.opencrow.app.ui.screens.qrscan.QrScanScreen
import org.opencrow.app.ui.screens.settings.SettingsScreen

object Routes {
    const val QR_SCAN = "qr_scan"
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost(
    pendingConversationId: String? = null,
    onConversationOpened: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val navController = rememberNavController()
    var startDest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.container.apiClient.initialize()

        if (!app.container.apiClient.isConfigured) {
            val onboardingDone = app.container.apiClient.isOnboardingDone()
            startDest = if (onboardingDone) Routes.QR_SCAN else Routes.ONBOARDING
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

        // If valid and we have a stored push endpoint, ensure the server has it
        // (covers the case where UP endpoint was assigned before/after QR pairing)
        if (valid) {
            try {
                val apiClient = app.container.apiClient
                val storedEndpoint = apiClient.getPushEndpoint()
                val deviceId = apiClient.getDeviceId()
                if (!storedEndpoint.isNullOrEmpty() && deviceId != null) {
                    apiClient.api.registerDevice(deviceId, RegisterDeviceRequest(
                        capabilities = LocalToolCapabilities.all,
                        pushEndpoint = storedEndpoint
                    ))
                    Log.i("AppNavHost", "Push endpoint synced on startup")
                }
            } catch (e: Exception) {
                Log.w("AppNavHost", "Push endpoint sync failed: ${e.message}")
            }
        }
    }

    LaunchedEffect(startDest, pendingConversationId) {
        pendingConversationId ?: return@LaunchedEffect
        startDest ?: return@LaunchedEffect

        // If a notification delivers a conversation id, always bring the user to the
        // chat route so ChatScreen can select that conversation immediately.
        navController.navigate(Routes.CHAT) {
            launchSingleTop = true
        }
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
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(Routes.QR_SCAN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                pendingConversationId = pendingConversationId,
                onConversationOpened = onConversationOpened,
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
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
