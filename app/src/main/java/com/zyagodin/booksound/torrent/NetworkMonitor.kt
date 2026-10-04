package com.zyagodin.booksound.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the device currently has a validated internet connection. */
class NetworkMonitor(context: Context) {
    private val connectivity: ConnectivityManager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online

    /**
     * Called when the default network really changes (connect, disconnect, Wi-Fi ⇄ mobile). Not
     * for capability updates of the same network, which Android reports every few seconds.
     */
    var onChanged: (() -> Unit)? = null

    private var current: Network? = connectivity.activeNetwork

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish()
        })
    }

    @Synchronized
    private fun publish() {
        val network = connectivity.activeNetwork
        val now = isOnlineNow()
        val changed = now != _online.value || network != current
        current = network
        _online.value = now
        if (changed) onChanged?.invoke()
    }

    private fun isOnlineNow(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps: NetworkCapabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
