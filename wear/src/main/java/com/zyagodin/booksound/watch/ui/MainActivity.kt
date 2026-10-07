package com.zyagodin.booksound.watch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.zyagodin.booksound.watch.WatchApp

class MainActivity : ComponentActivity() {
    private val container get() = (application as WatchApp).container
    /** Set by the tile or the complication: show the player once the screens are up. */
    private val openPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // For the playback controls and "update ready" notifications; the answer needs no handling.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme {
                AppScaffold {
                    val nav = rememberSwipeDismissableNavController()
                    val showPlayer by openPlayer
                    LaunchedEffect(showPlayer) {
                        if (showPlayer) {
                            nav.navigate(ROUTE_PLAYER) { launchSingleTop = true }
                            openPlayer.value = false
                        }
                    }
                    SwipeDismissableNavHost(navController = nav, startDestination = ROUTE_LIBRARY) {
                        composable(ROUTE_LIBRARY) {
                            LibraryScreen(
                                container,
                                onOpenPlayer = { nav.navigate(ROUTE_PLAYER) { launchSingleTop = true } },
                                onOpenBook = { nav.navigate("$ROUTE_BOOK/$it") },
                            )
                        }
                        composable(ROUTE_PLAYER) { PlayerScreen(container, onOpenChapters = { nav.navigate(ROUTE_CHAPTERS) }) }
                        composable(ROUTE_CHAPTERS) { ChaptersScreen(container, onDone = { nav.popBackStack() }) }
                        composable("$ROUTE_BOOK/{id}") { entry ->
                            BookScreen(
                                container,
                                bookId = entry.arguments?.getString("id").orEmpty(),
                                onListen = {
                                    nav.popBackStack()
                                    nav.navigate(ROUTE_PLAYER)
                                },
                                onDeleted = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_INSTALL_UPDATE) container.updates.install()
        val play = intent?.getStringExtra(EXTRA_PLAY_BOOK)
        play?.let { container.player.play(it) }
        // Without a book in the player there is nothing to show there: the library opens instead.
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true && (play != null || container.surfaces.nowPlaying.value != null)) {
            openPlayer.value = true
        }
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
    }

    // Not in onStop(): the system's headphones picker covers this activity while playback waits
    // for an output, and with no controller left the service would stop before it could play.
    override fun onDestroy() {
        container.player.disconnect()
        super.onDestroy()
    }

    companion object {
        /** From the "update ready" notification: open the installer. */
        const val ACTION_INSTALL_UPDATE = "com.zyagodin.booksound.watch.INSTALL_UPDATE"
        /** From the tile and the complication: show the player. */
        const val EXTRA_OPEN_PLAYER = "open_player"
        /** From the tile: start playing this book. */
        const val EXTRA_PLAY_BOOK = "play_book"
        private const val ROUTE_LIBRARY = "library"
        const val ROUTE_PLAYER = "player"
        const val ROUTE_CHAPTERS = "chapters"
        const val ROUTE_BOOK = "book"
    }
}
