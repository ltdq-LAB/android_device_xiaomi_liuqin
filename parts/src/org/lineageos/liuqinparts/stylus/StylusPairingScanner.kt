/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.util.Log

/** Performs the address-filtered pre-bond scan used by liuqin's stock helper. */
internal class StylusPairingScanner(
    private val adapter: BluetoothAdapter,
    private val handler: Handler,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onPenFound(address: String)

        fun onScanFailed()
    }

    private var expectedAddress: String? = null
    private var scanning = false

    private val timeout = Runnable(::handleTimeout)

    private val callback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                handler.post { handleResult(address) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                // Keep all mutable scan state on the manager's worker looper.
                results.forEach { result ->
                    val address = result.device.address
                    handler.post { handleResult(address) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Stylus BLE scan failed: $errorCode")
                handler.post(::handleFailure)
            }
        }

    fun start(address: String): Boolean {
        stop()

        val scanner = adapter.bluetoothLeScanner ?: return false
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

        return try {
            adapter.cancelDiscovery()
            expectedAddress = address
            scanning = true
            scanner.startScan(filters, settings, callback)
            handler.postDelayed(timeout, SCAN_TIMEOUT_MS)
            true
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not start the stylus BLE scan", exception)
            expectedAddress = null
            scanning = false
            false
        }
    }

    fun stop() {
        handler.removeCallbacks(timeout)
        expectedAddress = null
        if (!scanning) return
        scanning = false
        try {
            adapter.bluetoothLeScanner?.stopScan(callback)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not stop the stylus BLE scan", exception)
        }
    }

    override fun close() = stop()

    private fun handleResult(address: String) {
        val expected = expectedAddress ?: return
        if (!scanning || !address.equals(expected, ignoreCase = true)) return
        stop()
        listener.onPenFound(address)
    }

    private fun handleTimeout() {
        if (!scanning) return
        stop()
        listener.onScanFailed()
    }

    private fun handleFailure() {
        if (!scanning) return
        stop()
        listener.onScanFailed()
    }

    private companion object {
        private const val TAG = "LiuqinStylusScanner"
        private const val SCAN_TIMEOUT_MS = 20_000L
    }
}
