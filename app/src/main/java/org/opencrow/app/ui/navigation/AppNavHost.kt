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

    // ── Determine initial destination (fast, no network) ──────────────────────
    LaunchedEffect(Unit) {
        app.container.apiClient.initialize()
        startDest = when {
            app.container.apiClient.isConfigured -> Routes.CHAT
            app.container.apiClient.isOnboardingDone() -> Routes.QR_SCAN
            else -> Routes.ONBOARDING
        }
    }

    // ── Background token validation (non-blocking) ─────────────────────────────
    // Runs after the UI is already shown. If the session is dead and cannot be
    // refreshed, clears credentials and redirects to onboarding.
    LaunchedEffect(startDest) {
        if (startDest != Routes.CHAT) return@LaunchedEffect

        val valid = try {
            val resp = app.container.apiClient.api.listConversations()
            resp.isSuccessful
        } catch (e: Exception) {
            Log.w("AppNavHost", "Background auth check failed: ${e.message}")
            // Network error -- don't log out, assume transient
            true
        }

        if (!valid) {
            Log.w("AppNavHost", "Session invalid after refresh attempt, logging out")
            app.container.apiClient.clearTokens()
            val onboardingDone = app.container.apiClient.isOnboardingDone()
            val dest = if (onboardingDone) Routes.QR_SCAN else Routes.ONBOARDING
            navController.navigate(dest) {
                popUpTo(0) { inclusive = true }
            }
            return@LaunchedEffect
        }

        // Sync push endpoint if we have one stored
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
