package com.zyagodin.booksound.shared.server

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The signed-in BookSound server of this device. [deviceId] is the device's id on the server. */
data class ServerAccount(
    val url: String,
    val userName: String,
    val isAdmin: Boolean,
    val deviceId: String,
    val refreshToken: String,
    val accessToken: String? = null,
    val accessExpiresAt: Long = 0,
)

/**
 * Keeps the account in the app's private storage. Only tokens are kept, never the password; signing out
 * or the server revoking the device clears it.
 */
class ServerAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("server_account", Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(load())
    val account: StateFlow<ServerAccount?> = _account

    val current: ServerAccount? get() = _account.value

    private fun load(): ServerAccount? {
        val url = prefs.getString("url", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        return ServerAccount(
            url = url,
            userName = prefs.getString("user", "").orEmpty(),
            isAdmin = prefs.getBoolean("admin", false),
            deviceId = prefs.getString("device", "").orEmpty(),
            refreshToken = refresh,
            accessToken = prefs.getString("access", null),
            accessExpiresAt = prefs.getLong("access_expires", 0),
        )
    }

    @Synchronized
    fun save(account: ServerAccount) {
        prefs.edit(commit = true) {
            putString("url", account.url)
            putString("user", account.userName)
            putBoolean("admin", account.isAdmin)
            putString("device", account.deviceId)
            putString("refresh", account.refreshToken)
            putString("access", account.accessToken)
            putLong("access_expires", account.accessExpiresAt)
        }
        _account.value = account
    }

    /** Position in the server's change feed; reset with the account, so another server is read from the start. */
    var cursor: String?
        get() = prefs.getString("cursor", null)
        set(value) = prefs.edit(commit = true) { putString("cursor", value) }

    @Synchronized
    fun clear() {
        prefs.edit(commit = true) { clear() }
        _account.value = null
    }
}
