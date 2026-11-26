// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.AppContainer
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.McpScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.AntigravityScreen
import com.example.ui.screens.CodexAccountsScreen
import com.example.ui.screens.ProviderEditScreen
import com.example.ui.screens.ProvidersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SkillsScreen
import com.example.ui.screens.ProjectWorkspaceScreen
import com.example.ui.theme.YSCodeTheme
import com.example.ui.viewmodel.ConfigViewModel
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable data object Onboarding : AppRoute
    @Serializable data object Home : AppRoute
    @Serializable data object Workspace : AppRoute
    @Serializable data object Skills : AppRoute
    @Serializable data object Mcp : AppRoute
    @Serializable data object Providers : AppRoute
    @Serializable data object CodexAccounts : AppRoute
    @Serializable data object Antigravity : AppRoute
    @Serializable data class ProviderEdit(
        val providerId: String? = null,
        val presetId: String? = null
    ) : AppRoute
    @Serializable data object Permissions : AppRoute
    @Serializable data object Settings : AppRoute
}

@Composable
fun YSCodeApp(container: AppContainer) {
    val mainViewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(container))
    val configViewModel: ConfigViewModel = viewModel(factory = ConfigViewModel.Factory(container))

    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val context = LocalContext.current

    // Request runtime notification permission once on Android 13+ when foreground service runs
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(settings.onboardingCompleted) {
        if (settings.onboardingCompleted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        merge(mainViewModel.toastEvents, configViewModel.events).collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val startRoute: AppRoute = remember {
        if (settings.onboardingCompleted) AppRoute.Home else AppRoute.Onboarding
    }

    LaunchedEffect(settings.onboardingCompleted) {
        if (settings.onboardingCompleted && navController.currentDestination?.route?.contains("Onboarding") == true) {
            navController.navigate(AppRoute.Home) {
                popUpTo<AppRoute.Onboarding> { inclusive = true }
            }
        }
    }

    YSCodeTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding().imePadding()
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = startRoute
                ) {
                    composable<AppRoute.Onboarding> {
                        OnboardingScreen(
                            mainViewModel = mainViewModel,
                            configViewModel = configViewModel,
                            onFinish = {
                                navController.navigate(AppRoute.Permissions) {
                                    popUpTo<AppRoute.Onboarding> { inclusive = true }
                                }
                            }
                        )
                    }

                    composable<AppRoute.Permissions> {
                        PermissionsScreen(
                            mainViewModel = mainViewModel,
                            onDone = {
                                if (!navController.popBackStack()) {
                                    navController.navigate(AppRoute.Home) {
                                        popUpTo<AppRoute.Permissions> { inclusive = true }
                                    }
                                }
                            }
                        )
                    }

                    composable<AppRoute.Home> {
                        HomeScreen(
                            mainViewModel = mainViewModel,
                            onOpenProject = { project ->
                                mainViewModel.openProject(project)
                                navController.navigate(AppRoute.Workspace)
                            },
                            onOpenSkills = { navController.navigate(AppRoute.Skills) },
                            onOpenMcp = { navController.navigate(AppRoute.Mcp) },
                            onOpenSettings = { navController.navigate(AppRoute.Settings) }
                        )
                    }

                    composable<AppRoute.Workspace> {
                        ProjectWorkspaceScreen(
                            mainViewModel = mainViewModel,
                            onBackToProjects = { navController.popBackStack() },
                            onOpenSkills = { navController.navigate(AppRoute.Skills) },
                            onOpenMcp = { navController.navigate(AppRoute.Mcp) },
                            onOpenProviders = { navController.navigate(AppRoute.Providers) },
                            onOpenSettings = { navController.navigate(AppRoute.Settings) }
                        )
                    }

                    composable<AppRoute.Skills> {
                        SkillsScreen(
                            mainViewModel = mainViewModel,
                            configViewModel = configViewModel,
                            onBack = { navController.popBackStack() },
                            onCreateWithAgent = { prefillPrompt ->
                                mainViewModel.updateComposerText(prefillPrompt)
                                navController.popBackStack()
                            }
                        )
                    }

                    composable<AppRoute.Mcp> {
                        McpScreen(
                            mainViewModel = mainViewModel,
                            configViewModel = configViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<AppRoute.Providers> {
                        ProvidersScreen(
                            configViewModel = configViewModel,
                            onBack = { navController.popBackStack() },
                            onEditProvider = { providerId, presetId ->
                                navController.navigate(AppRoute.ProviderEdit(providerId, presetId))
                            },
                            onOpenCodex = { navController.navigate(AppRoute.CodexAccounts) },
                            onOpenAntigravity = { navController.navigate(AppRoute.Antigravity) }
                        )
                    }

                    composable<AppRoute.Antigravity> {
                        AntigravityScreen(
                            configViewModel = configViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<AppRoute.CodexAccounts> {
                        CodexAccountsScreen(
                            configViewModel = configViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<AppRoute.ProviderEdit> { backStackEntry ->
                        val args = backStackEntry.toRoute<AppRoute.ProviderEdit>()
                        ProviderEditScreen(
                            configViewModel = configViewModel,
                            providerId = args.providerId,
                            presetId = args.presetId,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<AppRoute.Settings> {
                        SettingsScreen(
                            mainViewModel = mainViewModel,
                            configViewModel = configViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenProviders = { navController.navigate(AppRoute.Providers) },
                            onOpenSkills = { navController.navigate(AppRoute.Skills) },
                            onOpenMcp = { navController.navigate(AppRoute.Mcp) },
                            onOpenPermissions = { navController.navigate(AppRoute.Permissions) }
                        )
                    }
                }
            }
        }
    }
}
