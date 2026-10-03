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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ssintelligence.app.BuildConfig
import com.ssintelligence.app.ServiceLocator
import com.ssintelligence.app.domain.usecase.ObserveOnboardingUseCase
import com.ssintelligence.app.ui.collections.CollectionsScreen
import com.ssintelligence.app.ui.compare.CompareScreenHost
import com.ssintelligence.app.ui.debug.SearchDebugScreen
import com.ssintelligence.app.ui.duplicates.DuplicatesScreen
import com.ssintelligence.app.ui.detail.ScreenshotDetailScreen
import com.ssintelligence.app.ui.explore.EntityScreen
import com.ssintelligence.app.ui.explore.ExploreScreen
import com.ssintelligence.app.ui.home.HomeScreen
import com.ssintelligence.app.ui.actions.ActionCenterScreen
import com.ssintelligence.app.ui.assistant.AssistantScreen
import com.ssintelligence.app.ui.automation.AutomationScreen
import com.ssintelligence.app.ui.cleanup.CleanupScreen
import com.ssintelligence.app.ui.expenses.ExpensesScreen
import com.ssintelligence.app.ui.insights.InsightsScreen
import com.ssintelligence.app.ui.privacy.PrivacyScreen
import com.ssintelligence.app.ui.tasks.TasksScreen
import com.ssintelligence.app.ui.onboarding.OnboardingScreen
import com.ssintelligence.app.ui.screenshots.ScreenshotListScreen
import com.ssintelligence.app.ui.search.ImagePickerScreen
import com.ssintelligence.app.ui.search.SearchScreen
import com.ssintelligence.app.ui.similar.SimilarScreen
import com.ssintelligence.app.ui.timeline.TimelineScreen
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
    const val TIMELINE = "timeline"
    const val COLLECTIONS = "collections"
    const val EXPLORE = "explore"
    const val ENTITY = "entity/{entityId}"
    const val SIMILAR = "similar/{screenshotId}"
    const val COMPARE = "compare/{firstId}?secondId={secondId}"
    const val PICK_IMAGE = "pick-image"
    const val ASSISTANT = "assistant?anchor={anchor}&selection={selection}"
    const val INSIGHTS = "insights"
    const val CLEANUP = "cleanup"
    const val PRIVACY = "privacy"
    const val TASKS = "tasks"
    const val EXPENSES = "expenses"
    const val ACTIONS = "actions"
    const val AUTOMATION = "automation"

    fun detail(id: Long) = "detail/$id"
    fun entity(id: Long) = "entity/$id"
    fun similar(id: Long) = "similar/$id"
    fun compare(firstId: Long, secondId: Long? = null) =
        if (secondId == null) "compare/$firstId" else "compare/$firstId?secondId=$secondId"

    /**
     * Assistant entry point.
     *
     * [anchor] is a single screenshot the user is asking about ("Ask about this
     * screenshot"); [selection] is a comma-separated set ("Ask about these").
     * Both are optional — the plain assistant screen has neither.
     */
    fun assistant(anchor: Long? = null, selection: List<Long> = emptyList()) = buildString {
        append("assistant")
        val params = buildList {
            if (anchor != null) add("anchor=$anchor")
            if (selection.isNotEmpty()) add("selection=${selection.joinToString(",")}")
        }
        if (params.isNotEmpty()) append("?${params.joinToString("&")}")
    }

    /**
     * Debug-only route. The destination is only registered when
     * `BuildConfig.DEBUG` is true, so a release build cannot navigate to it and
     * the inspector's internal vocabulary is never reachable by a user (§46).
     */
    const val SEARCH_DEBUG = "search-debug"
}

/**
 * Root composable: theme, first-run gate, permission request and navigation.
 *
 * The permission model is deliberately minimal (§29): one read-images
 * permission, requested only after the user has seen the explanation.
 */
@Composable
fun SsIntelligenceAppRoot(
    locator: ServiceLocator,
    startDestination: String? = null,
) {
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

    SsIntelligenceTheme(themeMode = themeMode, useDynamicColor = false) {
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

                true -> MainNavigation(locator, startDestination)
            }
        }
    }
}

@Composable
private fun MainNavigation(locator: ServiceLocator, startDestination: String? = null) {
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
        if (hasPermission) {
            locator.indexingScheduler.requestIndexing()
            locator.requestIntelligenceCatchUp()
        }
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
        startDestination = startDestination ?: Routes.HOME,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(300))
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                locator = locator,
                onNavigateToSearch = { navController.navigate(Routes.SEARCH) },
                onNavigateToBrowse = { navController.navigate(Routes.BROWSE) },
                onNavigateToDuplicates = { navController.navigate(Routes.DUPLICATES) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToTimeline = { navController.navigate(Routes.TIMELINE) },
                onNavigateToCollections = { navController.navigate(Routes.COLLECTIONS) },
                onNavigateToExplore = { navController.navigate(Routes.EXPLORE) },
                onNavigateToAssistant = { navController.navigate(Routes.assistant()) },
                onNavigateToInsights = { navController.navigate(Routes.INSIGHTS) },
                onNavigateToCleanup = { navController.navigate(Routes.CLEANUP) },
                onNavigateToPrivacy = { navController.navigate(Routes.PRIVACY) },
                onNavigateToTasks = { navController.navigate(Routes.TASKS) },
                onNavigateToExpenses = { navController.navigate(Routes.EXPENSES) },
                onNavigateToActions = { navController.navigate(Routes.ACTIONS) },
                onNavigateToAutomation = { navController.navigate(Routes.AUTOMATION) },
                onOpenEntity = { entityId -> navController.navigate(Routes.entity(entityId)) },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(
            route = "${Routes.SEARCH}?preset={preset}",
            arguments = listOf(
                navArgument("preset") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            // The search-by-image pin lives here so the picker route can set
            // it and the search screen can consume it across navigations.
            var imageQuery by rememberSaveable { mutableStateOf<Long?>(null) }
            SearchScreen(
                locator = locator,
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
                // Only wired up in debug builds; see the composable for why.
                onOpenSearchDebug = if (BuildConfig.DEBUG) {
                    { navController.navigate(Routes.SEARCH_DEBUG) }
                } else {
                    null
                },
                presetQuery = entry.arguments?.getString("preset").orEmpty(),
                visualQueryId = imageQuery,
                onPickImage = { navController.navigate(Routes.PICK_IMAGE) },
            )
            // The picker writes back through the same state on return.
            navController.currentBackStackEntry?.savedStateHandle
                ?.getStateFlow("picked_image", -1L)
                ?.let { flow ->
                    val picked by flow.collectAsStateWithLifecycle(initialValue = -1L)
                    LaunchedEffect(picked) {
                        if (picked >= 0) {
                            imageQuery = picked
                            navController.currentBackStackEntry
                                ?.savedStateHandle?.set("picked_image", -1L)
                        }
                    }
                }
        }
        composable(Routes.PICK_IMAGE) {
            ImagePickerScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onPick = { id ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set("picked_image", id)
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.BROWSE) {
            ScreenshotListScreen(
                locator = locator,
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
                onAskSelection = { ids -> navController.navigate(Routes.assistant(selection = ids)) },
                onCompareSelection = { ids ->
                    if (ids.size >= 2) navController.navigate(Routes.compare(ids[0], ids[1]))
                },
            )
        }
        composable(Routes.INSIGHTS) {
            InsightsScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.CLEANUP) {
            CleanupScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TASKS) {
            TasksScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.EXPENSES) {
            ExpensesScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.ACTIONS) {
            ActionCenterScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.AUTOMATION) {
            AutomationScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.ASSISTANT,
            arguments = listOf(
                navArgument("anchor") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("selection") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val anchor = entry.arguments?.getLong("anchor")?.takeIf { it >= 0 }
            val selection = entry.arguments?.getString("selection")
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                .orEmpty()
            AssistantScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
                anchor = anchor,
                selection = selection,
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
                onNavigateToInsights = { navController.navigate(Routes.INSIGHTS) },
                onNavigateToCleanup = { navController.navigate(Routes.CLEANUP) },
                onNavigateToPrivacy = { navController.navigate(Routes.PRIVACY) },
                onNavigateToTasks = { navController.navigate(Routes.TASKS) },
                onNavigateToExpenses = { navController.navigate(Routes.EXPENSES) },
                onNavigateToActions = { navController.navigate(Routes.ACTIONS) },
                onNavigateToAutomation = { navController.navigate(Routes.AUTOMATION) },
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
                onCompare = { firstId -> navController.navigate(Routes.compare(firstId)) },
                onExploreEntity = { entityId -> navController.navigate(Routes.entity(entityId)) },
                onSearchDomain = { domain ->
                    navController.navigate("${Routes.SEARCH}?preset=${java.net.URLEncoder.encode(domain, "UTF-8")}")
                },
                onFindVisuallySimilar = { navController.navigate(Routes.similar(id)) },
                onAskAbout = { shotId -> navController.navigate(Routes.assistant(anchor = shotId)) },
            )
        }
        composable(Routes.TIMELINE) {
            TimelineScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.COLLECTIONS) {
            CollectionsScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.EXPLORE) {
            ExploreScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onOpenEntity = { entityId -> navController.navigate(Routes.entity(entityId)) },
            )
        }
        composable(
            route = Routes.ENTITY,
            arguments = listOf(navArgument("entityId") { type = NavType.LongType }),
        ) { entry ->
            val entityId = entry.arguments?.getLong("entityId") ?: return@composable
            EntityScreen(
                locator = locator,
                entityId = entityId,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(
            route = Routes.SIMILAR,
            arguments = listOf(navArgument("screenshotId") { type = NavType.LongType }),
        ) { entry ->
            val shotId = entry.arguments?.getLong("screenshotId") ?: return@composable
            SimilarScreen(
                locator = locator,
                screenshotId = shotId,
                onBack = { navController.popBackStack() },
                onOpenScreenshot = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(
            route = Routes.COMPARE,
            arguments = listOf(
                navArgument("firstId") { type = NavType.LongType },
                navArgument("secondId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val firstId = entry.arguments?.getLong("firstId") ?: return@composable
            val secondId = entry.arguments?.getLong("secondId")?.takeIf { it >= 0 }
            CompareScreenHost(
                locator = locator,
                firstId = firstId,
                secondId = secondId,
                navController = navController,
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
