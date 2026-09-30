package com.ssintelligence.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ssintelligence.app.BuildConfig
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.usecase.ObserveOnboardingUseCase
import com.ssintelligence.app.ui.debug.SearchDebugScreen
import com.ssintelligence.app.ui.duplicates.DuplicatesScreen
import com.ssintelligence.app.ui.detail.ScreenshotDetailScreen
import com.ssintelligence.app.ui.home.HomeScreen
import com.ssintelligence.app.ui.onboarding.OnboardingScreen
import com.ssintelligence.app.ui.screenshots.ScreenshotListScreen
import com.ssintelligence.app.ui.search.SearchScreen
import com.ssintelligence.app.ui.settings.SettingsScreen
import com.ssintelligence.app.ui.theme.SsIntelligenceTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Navigation destinations. */
private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val BROWSE = "browse"
    const val DUPLICATES = "duplicates"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{screenshotId}"

    /**
     * Debug-only route. The destination is only registered when
     * `BuildConfig.DEBUG` is true, so a release build cannot navigate to it and
     * the inspector's internal vocabulary is never reachable by a user (§46).
     */
    const val SEARCH_DEBUG = "search-debug"

    fun detail(id: Long) = "detail/$id"
}

/**
 * Root composable: theme, first-run gate, permission request and navigation.
 *
 * The permission model is deliberately minimal (§29): one read-images
 * permission, requested only after the user has seen the explanation.
 */
@Composable
fun SsIntelligenceAppRoot(locator: ServiceLocator) {
    val scope = rememberCoroutineScope()
    val onboarding = remember(locator) {
        ObserveOnboardingUseCase(locator.settingsRepository)
    }
    val onboardingDone by onboarding().collectAsStateWithLifecycle(initialValue = false)
    val themeMode by locator.settingsRepository.observeTheme()
        .collectAsStateWithLifecycle(
            initialValue = com.ssintelligence.app.domain.model.ThemeMode.SYSTEM
        )

    var onboardingResolved by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        onboardingResolved = onboarding().first()
    }

    SsIntelligenceTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            when (onboardingResolved) {
                null -> Unit // brief splash while preferences load
                false -> OnboardingScreen(
                    viewModel = onboarding,
                    onGetStarted = {
                        onboardingResolved = true
                        scope.launch { onboarding.complete() }
                    },
                )

                true -> MainNavigation(locator)
            }
        }
    }
}

@Composable
private fun MainNavigation(locator: ServiceLocator) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Android 13+ uses READ_MEDIA_IMAGES; 10-12 use READ_EXTERNAL_STORAGE.
    val permission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        // Indexing is requested either way: without permission the scan finds
        // nothing and the user is prompted again on the next Scan now.
        if (granted) locator.indexingScheduler.requestIndexing()
    }

    // The first indexing run starts as soon as access exists (§7).
    LaunchedEffect(hasPermission) {
        if (hasPermission) locator.indexingScheduler.requestIndexing()
    }

    if (!hasPermission) {
        PermissionRationale(
            onRequest = { permissionLauncher.launch(permission) },
            onOpenSettings = { scope.launch { openAppSettings(context) } },
        )
        return
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                locator = locator,
                onNavigateToSearch = { navController.navigate(Routes.SEARCH) },
                onNavigateToBrowse = { navController.navigate(Routes.BROWSE) },
                onNavigateToDuplicates = { navController.navigate(Routes.DUPLICATES) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                locator = locator,
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
                // Only wired up in debug builds; see the composable for why.
                onOpenSearchDebug = if (BuildConfig.DEBUG) {
                    { navController.navigate(Routes.SEARCH_DEBUG) }
                } else {
                    null
                },
            )
        }
        composable(Routes.BROWSE) {
            ScreenshotListScreen(
                locator = locator,
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.DUPLICATES) {
            DuplicatesScreen(
                locator = locator,
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("screenshotId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("screenshotId") ?: return@composable
            ScreenshotDetailScreen(
                locator = locator,
                screenshotId = id,
                onBack = { navController.popBackStack() },
                // "Find similar" navigates within the same detail destination,
                // so back returns to the previous screenshot, not to search.
                onOpenScreenshot = { otherId -> navController.navigate(Routes.detail(otherId)) },
            )
        }

        if (BuildConfig.DEBUG) {
            composable(Routes.SEARCH_DEBUG) {
                SearchDebugScreen(
                    locator = locator,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
