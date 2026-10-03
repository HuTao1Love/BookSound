package com.zyagodin.booksound

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zyagodin.booksound.ui.AppRoot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val container get() = (application as BookSoundApp).container

    /** One-shot navigation requests from notifications (open player / open imports). */
    private val actions = Channel<String>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppRoot(container, actions.receiveAsFlow())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.appVisible = true
        container.player.connect()
    }

    override fun onStop() {
        container.appVisible = false
        container.player.disconnect()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_PLAYER, ACTION_OPEN_IMPORTS -> actions.trySend(intent.action!!)
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.zyagodin.booksound.OPEN_PLAYER"
        const val ACTION_OPEN_IMPORTS = "com.zyagodin.booksound.OPEN_IMPORTS"
    }
}
