package com.zyagodin.booksound.watch.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.core.wear.PhoneBook
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Plays and pauses the phone's book from the watch (see [WatchPaths.PHONE_BOOK_PATH]). */
class PhoneRemote(context: Context) {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val data = Wearable.getDataClient(context)
    private val messages = Wearable.getMessageClient(context)

    /** The phone's book while a phone with BookSound is connected, otherwise null. */
    val book: Flow<PhoneBook?> = combine(reachable(), published()) { reachable, book -> book.takeIf { reachable } }
        .distinctUntilChanged()

    /** Asks the phone to play or pause; false when no phone could be reached. */
    suspend fun send(command: String): Boolean = try {
        val node = phone() ?: return false
        messages.sendMessage(node.id, WatchPaths.PHONE_COMMAND_PREFIX + command, ByteArray(0)).await()
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Can't reach the phone", e)
        false
    }

    private suspend fun phone(): Node? =
        capabilities.getCapability(WatchPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.sortedByDescending { it.isNearby }.firstOrNull()

    private fun reachable(): Flow<Boolean> = callbackFlow {
        val listener = CapabilityClient.OnCapabilityChangedListener { trySend(it.nodes.isNotEmpty()) }
        capabilities.addListener(listener, WatchPaths.PHONE_CAPABILITY)
        launch {
            val found = runCatching { phone() != null }.getOrDefault(false)
            trySend(found)
        }
        awaitClose { capabilities.removeListener(listener, WatchPaths.PHONE_CAPABILITY) }
    }

    private fun published(): Flow<PhoneBook?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            // The buffer is released after this call: decode now.
            events.filter { it.type == DataEvent.TYPE_CHANGED }.firstNotNullOfOrNull { decode(it.dataItem) }?.let { trySend(it) }
        }
        data.addListener(listener, Uri.parse("wear://*" + WatchPaths.PHONE_BOOK_PATH), DataClient.FILTER_LITERAL)
        launch {
            // A URI without a host matches the item of every node.
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchPaths.PHONE_BOOK_PATH).build()
            runCatching {
                val buffer = data.getDataItems(uri).await()
                try {
                    buffer.firstNotNullOfOrNull(::decode)
                } finally {
                    buffer.release()
                }
            }.getOrNull()?.let { trySend(it) }
        }
        trySend(null)
        awaitClose { data.removeListener(listener) }
    }

    private fun decode(item: DataItem): PhoneBook? =
        item.data?.takeIf { item.uri.path == WatchPaths.PHONE_BOOK_PATH }?.let { runCatching { WatchCodec.decodePhoneBook(it) }.getOrNull() }

    private companion object {
        const val TAG = "PhoneRemote"
    }
}
