/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.RemoteException
import android.util.Log
import android.view.InputDevice
import vendor.xiaomi.hw.touchfeature.V1_0.ITouchFeature

/**
 * Replays the stock liuqin TouchFeature state used by framework integrations.
 *
 * Wake modes 14/24 are persistent values. Stylus mode-20 events are deltas;
 * recovery follows the stock listener by sending reset (-1), then replaying
 * every currently connected Xiaomi stylus. The exact liuqin reset clears the
 * supported-pen counter but deliberately does not clear the legacy type-1
 * shield. Individual stylus events are never retried in isolation.
 */
class TouchFeatureClient(
    context: Context,
    private val handler: Handler,
) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val connectedTypes = mutableMapOf<Int, Int>()
    private val persistentModes = mutableMapOf<Int, Boolean>()

    private var service: ITouchFeature? = null
    private var started = false
    private var replayScheduled = false

    fun start() {
        if (started) return
        started = true
        inputManager?.registerInputDeviceListener(this, handler)
        refreshConnectedDevices()
        resetAndReplay(replayPersistentModes = true)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(replayRunnable)
        replayScheduled = false
        inputManager?.unregisterInputDeviceListener(this)
        connectedTypes.clear()
        persistentModes.clear()
        service = null
    }

    fun setDoubleTapWakeEnabled(enabled: Boolean) {
        setPersistentMode(STOCK_DOUBLE_TAP_MODE, enabled, "double-tap wake")
    }

    fun setStylusQuickNoteEnabled(enabled: Boolean) {
        setPersistentMode(STOCK_STYLUS_QUICK_NOTE_MODE, enabled, "stylus quick note")
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val type = XiaomiStylusDevice.typeOf(inputManager?.getInputDevice(deviceId))
        if (type == TYPE_NONE) return
        connectedTypes[deviceId] = type
        if (!sendConnectionEvent(type, connected = true)) scheduleReplay()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val type = connectedTypes.remove(deviceId) ?: return
        if (!sendConnectionEvent(type, connected = false)) scheduleReplay()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val oldType = connectedTypes[deviceId] ?: TYPE_NONE
        val newType = XiaomiStylusDevice.typeOf(inputManager?.getInputDevice(deviceId))
        if (oldType == newType) return

        if (oldType != TYPE_NONE) connectedTypes.remove(deviceId)
        if (newType != TYPE_NONE) connectedTypes[deviceId] = newType
        // A changed device can require two deltas. Use reset/replay so a failure
        // between them cannot leave the kernel count or legacy shield stale.
        resetAndReplay(replayPersistentModes = false)
    }

    private fun refreshConnectedDevices() {
        connectedTypes.clear()
        inputManager?.inputDeviceIds?.forEach { deviceId ->
            val type = XiaomiStylusDevice.typeOf(inputManager.getInputDevice(deviceId))
            if (type != TYPE_NONE) connectedTypes[deviceId] = type
        }
    }

    private fun resetAndReplay(replayPersistentModes: Boolean) {
        if (!started) return
        replayScheduled = false
        handler.removeCallbacks(replayRunnable)

        val touchFeature = getService() ?: run {
            scheduleReplay()
            return
        }

        try {
            if (replayPersistentModes) {
                for ((mode, enabled) in persistentModes) {
                    val result = touchFeature.setModeValue(
                        TOUCH_ID_PRIMARY,
                        mode,
                        if (enabled) 1 else 0,
                    )
                    if (result < 0) {
                        Log.w(TAG, "${persistentModeName(mode)} replay failed: $result")
                        invalidateAndRetry()
                        return
                    }
                }
            }

            val resetResult = touchFeature.setModeValue(
                TOUCH_ID_PRIMARY,
                STOCK_STYLUS_MODE,
                STOCK_STYLUS_RESET,
            )
            if (resetResult < 0) {
                Log.w(TAG, "Stylus state reset failed: $resetResult")
                invalidateAndRetry()
                return
            }

            for (type in connectedTypes.values) {
                val result = touchFeature.setModeValue(
                    TOUCH_ID_PRIMARY,
                    STOCK_STYLUS_MODE,
                    type or STOCK_STYLUS_CONNECTED,
                )
                if (result < 0 && result != UNSUPPORTED) {
                    Log.w(TAG, "Stylus type $type replay failed: $result")
                    invalidateAndRetry()
                    return
                }
                if (result == UNSUPPORTED) {
                    // Keep this branch for a backend which rejects a framework
                    // generation instead of applying liuqin's stock clamp.
                    Log.i(TAG, "Stylus type $type is not supported by this touch module")
                }
            }
        } catch (exception: RemoteException) {
            Log.w(TAG, "TouchFeature died during stylus replay", exception)
            invalidateAndRetry()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not replay stylus state", exception)
            invalidateAndRetry()
        }
    }

    private fun setPersistentMode(mode: Int, enabled: Boolean, name: String) {
        if (persistentModes[mode] == enabled) return
        persistentModes[mode] = enabled
        if (!started) return

        val touchFeature = getService() ?: run {
            scheduleReplay()
            return
        }

        try {
            val result = touchFeature.setModeValue(
                TOUCH_ID_PRIMARY,
                mode,
                if (enabled) 1 else 0,
            )
            if (result < 0) {
                Log.w(TAG, "$name update failed: $result")
                invalidateAndRetry()
            } else {
                Log.i(TAG, "$name=${if (enabled) 1 else 0}")
            }
        } catch (exception: RemoteException) {
            Log.w(TAG, "TouchFeature died while updating $name", exception)
            invalidateAndRetry()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not update $name", exception)
            invalidateAndRetry()
        }
    }

    private fun persistentModeName(mode: Int): String =
        when (mode) {
            STOCK_DOUBLE_TAP_MODE -> "Double-tap wake"
            STOCK_STYLUS_QUICK_NOTE_MODE -> "Stylus quick note"
            else -> "Persistent mode $mode"
        }

    private fun sendConnectionEvent(type: Int, connected: Boolean): Boolean {
        val touchFeature = getService() ?: return false
        val value = type or if (connected) STOCK_STYLUS_CONNECTED else 0
        return try {
            val result = touchFeature.setModeValue(
                TOUCH_ID_PRIMARY,
                STOCK_STYLUS_MODE,
                value,
            )
            when {
                result >= 0 -> true
                result == UNSUPPORTED -> {
                    Log.i(TAG, "Stylus type $type is not supported by this touch module")
                    true
                }
                else -> {
                    Log.w(TAG, "Stylus event 0x${value.toString(16)} failed: $result")
                    false
                }
            }
        } catch (exception: RemoteException) {
            Log.w(TAG, "TouchFeature died while sending stylus state", exception)
            service = null
            false
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not send stylus state", exception)
            service = null
            false
        }
    }

    private fun getService(): ITouchFeature? {
        service?.let { return it }
        return try {
            ITouchFeature.getService().also { service = it }
        } catch (exception: Exception) {
            Log.w(TAG, "TouchFeature service is not ready", exception)
            null
        }
    }

    private fun invalidateAndRetry() {
        service = null
        scheduleReplay()
    }

    private fun scheduleReplay() {
        if (!started || replayScheduled) return
        replayScheduled = true
        handler.postDelayed(replayRunnable, REPLAY_DELAY_MS)
    }

    private val replayRunnable = Runnable {
        refreshConnectedDevices()
        resetAndReplay(replayPersistentModes = true)
    }

    companion object {
        private const val TAG = "LiuqinParts.TouchFeature"
        private const val TYPE_NONE = 0
        private const val TOUCH_ID_PRIMARY = 0
        private const val STOCK_DOUBLE_TAP_MODE = 14
        private const val STOCK_STYLUS_MODE = 20
        private const val STOCK_STYLUS_QUICK_NOTE_MODE = 24
        private const val STOCK_STYLUS_RESET = -1
        private const val STOCK_STYLUS_CONNECTED = 0x10
        private const val UNSUPPORTED = -95 // -EOPNOTSUPP
        private const val REPLAY_DELAY_MS = 2_000L
    }
}
