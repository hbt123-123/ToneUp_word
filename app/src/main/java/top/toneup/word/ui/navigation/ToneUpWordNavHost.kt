package top.toneup.word.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import top.toneup.word.AppContainer
import top.toneup.word.data.update.AppUpdateUiState
import top.toneup.word.ui.ExamViewModel
import top.toneup.word.ui.ViewModelFactories
import top.toneup.word.ui.screens.AboutScreen
import top.toneup.word.ui.screens.DataTransferScreen
import top.toneup.word.ui.screens.ExamResultScreen
import top.toneup.word.ui.screens.ExamRunScreen
import top.toneup.word.ui.screens.ExamSetupScreen
import top.toneup.word.ui.screens.HomeScreen
import top.toneup.word.ui.screens.ImportWordsScreen
import top.toneup.word.ui.screens.LicensesScreen
import top.toneup.word.ui.screens.OnboardingScreen
import top.toneup.word.ui.screens.PlanDetailScreen
import top.toneup.word.ui.screens.PlanListScreen
import top.toneup.word.ui.screens.ProfileScreen
import top.toneup.word.ui.screens.ReviewCenterScreen
import top.toneup.word.ui.screens.SettingsScreen
import top.toneup.word.ui.screens.StatsScreen
import top.toneup.word.ui.screens.StudyScreen
import top.toneup.word.ui.screens.WordBrowserScreen
import top.toneup.word.ui.screens.WordDetailScreen
import top.toneup.word.ui.screens.WriteWordScreen
import top.toneup.word.ui.theme.AppTheme

/**
 * The single navigation graph.
 *
 * Conventions that keep the screens independent and testable:
 *  * a screen never receives the `NavController` — only typed lambdas such as `onOpenWord(id)`;
 *  * every route argument is parsed here into a domain value before it reaches a screen;
 *  * the bottom bar is shown only on the five top-level destinations, so the study session and
 *    the exam remain focused modes with no way to wander off by tapping a tab.
 */
@Composable
fun ToneUpWordNavHost(
    container: AppContainer,
    onboardingCompleted: Boolean,
    updateState: AppUpdateUiState = AppUpdateUiState(),
    onCheckUpdate: () -> Unit = {},
    onShowUpdate: () -> Unit = {},
    onCurrentRoute: (String) -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = TopLevelDestination.fromRoute(backStackEntry?.destination?.route)

    // Publish the active route to the host: it defers the automatic update prompt to
    // home/profile so the dialog can never interrupt onboarding or an exam.
    val route = backStackEntry?.destination?.route
    LaunchedEffect(route) { route?.let(onCurrentRoute) }

    // One exam ViewModel for the setup → run → result flow, so an exam survives navigation
    // between those three screens without re-reading the database on every step.
    val examViewModel: ExamViewModel = viewModel(factory = ViewModelFactories.exam(container))

    // Navigation Compose's default is a 700 ms cross-fade, during which two full screens are
    // composed, measured and drawn simultaneously — the most expensive frame sequence in the app,
    // and far longer than the Material 3 medium motion used everywhere else. These transitions
    // keep a real transition (fade + a small shared-axis slide, both on the graphics layer) at the
    // app's own motion duration, and they honour the in-app animation switch.
    val motion = AppTheme.motion
    val transitionMillis = motion.durationMillis(NAV_TRANSITION_MILLIS)
    val enterTransition = fadeIn(tween(transitionMillis)) +
        slideInHorizontally(tween(transitionMillis), initialOffsetX = { it / 16 })
    val exitTransition = fadeOut(tween(transitionMillis)) +
        slideOutHorizontally(tween(transitionMillis), targetOffsetX = { -it / 16 })
    val popEnterTransition = fadeIn(tween(transitionMillis)) +
        slideInHorizontally(tween(transitionMillis), initialOffsetX = { -it / 16 })
    val popExitTransition = fadeOut(tween(transitionMillis)) +
        slideOutHorizontally(tween(transitionMillis), targetOffsetX = { it / 16 })

    // The bottom bar has no job while the keyboard is up — it only sat flush against the IME
    // (colliding with the input method's floating composing bar) and stole ~80dp from the form.
    // `WindowInsets.isImeVisible` is still an experimental API in this Compose version, so the
    // visibility is derived from the stable `WindowInsets.ime` object instead: `getBottom` reads
    // snapshot-backed inset state inside `derivedStateOf`, so during the IME slide only this
    // boolean is invalidated — the tree below recomposes twice (shown/hidden), not every frame.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val imeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentDestination != null && !imeVisible) {
                AppBottomNavigation(
                    current = currentDestination,
                    onSelect = { destination ->
                        if (destination.route != backStackEntry?.destination?.route) {
                            navController.navigate(destination.route) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (onboardingCompleted) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = { enterTransition },
            exitTransition = { exitTransition },
            popEnterTransition = { popEnterTransition },
            popExitTransition = { popExitTransition },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    container = container,
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onStartStudy = { navController.navigate(Routes.studySession()) },
                    onResumeStudy = { navController.navigate(Routes.studySession()) },
                    onStartMistakeDrill = { navController.navigate(Routes.REVIEW) },
                    onOpenReviewCenter = { navController.navigate(Routes.REVIEW) },
                    onOpenStats = { navController.navigate(Routes.STATS) },
                    onOpenWordBrowser = { navController.navigate(Routes.WORD_BROWSER) },
                    onOpenPlans = { navController.navigate(Routes.PLANS) },
                )
            }

            composable(Routes.WRITE_WORD) {
                WriteWordScreen(container = container)
            }

            composable(
                route = Routes.STUDY_SESSION,
                arguments = listOf(
                    navArgument(Routes.ARG_DRILL_IDS) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val raw = entry.arguments?.getString(Routes.ARG_DRILL_IDS).orEmpty()
                val drillIds = raw.split(',')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .filter { it > 0 }
                StudyScreen(
                    container = container,
                    drillIds = drillIds,
                    onExit = { navController.popBackStack() },
                    onGoHome = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.REVIEW) {
                ReviewCenterScreen(
                    container = container,
                    onOpenWord = { wordId -> navController.navigate(Routes.wordDetail(wordId)) },
                    onStartDrill = { ids -> navController.navigate(Routes.studySession(ids)) },
                )
            }

            composable(
                route = Routes.WORD_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_WORD_ID) { type = NavType.LongType }),
            ) { entry ->
                val wordId = entry.arguments?.getLong(Routes.ARG_WORD_ID) ?: 0L
                WordDetailScreen(
                    container = container,
                    wordId = wordId,
                    onBack = { navController.popBackStack() },
                    onPractice = { id -> navController.navigate(Routes.studySession(listOf(id))) },
                )
            }

            composable(Routes.EXAM_SETUP) {
                ExamSetupScreen(
                    viewModel = examViewModel,
                    onOpenExam = { examId -> navController.navigate(Routes.examRun(examId)) },
                )
            }

            composable(
                route = Routes.EXAM_RUN,
                arguments = listOf(navArgument(Routes.ARG_EXAM_ID) { type = NavType.LongType }),
            ) { entry ->
                val examId = entry.arguments?.getLong(Routes.ARG_EXAM_ID) ?: 0L
                ExamRunScreen(
                    viewModel = examViewModel,
                    onExit = { navController.popBackStack() },
                    onSubmitted = { submittedId ->
                        navController.navigate(Routes.examResult(submittedId)) {
                            popUpTo(Routes.EXAM_SETUP) { inclusive = false }
                        }
                    },
                )
            }

            composable(
                route = Routes.EXAM_RESULT,
                arguments = listOf(navArgument(Routes.ARG_EXAM_ID) { type = NavType.LongType }),
            ) { entry ->
                val examId = entry.arguments?.getLong(Routes.ARG_EXAM_ID) ?: 0L
                ExamResultScreen(
                    viewModel = examViewModel,
                    examId = examId,
                    onRetryWrongWords = { ids -> navController.navigate(Routes.studySession(ids)) },
                    onGoHome = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBackToSetup = { navController.navigate(Routes.EXAM_SETUP) },
                )
            }

            composable(Routes.STATS) {
                StatsScreen(container = container)
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    container = container,
                    updateState = updateState,
                    onCheckUpdate = onCheckUpdate,
                    onShowUpdate = onShowUpdate,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenDataTransfer = { navController.navigate(Routes.DATA_TRANSFER) },
                    onOpenWordBrowser = { navController.navigate(Routes.WORD_BROWSER) },
                    onOpenPlans = { navController.navigate(Routes.PLANS) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                )
            }

            composable(Routes.PLANS) {
                PlanListScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenPlan = { planId -> navController.navigate(Routes.planDetail(planId)) },
                )
            }

            composable(
                route = Routes.PLAN_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_PLAN_ID) { type = NavType.StringType }),
            ) { entry ->
                val planId = entry.arguments?.getString(Routes.ARG_PLAN_ID).orEmpty()
                PlanDetailScreen(
                    container = container,
                    planId = planId,
                    onBack = { navController.popBackStack() },
                    onOpenImport = { packId -> navController.navigate(Routes.importWords(packId)) },
                )
            }

            composable(
                route = Routes.IMPORT_WORDS,
                arguments = listOf(navArgument(Routes.ARG_PACK_ID) { type = NavType.StringType }),
            ) { entry ->
                val packId = entry.arguments?.getString(Routes.ARG_PACK_ID).orEmpty()
                ImportWordsScreen(
                    container = container,
                    packId = packId,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.WORD_BROWSER) {
                WordBrowserScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenWord = { wordId -> navController.navigate(Routes.wordDetail(wordId)) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable(Routes.DATA_TRANSFER) {
                DataTransferScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable(Routes.ABOUT) {
                AboutScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable(Routes.LICENSES) {
                LicensesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Screen transition duration.
 *
 * Material 3's "medium 2" duration, matching the rest of the app's motion. Long enough to read
 * as a transition, short enough that two screens are not drawn simultaneously for most of a
 * second; the in-app animation switch still collapses it to zero.
 */
private const val NAV_TRANSITION_MILLIS = 300
