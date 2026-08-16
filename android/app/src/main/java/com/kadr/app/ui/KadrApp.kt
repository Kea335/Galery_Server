package com.kadr.app.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.ui.debug.DebugScreen
import com.kadr.app.ui.gallery.GalleryViewModel
import com.kadr.app.ui.gallery.TimelineScreen
import com.kadr.app.ui.gallery.ViewerScreen
import com.kadr.app.ui.login.LoginScreen
import com.kadr.app.ui.settings.SettingsScreen
import com.kadr.app.ui.settings.TrashScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val TIMELINE = "timeline"
    const val BACKUP = "backup"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val VIEWER = "viewer/{index}"

    fun viewer(index: Int) = "viewer/$index"
}

@HiltViewModel
class RootViewModel @Inject constructor(settingsStore: SettingsStore) : ViewModel() {
    val settings: StateFlow<KadrSettings> = settingsStore.settings
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KadrApp() {
    val navController = rememberNavController()
    val rootViewModel: RootViewModel = hiltViewModel()
    val settings by rootViewModel.settings.collectAsStateWithLifecycle()

    // Read once: after this, the screens drive navigation themselves, so
    // re-pairing does not yank the user mid-screen.
    val startDestination = remember { if (settings.isPaired) Routes.TIMELINE else Routes.LOGIN }

    // One instance for both the grid and the viewer, so the pager indexes line
    // up with what the timeline is showing.
    val galleryViewModel: GalleryViewModel = hiltViewModel()

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            // Springs everywhere; §12 rules out linear interpolators.
            enterTransition = { fadeIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) },
            exitTransition = { fadeOut(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) },
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onSignedIn = {
                        navController.navigate(Routes.TIMELINE) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.TIMELINE) {
                TimelineScreen(
                    viewModel = galleryViewModel,
                    animatedVisibilityScope = this@composable,
                    onOpenPhoto = { index -> navController.navigate(Routes.viewer(index)) },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) },
                )
            }

            composable(
                route = Routes.VIEWER,
                arguments = listOf(navArgument("index") { type = NavType.IntType }),
            ) { backStackEntry ->
                ViewerScreen(
                    viewModel = galleryViewModel,
                    animatedVisibilityScope = this@composable,
                    startIndex = backStackEntry.arguments?.getInt("index") ?: 0,
                    onClose = { navController.popBackStack() },
                )
            }

            composable(Routes.BACKUP) {
                DebugScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onUnpaired = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTrash = { navController.navigate(Routes.TRASH) },
                    onUnpaired = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.TRASH) {
                TrashScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
