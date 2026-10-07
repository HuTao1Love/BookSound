package com.zyagodin.booksound.watch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.zyagodin.booksound.watch.WatchApp

class MainActivity : ComponentActivity() {
    private val container get() = (application as WatchApp).container

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
        private const val ROUTE_LIBRARY = "library"
        const val ROUTE_PLAYER = "player"
        const val ROUTE_CHAPTERS = "chapters"
        const val ROUTE_BOOK = "book"
    }
}
