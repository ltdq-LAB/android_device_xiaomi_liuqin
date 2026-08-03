/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.UEventObserver
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display
import java.io.File
import kotlin.math.abs

/**
 * Approximates the liuqin OS3 smart-pen display policy without persisting a
 * user preference.
 *
 * Stock liuqin uses the ordered list [120, 60] and selects the first entry no
 * higher than the normal refresh-rate policy cap. It therefore does not always
 * force 120 Hz: a 90 Hz cap becomes 60 Hz, for example. Lineage does not carry
 * Xiaomi's SurfaceFlinger hook, so the helper uses Android's hidden, explicitly
 * non-persisting user-preferred-mode API while the kernel's exact
 * pen_connect_strategy state is non-zero. This cannot reproduce Xiaomi's
 * private SurfaceFlinger mode-group and per-layer ranking inputs.
 */
class StylusDisplayController(
    context: Context,
    private val handler: Handler,
) : DisplayManager.DisplayListener {
    private val contentResolver: ContentResolver = context.contentResolver
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    private var started = false
    private var penActive = false
    private var appliedModeId = INVALID_MODE_ID
    private var initialReadAttempts = 0

    private val penObserver = object : UEventObserver() {
        override fun onUEvent(event: UEvent) {
            handler.post(::refreshPenState)
        }
    }

    private val refreshRateObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (penActive) applyStockPenMode()
        }
    }

    fun start() {
        if (started) return
        started = true
        displayManager?.registerDisplayListener(this, handler)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE),
            false,
            refreshRateObserver,
            UserHandle.USER_ALL,
        )
        penObserver.startObserving(TOUCH_UEVENT_MATCH)
        refreshPenState()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(initialReadRunnable)
        penObserver.stopObserving()
        contentResolver.unregisterContentObserver(refreshRateObserver)
        displayManager?.unregisterDisplayListener(this)
        if (penActive || appliedModeId != INVALID_MODE_ID) restoreNormalPolicy()
        penActive = false
    }

    override fun onDisplayAdded(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY && penActive) applyStockPenMode()
    }

    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY && penActive) applyStockPenMode()
    }

    private fun refreshPenState() {
        if (!started) return
        val value = readInteger(PEN_CONNECT_STRATEGY_PATH)
        if (value == null) {
            if (initialReadAttempts++ < INITIAL_READ_RETRIES) {
                handler.removeCallbacks(initialReadRunnable)
                handler.postDelayed(initialReadRunnable, INITIAL_READ_RETRY_MS)
            }
            return
        }

        initialReadAttempts = 0
        val active = value != 0
        if (active == penActive) {
            if (active && appliedModeId == INVALID_MODE_ID) applyStockPenMode()
            return
        }

        penActive = active
        if (active) applyStockPenMode() else restoreNormalPolicy()
    }

    private fun applyStockPenMode() {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val targetMode = selectStockPenMode(display) ?: run {
            Log.w(TAG, "No stock smart-pen display mode is available")
            return
        }
        if (targetMode.modeId == appliedModeId) return

        try {
            // storeMode=false is enabled as a fixed read-only Android 16 flag in
            // Lineage 23.2. It changes the active policy vote without modifying
            // Settings or the display persistent-data store.
            display.setUserPreferredDisplayMode(targetMode, false)
            appliedModeId = targetMode.modeId
            Log.i(TAG, "Smart-pen display mode=${targetMode.refreshRate}Hz")
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not apply smart-pen display mode", exception)
        }
    }

    private fun selectStockPenMode(display: Display): Display.Mode? {
        val currentMode = display.mode
        val configuredCap = Settings.System.getFloatForUser(
            contentResolver,
            Settings.System.PEAK_REFRESH_RATE,
            Float.NaN,
            UserHandle.USER_CURRENT,
        )
        val cap = configuredCap.takeIf { it.isFinite() && it > 0f }
            ?: display.userPreferredDisplayMode?.refreshRate
            ?: currentMode.refreshRate

        val supported = display.supportedModes.filter { mode ->
            mode.physicalWidth == currentMode.physicalWidth &&
                mode.physicalHeight == currentMode.physicalHeight
        }
        val targetRate = STOCK_SMARTPEN_RATES.firstOrNull { it <= cap + FPS_TOLERANCE }
            ?: STOCK_SMARTPEN_RATES.last()

        return supported.firstOrNull { mode ->
            abs(mode.refreshRate - targetRate) <= FPS_TOLERANCE
        }
    }

    private fun restoreNormalPolicy() {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        try {
            display?.resetUserPreferredDisplayMode()
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not restore the normal display policy", exception)
        } finally {
            appliedModeId = INVALID_MODE_ID
        }
    }

    private fun readInteger(path: String): Int? = try {
        File(path).readText().trim().toIntOrNull()
    } catch (exception: Exception) {
        Log.w(TAG, "Could not read $path", exception)
        null
    }

    private val initialReadRunnable = Runnable(::refreshPenState)

    companion object {
        private const val TAG = "LiuqinParts.Display"
        private const val PEN_CONNECT_STRATEGY_PATH =
            "/sys/class/touch/touch_dev/pen_connect_strategy"
        private const val TOUCH_UEVENT_MATCH =
            "DEVPATH=/devices/virtual/touch/touch_dev"
        private const val INVALID_MODE_ID = -1
        private const val FPS_TOLERANCE = 0.01f
        private const val INITIAL_READ_RETRIES = 30
        private const val INITIAL_READ_RETRY_MS = 1_000L
        private val STOCK_SMARTPEN_RATES = floatArrayOf(120f, 60f)
    }
}
