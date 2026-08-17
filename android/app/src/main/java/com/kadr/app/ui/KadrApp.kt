package com.kadr.app.ui

import android.net.Uri
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
import com.kadr.app.ui.albums.AlbumDetailScreen
import com.kadr.app.ui.albums.AlbumsScreen
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
    const val ALBUMS = "albums"
    const val ALBUM = "albums/{id}/{name}"

    fun album(id: String, name: String) = "albums/$id/${Uri.encode(name)}"
    /**
     * The photo itself, not its position. A position taken from a partly loaded
     * timeline would point somewhere else as soon as more of it was read; the
     * key never moves, and the viewer asks the database where it sits.
     */
    const val VIEWER = "viewer/{key}/{capturedAt}?album={album}"

    fun viewer(key: String, capturedAt: Long, albumId: String? = null): String {
        val base = "viewer/${Uri.encode(key)}/$capturedAt"
        return if (albumId == null) base else "$base?album=${Uri.encode(albumId)}"
    }
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
                    onOpenPhoto = { item ->
                        navController.navigate(Routes.viewer(item.key, item.capturedAt))
                    },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) },
                    onOpenAlbums = { navController.navigate(Routes.ALBUMS) },
                )
            }

            composable(
                route = Routes.VIEWER,
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType },
                    navArgument("capturedAt") { type = NavType.LongType },
                    navArgument("album") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                ViewerScreen(
                    viewModel = galleryViewModel,
                    animatedVisibilityScope = this@composable,
                    startKey = backStackEntry.arguments?.getString("key").orEmpty(),
                    startCapturedAt = backStackEntry.arguments?.getLong("capturedAt") ?: 0L,
                    onClose = { navController.popBackStack() },
                    albumId = backStackEntry.arguments?.getString("album"),
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

            composable(Routes.ALBUMS) {
                AlbumsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { id, name -> navController.navigate(Routes.album(id, name)) },
                )
            }

            composable(
                route = Routes.ALBUM,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                AlbumDetailScreen(
                    albumId = backStackEntry.arguments?.getString("id").orEmpty(),
                    albumName = backStackEntry.arguments?.getString("name").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenPhoto = { item ->
                        navController.navigate(
                            Routes.viewer(
                                item.key,
                                item.capturedAt,
                                albumId = backStackEntry.arguments?.getString("id"),
                            ),
                        )
                    },
                )
            }
        }
    }
}
