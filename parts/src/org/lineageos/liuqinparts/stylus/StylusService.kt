/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import org.lineageos.liuqinparts.R
import org.lineageos.liuqinparts.touch.WakeGestureSettingsController

/**
 * Long-lived host for liuqin's stock-derived stylus and touch integration.
 *
 * Hardware-facing state is serialized on one worker looper. Bluetooth uses a
 * separate worker internally because framework callbacks can otherwise block
 * display/touch state replay. The app never opens /dev/xiaomi-touch directly;
 * mode-20 traffic goes through the exact vendor HIDL compatibility service.
 */
class StylusService : Service(), StylusBluetoothManager.Listener {
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var touchFeatureClient: TouchFeatureClient
    private lateinit var displayController: StylusDisplayController
    private lateinit var bluetoothManager: StylusBluetoothManager
    private lateinit var wakeGestureSettingsController: WakeGestureSettingsController
    private var statusToast: Toast? = null

    private var initialized = false

    override fun onCreate() {
        super.onCreate()
        try {
            workerThread = HandlerThread(WORKER_NAME).also(HandlerThread::start)
            workerHandler = Handler(workerThread.looper)
            touchFeatureClient = TouchFeatureClient(this, workerHandler)
            displayController = StylusDisplayController(this, workerHandler)
            bluetoothManager = StylusBluetoothManager(this, this)
            wakeGestureSettingsController =
                WakeGestureSettingsController(this, workerHandler, touchFeatureClient)
            initialized = true

            workerHandler.post {
                wakeGestureSettingsController.start()
                touchFeatureClient.start()
                displayController.start()
            }
            bluetoothManager.start()
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not initialize the stylus helper", exception)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDockStatusChanged(
        state: StylusDockConnectionState,
        battery: Int?,
    ) {
        val message =
            when (state) {
                StylusDockConnectionState.CONNECTING ->
                    getString(R.string.stylus_connecting)
                StylusDockConnectionState.PAIRING ->
                    getString(R.string.stylus_pairing)
                StylusDockConnectionState.CONNECTED ->
                    if (battery != null) {
                        getString(R.string.stylus_battery_level, battery)
                    } else {
                        getString(R.string.stylus_connected)
                    }
                StylusDockConnectionState.CONNECTION_FAILED ->
                    getString(R.string.stylus_connection_failed)
                StylusDockConnectionState.BLUETOOTH_OFF ->
                    getString(R.string.stylus_bluetooth_off)
            }
        statusToast?.cancel()
        statusToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    override fun onUndocked() {
        statusToast?.cancel()
        statusToast = null
    }

    private fun clearStatusToast() {
        statusToast?.cancel()
        statusToast = null
    }

    override fun onDestroy() {
        clearStatusToast()
        if (initialized) {
            bluetoothManager.close()
            workerHandler.post {
                wakeGestureSettingsController.stop()
                touchFeatureClient.stop()
                displayController.stop()
                workerThread.quitSafely()
            }
            initialized = false
        } else if (::workerThread.isInitialized) {
            workerThread.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiuqinParts.Service"
        private const val WORKER_NAME = "LiuqinStylus"
    }
}
