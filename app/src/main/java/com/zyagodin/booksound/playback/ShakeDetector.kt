package com.zyagodin.booksound.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.VibratorManager
import com.zyagodin.booksound.core.playback.ShakeGesture
import kotlin.math.sqrt

/**
 * Listens to the accelerometer and calls [onShake] (on the main thread) when the phone is shaken.
 * Only registered while needed, so it costs nothing when the sleep timer is off.
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gesture = ShakeGesture()
    private var listening = false

    fun start() {
        if (listening || accelerometer == null) return
        gesture.reset()
        listening = sensors?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI) == true
    }

    fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gesture.onSample(event.timestamp / 1_000_000, g)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** A short double buzz, so the listener knows the shake worked without looking at the screen. */
    fun confirm() {
        runCatching {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }
}
