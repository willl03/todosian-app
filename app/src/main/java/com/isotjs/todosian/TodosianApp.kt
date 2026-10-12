package com.isotjs.todosian

import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.ui.addtask.AddTaskScreen
import com.isotjs.todosian.ui.folder.FolderScreen
import com.isotjs.todosian.ui.dailyfocus.DailyFocusScreen
import com.isotjs.todosian.ui.home.HomeScreen
import com.isotjs.todosian.ui.category.ListPagerScreen
import com.isotjs.todosian.ui.onboarding.OnboardingScreen
import com.isotjs.todosian.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TodosianApp(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    preferencesManager: PreferencesManager,
    addTaskSignal: StateFlow<AddTaskRequest>,
    initialAddTaskRequest: AddTaskRequest,
) {
    val navController = rememberNavController()
    val addTaskRequest by addTaskSignal.collectAsStateWithLifecycle(initialValue = initialAddTaskRequest)
    val startDestination = remember(fileRepository, addTaskRequest) {
        val categoryUri = addTaskRequest.categoryUri
        when {
            fileRepository.getFolderUri() == null -> Routes.Onboarding
            addTaskRequest.tick > 0L && categoryUri != null ->
                Routes.category(categoryUri.toUri(), addTodo = true)
            addTaskRequest.tick > 0L -> Routes.AddTask
            else -> Routes.Home
        }
    }

    LaunchedEffect(addTaskRequest) {
        val request = addTaskRequest
        if (request.tick > 0L && fileRepository.getFolderUri() != null) {
            val destination = navController.currentDestination?.route
            when {
                request.categoryUri != null && destination != Routes.ListPager ->
                    navController.navigate(
                        Routes.category(Uri.parse(request.categoryUri), addTodo = true),
                    ) { launchSingleTop = true }

                request.categoryUri == null && destination != Routes.AddTask ->
                    navController.navigate(Routes.AddTask) { launchSingleTop = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
        // Predictive back gestures (swipe from edge) use these instead of popEnter/popExit.
        // Without them, the system's default scale-down "zoom out" animation plays, which
        // clashes with the app's slide-based motion language.
        predictivePopEnterTransition = { _ ->
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        predictivePopExitTransition = { _ ->
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(
                fileRepository = fileRepository,
                onFinished = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Home) { backStackEntry ->
            val refreshSignal = backStackEntry.savedStateHandle
                .getStateFlow(KEY_REFRESH_HOME, 0L)
                .collectAsStateWithLifecycle()
                .value

            HomeScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                preferencesManager = preferencesManager,
                onOpenFolder = { folder ->
                    navController.navigate(Routes.folder(folder.uri, folder.isUnfiled))
                },
                onOpenList = { categoryUri ->
                    navController.navigate(Routes.category(categoryUri))
                },
                onOpenSettings = {
                    navController.navigate(Routes.Settings)
                },
                onOpenDailyFocus = {
                    navController.navigate(Routes.DailyFocus)
                },
                refreshSignal = refreshSignal,
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                preferencesManager = preferencesManager,
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.Folder,
            arguments = listOf(
                navArgument(Routes.ARG_FOLDER_URI) {
                    type = NavType.StringType
                },
                navArgument(Routes.ARG_FOLDER_UNFILED) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(Routes.ARG_FOLDER_URI).orEmpty()
            val folderUri = encoded.toUri()
            val isUnfiled = backStackEntry.arguments?.getBoolean(Routes.ARG_FOLDER_UNFILED) ?: false
            FolderScreen(
                fileRepository = fileRepository,
                folderUri = folderUri,
                isUnfiled = isUnfiled,
                onOpenList = { categoryUri ->
                    navController.navigate(Routes.category(categoryUri))
                },
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Routes.ListPager,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_URI) {
                    type = NavType.StringType
                },
                navArgument(Routes.ARG_CATEGORY_ADD) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(Routes.ARG_CATEGORY_URI).orEmpty()
            val categoryUri = encoded.toUri()
            val autoOpenAddTodo = backStackEntry.arguments?.getBoolean(Routes.ARG_CATEGORY_ADD) ?: false
            ListPagerScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                categoryUri = categoryUri,
                autoOpenAddTodo = autoOpenAddTodo,
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
                onOpenFolderView = { folderUri ->
                    navController.navigate(Routes.folder(folderUri))
                },
            )
        }

        composable(Routes.AddTask) {
            AddTaskScreen(
                fileRepository = fileRepository,
                onSelectList = { uri ->
                    navController.navigate(Routes.category(uri, addTodo = true))
                },
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.DailyFocus) {
            DailyFocusScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private const val KEY_REFRESH_HOME = "refresh_home"

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Settings = "settings"
    const val DailyFocus = "daily_focus"
    const val AddTask = "add_task"

    const val ARG_FOLDER_URI = "folderUri"
    const val ARG_FOLDER_UNFILED = "unfiled"
    const val Folder = "folder/{$ARG_FOLDER_URI}?$ARG_FOLDER_UNFILED={$ARG_FOLDER_UNFILED}"

    fun folder(folderUri: Uri, isUnfiled: Boolean = false): String =
        "folder/${Uri.encode(folderUri.toString())}?$ARG_FOLDER_UNFILED=$isUnfiled"

    const val ARG_CATEGORY_URI = "categoryUri"
    const val ARG_CATEGORY_ADD = "add"
    // Named after upstream's Routes.Category, but in this fork the destination is the pager.
    const val ListPager = "list/{$ARG_CATEGORY_URI}?$ARG_CATEGORY_ADD={$ARG_CATEGORY_ADD}"

    // Same call shape as upstream's Routes.category(uri, addTodo); routes into the pager.
    fun category(categoryUri: Uri, addTodo: Boolean = false): String =
        "list/${Uri.encode(categoryUri.toString())}?$ARG_CATEGORY_ADD=$addTodo"
}
