package xyz.aezdek.aezprint.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import xyz.aezdek.aezprint.BuildConfig
import xyz.aezdek.aezprint.designsystem.motion.LocalNavAnimatedVisibilityScope
import xyz.aezdek.aezprint.designsystem.motion.LocalSharedTransitionScope
import xyz.aezdek.aezprint.feature.home.HomeRoute
import xyz.aezdek.aezprint.feature.jobs.history.JobHistoryRoute
import xyz.aezdek.aezprint.feature.jobs.progress.JobProgressRoute
import xyz.aezdek.aezprint.feature.print.PrintRoute
import xyz.aezdek.aezprint.feature.settings.SettingsRoute

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AezPrintNavHost(navController: NavHostController) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(navController = navController, startDestination = HomeDestination) {
                composable<HomeDestination> {
                    WithVisibility {
                        HomeRoute(
                            onOpenUri = { navController.navigate(PrintDestination(uri = it)) },
                            onOpenDocument = { navController.navigate(PrintDestination(documentId = it)) },
                            onOpenJob = { navController.navigate(JobDestination(it)) },
                            onOpenHistory = { navController.navigate(HistoryDestination) },
                            onOpenSettings = { navController.navigate(SettingsDestination) },
                        )
                    }
                }
                composable<PrintDestination> { entry ->
                    val route = entry.toRoute<PrintDestination>()
                    WithVisibility {
                        PrintRoute(
                            sharedDocumentId = route.documentId,
                            onBack = { navController.popBackStack() },
                            onOpenJob = { localId ->
                                navController.navigate(JobDestination(localId)) {
                                    popUpTo<HomeDestination>()
                                }
                            },
                            onOpenSettings = { navController.navigate(SettingsDestination) },
                        )
                    }
                }
                composable<JobDestination> {
                    WithVisibility {
                        JobProgressRoute(
                            onBack = { if (!navController.popBackStack()) navController.navigate(HomeDestination) },
                            onOpenJob = { localId ->
                                navController.navigate(JobDestination(localId)) {
                                    popUpTo<HomeDestination>()
                                }
                            },
                        )
                    }
                }
                composable<HistoryDestination> {
                    WithVisibility {
                        JobHistoryRoute(
                            onBack = { navController.popBackStack() },
                            onOpenJob = { navController.navigate(JobDestination(it)) },
                        )
                    }
                }
                composable<SettingsDestination> {
                    WithVisibility {
                        SettingsRoute(onBack = { navController.popBackStack() }, versionName = BuildConfig.VERSION_NAME)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedContentScope.WithVisibility(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this, content = content)
}
