/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.os.UEventObserver
import android.util.Log

/**
 * Observes the pen fields emitted by liuqin's qti_battery_charger module.
 *
 * These are the fields consumed by BluetoothExtension in the liuqin OS3.0.7.0
 * image. Stock also observes proprietary MIPP/frequency events, but whether a
 * liuqin VID-6421 pen exposes the required FE10/FE11 GATT service cannot be
 * established without a hardware service-table capture. Those events are
 * intentionally omitted instead of issuing unverified proprietary GATT writes.
 */
internal class PenUEventObserver(
    private val listener: Listener,
) : UEventObserver(), AutoCloseable {
    interface Listener {
        fun onPenMac(rawMac: String)

        fun onChargeStateChanged(state: Int)

        fun onChargerBatteryChanged(level: Int)

        fun onDocked()

        fun onUndocked()
    }

    private val stateLock = Any()

    @Volatile
    private var started = false

    private var lastChargeState: Int? = null
    private var lastChargerBattery: Int? = null
    private var lastAttached: Boolean? = null

    fun start() {
        synchronized(stateLock) {
            if (started) return
            started = true
        }

        UEVENT_KEYS.forEach(::startObserving)
    }

    override fun onUEvent(event: UEvent) {
        if (!started) return

        val callbacks = mutableListOf<() -> Unit>()
        synchronized(stateLock) {
            if (!started) return

            // Keep the exact BluetoothExtension field order. UEventObserver
            // invokes us once for every matching key in a combined event, so a
            // state-4 event can arm the gate before its repeated MAC callback.
            // Do not deduplicate MAC values across distinct kernel events: a
            // MAC observed before state 4 is deliberately ignored by stock.
            event[KEY_PEN_MAC]?.let { rawMac ->
                callbacks += { listener.onPenMac(rawMac) }
            }

            event[KEY_CHARGE_STATE]?.toIntOrNull()?.let { state ->
                if (state != lastChargeState) {
                    lastChargeState = state
                    callbacks += { listener.onChargeStateChanged(state) }
                }
            }

            event[KEY_CHARGER_BATTERY]?.toIntOrNull()?.let { level ->
                if (level in BATTERY_RANGE && level != lastChargerBattery) {
                    lastChargerBattery = level
                    callbacks += { listener.onChargerBatteryChanged(level) }
                }
            }

            // BluetoothExtension only evaluates Hall state when both fields
            // are present in the same qcom-battery uevent. Do not combine one
            // new value with a stale value retained from an older event.
            val currentHall3 = event[KEY_HALL3]?.toIntOrNull()?.takeIf(::isHallValue)
            val currentHall4 = event[KEY_HALL4]?.toIntOrNull()?.takeIf(::isHallValue)
            if (currentHall3 != null && currentHall4 != null) {
                // Exact OS3 semantics: either Hall sensor low means attached;
                // only two high signals mean detached.
                val attached = currentHall3 == 0 || currentHall4 == 0
                val previous = lastAttached
                if (previous == null) {
                    // The first complete Hall sample is a baseline. Treating it
                    // as an edge would show a stale dock-status toast after boot.
                    lastAttached = attached
                } else if (attached != previous) {
                    lastAttached = attached
                    if (attached) {
                        callbacks.add { listener.onDocked() }
                    } else {
                        callbacks.add { listener.onUndocked() }
                    }
                }
            }
        }

        // Never invoke application code while holding the observer state lock.
        callbacks.forEach { callback ->
            try {
                callback()
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Pen UEvent callback failed", exception)
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (!started) return
            started = false
        }
        stopObserving()
    }

    private fun isHallValue(value: Int) = value == 0 || value == 1

    private companion object {
        private const val TAG = "LiuqinPenUEvent"

        private const val KEY_PEN_MAC = "POWER_SUPPLY_PEN_MAC"
        private const val KEY_CHARGE_STATE = "POWER_SUPPLY_REVERSE_PEN_CHG_STATE"
        private const val KEY_CHARGER_BATTERY = "POWER_SUPPLY_REVERSE_PEN_SOC"
        private const val KEY_HALL3 = "POWER_SUPPLY_PEN_HALL3"
        private const val KEY_HALL4 = "POWER_SUPPLY_PEN_HALL4"

        private val BATTERY_RANGE = 0..100

        private val UEVENT_KEYS =
            arrayOf(
                KEY_PEN_MAC,
                KEY_CHARGE_STATE,
                KEY_CHARGER_BATTERY,
                KEY_HALL3,
                KEY_HALL4,
            )
    }
}
