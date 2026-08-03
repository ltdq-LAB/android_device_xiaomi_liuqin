/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.touch

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.hardware.display.AmbientDisplayConfiguration
import android.net.Uri
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import org.lineageos.liuqinparts.stylus.StylusSettingsContract
import org.lineageos.liuqinparts.stylus.TouchFeatureClient

/** Keeps LCD wake modes aligned with persistent user settings. */
class WakeGestureSettingsController(
    context: Context,
    private val handler: Handler,
    private val touchFeatureClient: TouchFeatureClient,
) {
    private val context = context.applicationContext
    private val contentResolver = context.contentResolver
    private val ambientDisplayConfiguration = AmbientDisplayConfiguration(context)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val appliedValues = mutableMapOf<String, Boolean>()

    private val modes =
        listOf(
            GestureMode(
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.getUriFor(Settings.Secure.DOZE_DOUBLE_TAP_GESTURE),
                ambientDisplayConfiguration::doubleTapGestureEnabled,
                touchFeatureClient::setDoubleTapWakeEnabled,
            ),
            GestureMode(
                StylusSettingsContract.STYLUS_QUICK_NOTE_SCREEN_OFF,
                Settings.System.getUriFor(StylusSettingsContract.STYLUS_QUICK_NOTE_SCREEN_OFF),
                ::stylusQuickNoteEnabled,
                ::setStylusQuickNoteEnabled,
            ),
        )

    private var started = false
    @Volatile
    private var stylusQuickNoteRequested = false
    private var stylusQuickNoteRegistration: StylusQuickNoteRegistration? = null
    private var stylusQuickNoteRegistrationGeneration = 0L
    private var stylusQuickNoteRegistrationRetryCount = 0

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scheduleSync()
            }
        }

    private val userSwitchReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_SWITCHED) scheduleSync()
            }
        }

    private val syncRunnable = Runnable(::syncCurrentUser)
    private val stylusQuickNoteRegistrationRetryRunnable =
        Runnable(::updateStylusQuickNoteSensorRegistration)

    fun start() {
        if (started) return
        started = true

        modes.forEach { mode ->
            contentResolver.registerContentObserver(
                mode.uri,
                false,
                settingsObserver,
                UserHandle.USER_ALL,
            )
        }
        context.registerReceiver(
            userSwitchReceiver,
            IntentFilter(Intent.ACTION_USER_SWITCHED),
            null,
            handler,
            Context.RECEIVER_NOT_EXPORTED,
        )
        scheduleSync()
    }

    fun stop() {
        if (!started) return
        started = false
        stylusQuickNoteRequested = false
        handler.removeCallbacks(syncRunnable)
        updateStylusQuickNoteSensorRegistration()
        contentResolver.unregisterContentObserver(settingsObserver)
        context.unregisterReceiver(userSwitchReceiver)
        appliedValues.clear()
    }

    private fun scheduleSync() {
        if (!started) return
        handler.removeCallbacks(syncRunnable)
        handler.post(syncRunnable)
    }

    private fun syncCurrentUser() {
        if (!started) return
        val userId = ActivityManager.getCurrentUser()
        modes.forEach { mode -> applyMode(mode, mode.isEnabled(userId)) }
    }

    private fun applyMode(mode: GestureMode, enabled: Boolean) {
        if (appliedValues[mode.setting] == enabled) return
        mode.apply(enabled)
        appliedValues[mode.setting] = enabled
    }

    private fun stylusQuickNoteEnabled(userId: Int): Boolean =
        Settings.System.getIntForUser(
            contentResolver,
            StylusSettingsContract.STYLUS_QUICK_NOTE_SCREEN_OFF,
            if (StylusSettingsContract.DEFAULT_STYLUS_QUICK_NOTE_SCREEN_OFF) 1 else 0,
            userId,
        ) != 0

    private fun setStylusQuickNoteEnabled(enabled: Boolean) {
        if (!enabled) {
            stylusQuickNoteRequested = false
            updateStylusQuickNoteSensorRegistration()
        }

        touchFeatureClient.setStylusQuickNoteEnabled(enabled)

        if (enabled) {
            stylusQuickNoteRequested = true
            updateStylusQuickNoteSensorRegistration()
        }
    }

    private fun updateStylusQuickNoteSensorRegistration() {
        if (!started || !stylusQuickNoteRequested) {
            cancelStylusQuickNoteRegistrationRetry()
            cancelStylusQuickNoteSensorRegistration()
            return
        }
        if (stylusQuickNoteRegistration != null) return

        handler.removeCallbacks(stylusQuickNoteRegistrationRetryRunnable)
        val sensor = findStylusQuickNoteSensor()
        if (sensor == null) {
            scheduleStylusQuickNoteRegistrationRetry()
            return
        }

        val generation = ++stylusQuickNoteRegistrationGeneration
        val listener = createStylusQuickNoteListener(generation)
        val registered =
            try {
                sensorManager?.requestTriggerSensor(listener, sensor) == true
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not register the stylus quick-note sensor", exception)
                false
            }
        if (!registered) {
            scheduleStylusQuickNoteRegistrationRetry()
            return
        }

        stylusQuickNoteRegistration =
            StylusQuickNoteRegistration(sensor, listener, generation)
        cancelStylusQuickNoteRegistrationRetry()
    }

    private fun findStylusQuickNoteSensor(): Sensor? =
        try {
            sensorManager?.getSensorList(Sensor.TYPE_ALL)?.firstOrNull {
                it.stringType == STYLUS_QUICK_NOTE_SENSOR_TYPE
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not enumerate the stylus quick-note sensor", exception)
            null
        }

    private fun createStylusQuickNoteListener(generation: Long): TriggerEventListener =
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                val manager = powerManager
                if (stylusQuickNoteRequested && manager?.isInteractive == false) {
                    try {
                        manager.wakeUp(
                            SystemClock.uptimeMillis(),
                            PowerManager.WAKE_REASON_GESTURE,
                            TAG,
                        )
                    } catch (exception: RuntimeException) {
                        Log.w(TAG, "Could not wake for the stylus quick-note gesture", exception)
                    }
                }

                handler.post {
                    if (stylusQuickNoteRegistration?.generation != generation) return@post
                    stylusQuickNoteRegistration = null
                    if (!started || !stylusQuickNoteRequested) return@post
                    updateStylusQuickNoteSensorRegistration()
                }
            }
        }

    private fun scheduleStylusQuickNoteRegistrationRetry() {
        if (stylusQuickNoteRegistrationRetryCount >= MAX_SENSOR_REGISTRATION_RETRIES) {
            Log.e(TAG, "Stylus quick-note sensor registration retries exhausted")
            return
        }

        stylusQuickNoteRegistrationRetryCount++
        handler.removeCallbacks(stylusQuickNoteRegistrationRetryRunnable)
        handler.postDelayed(
            stylusQuickNoteRegistrationRetryRunnable,
            SENSOR_REGISTRATION_RETRY_DELAY_MS,
        )
    }

    private fun cancelStylusQuickNoteRegistrationRetry() {
        handler.removeCallbacks(stylusQuickNoteRegistrationRetryRunnable)
        stylusQuickNoteRegistrationRetryCount = 0
    }

    private fun cancelStylusQuickNoteSensorRegistration() {
        val registration = stylusQuickNoteRegistration ?: return
        stylusQuickNoteRegistration = null
        try {
            sensorManager?.cancelTriggerSensor(registration.listener, registration.sensor)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not cancel the stylus quick-note sensor", exception)
        }
    }

    private data class GestureMode(
        val setting: String,
        val uri: Uri,
        val isEnabled: (Int) -> Boolean,
        val apply: (Boolean) -> Unit,
    )

    private data class StylusQuickNoteRegistration(
        val sensor: Sensor,
        val listener: TriggerEventListener,
        val generation: Long,
    )

    companion object {
        private const val TAG = "LiuqinParts.WakeGestures"
        private const val MAX_SENSOR_REGISTRATION_RETRIES = 5
        private const val SENSOR_REGISTRATION_RETRY_DELAY_MS = 2_000L
        private const val STYLUS_QUICK_NOTE_SENSOR_TYPE =
            "org.lineageos.sensor.stylus_quick_note"
    }
}
