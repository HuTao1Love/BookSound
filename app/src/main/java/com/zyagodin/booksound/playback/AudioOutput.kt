package com.zyagodin.booksound.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Where the book is heard: the phone itself, headphones or a speaker. */
data class AudioOutput(val kind: Kind, val name: String?) {
    enum class Kind { PHONE, BLUETOOTH, WIRED, OTHER }
}

/** Follows the device media plays on, and opens the system's output picker. */
class AudioOutputs(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** The current output, updated as headphones and speakers come and go. */
    val current: Flow<AudioOutput> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { trySend(read()) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { trySend(read()) }
        }
        trySend(read())
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audio.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    private fun read(): AudioOutput {
        // Where media would play now; the connected devices as a fallback.
        val device = runCatching { audio.getAudioDevicesForAttributes(media).firstOrNull() }.getOrNull()
            ?: audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).maxByOrNull { priority(it.type) }
        val kind = when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutput.Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioOutput.Kind.WIRED
            null, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutput.Kind.PHONE
            else -> AudioOutput.Kind.OTHER
        }
        val name = device?.productName?.toString()?.takeIf { it.isNotBlank() && kind != AudioOutput.Kind.PHONE && it != android.os.Build.MODEL }
        return AudioOutput(kind, name)
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    /**
     * The system's output picker (Bluetooth headphones, speakers, the phone), from an activity
     * [context]. Without one, the Bluetooth settings.
     */
    fun openPicker(context: Context) {
        if (SystemOutputSwitcherDialogController.showDialog(context)) return
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            // Nothing to open on this device.
        }
    }
}
