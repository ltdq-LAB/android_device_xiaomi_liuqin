/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.UUID

/**
 * liuqin-specific Smart Pen pairing, HID connection and battery handling.
 *
 * The protocol intentionally follows BluetoothExtension from the liuqin
 * OS3.0.7.0 image. Its standard pairing/HID/battery path is reproduced here.
 * The stock app also contains conditional FE10/FE11 proprietary writes, but no
 * offline evidence proves that liuqin's VID-6421 pens expose that GATT service;
 * those writes are not guessed without a service-table capture.
 */
enum class StylusDockConnectionState {
    CONNECTING,
    PAIRING,
    CONNECTED,
    CONNECTION_FAILED,
    BLUETOOTH_OFF,
}

internal class StylusBluetoothManager(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onDockStatusChanged(state: StylusDockConnectionState, battery: Int?)

        fun onUndocked()
    }

    private data class BatterySample(
        val level: Int,
        val timestamp: Long = SystemClock.elapsedRealtime(),
    )

    private data class DockPresentation(
        val state: StylusDockConnectionState,
        val battery: Int?,
    )

    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? =
        this.context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mainHandler = Handler(context.mainLooper)
    private val workerThread = HandlerThread(WORKER_NAME)
    private val uEventObserver =
        PenUEventObserver(
            object : PenUEventObserver.Listener {
                override fun onPenMac(rawMac: String) = post { handlePenMac(rawMac) }

                override fun onChargeStateChanged(state: Int) =
                    post { handleChargeState(state) }

                override fun onChargerBatteryChanged(level: Int) =
                    post { handleChargerBattery(level) }

                override fun onDocked() = post(::handleDocked)

                override fun onUndocked() = post(::handleUndocked)
            },
        )

    @Volatile
    private var running = false

    private var closed = false
    private lateinit var workerHandler: Handler
    private var receiverRegistered = false
    private var profileRequested = false
    private var hidHost: BluetoothProfile? = null
    private var gatt: BluetoothGatt? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var firmwareRevisionCharacteristic: BluetoothGattCharacteristic? = null
    private var pnpIdCharacteristic: BluetoothGattCharacteristic? = null
    private var pairingScanner: StylusPairingScanner? = null

    private var chargeState: Int? = null
    private var macGateArmed = false
    private var currentAddress: String? = null
    private var chargerBattery: BatterySample? = null
    private var gattBattery: BatterySample? = null
    private var deviceBattery: BatterySample? = null
    private var lastRemovedAddress: String? = null
    private var lastRemoveTimeMillis = 0L
    private var docked = false
    private var lastDockPresentation: DockPresentation? = null
    private var deviceInformation = StylusDeviceInformation()

    private val connectionTimeout =
        Runnable {
            try {
                if (running && docked && !isCurrentPenConnected()) {
                    publishDockStatus(StylusDockConnectionState.CONNECTION_FAILED)
                }
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Could not resolve timed-out stylus connection", exception)
                if (running && docked) {
                    publishDockStatus(StylusDockConnectionState.CONNECTION_FAILED)
                }
            }
        }

    private val profileListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_HOST) return
                if (!running) {
                    try {
                        adapter?.closeProfileProxy(BluetoothProfile.HID_HOST, proxy)
                    } catch (exception: RuntimeException) {
                        Log.w(TAG, "Could not close a late HID profile proxy", exception)
                    }
                    return
                }
                post { handleProfileConnected(proxy) }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_HOST) post(::handleProfileDisconnected)
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state =
                            intent.getIntExtra(
                                BluetoothAdapter.EXTRA_STATE,
                                BluetoothAdapter.ERROR,
                            )
                        post { handleAdapterState(state) }
                    }

                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = intent.bluetoothDeviceExtra() ?: return
                        val state =
                            intent.getIntExtra(
                                BluetoothDevice.EXTRA_BOND_STATE,
                                BluetoothDevice.ERROR,
                            )
                        post { handleBondState(device, state) }
                    }

                    ACTION_HID_CONNECTION_STATE_CHANGED -> {
                        val device = intent.bluetoothDeviceExtra() ?: return
                        val state =
                            intent.getIntExtra(
                                BluetoothProfile.EXTRA_STATE,
                                BluetoothProfile.STATE_DISCONNECTED,
                            )
                        post { handleHidState(device, state) }
                    }

                    BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED -> {
                        val device = intent.bluetoothDeviceExtra() ?: return
                        val level =
                            intent.getIntExtra(
                                BluetoothDevice.EXTRA_BATTERY_LEVEL,
                                BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                            )
                        post { handleDeviceBattery(device, level) }
                    }
                }
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                bluetoothGatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                post { handleGattState(bluetoothGatt, status, newState) }
            }

            override fun onServicesDiscovered(bluetoothGatt: BluetoothGatt, status: Int) {
                post { handleServicesDiscovered(bluetoothGatt, status) }
            }

            override fun onCharacteristicRead(
                bluetoothGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                post {
                    handleCharacteristicRead(
                        bluetoothGatt,
                        characteristic,
                        value,
                        status,
                    )
                }
            }

            override fun onCharacteristicChanged(
                bluetoothGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == BATTERY_UUID) {
                    post { cacheGattBattery(bluetoothGatt, value) }
                }
            }

            override fun onDescriptorWrite(
                bluetoothGatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    post { readBatteryCharacteristic(bluetoothGatt) }
                }
            }
        }

    @Synchronized
    fun start() {
        if (running) return
        check(!closed) { "StylusBluetoothManager cannot be restarted after close()" }

        workerThread.start()
        workerHandler = Handler(workerThread.looper)
        pairingScanner =
            adapter?.let { bluetoothAdapter ->
                StylusPairingScanner(
                    bluetoothAdapter,
                    workerHandler,
                    object : StylusPairingScanner.Listener {
                        override fun onPenFound(address: String) {
                            handleScannedPen(address)
                        }

                        override fun onScanFailed() {
                            if (docked) {
                                publishDockStatus(
                                    StylusDockConnectionState.CONNECTION_FAILED,
                                )
                            }
                        }
                    },
                )
            }
        running = true

        val filter =
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(ACTION_HID_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        uEventObserver.start()

        post {
            if (adapter?.isEnabled == true) {
                requestHidProxy()
                retryStoredAddress(allowPairing = false)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        running = false

        uEventObserver.close()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Bluetooth receiver was already unregistered", exception)
            }
            receiverRegistered = false
        }

        if (::workerHandler.isInitialized) {
            workerHandler.removeCallbacksAndMessages(null)
            workerHandler.post {
                try {
                    pairingScanner?.close()
                    pairingScanner = null
                    closeGatt()
                } catch (exception: RuntimeException) {
                    Log.w(TAG, "Could not close pen GATT during shutdown", exception)
                }
                try {
                    closeHidProxy()
                } catch (exception: RuntimeException) {
                    Log.w(TAG, "Could not close HID proxy during shutdown", exception)
                } finally {
                    workerThread.quitSafely()
                }
            }
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun post(block: () -> Unit) {
        if (!running || !::workerHandler.isInitialized) return
        workerHandler.post {
            if (running) {
                try {
                    block()
                } catch (exception: RuntimeException) {
                    // A framework/Binder failure must not kill the only looper
                    // which owns pairing and GATT state. Later broadcasts and
                    // uevents can then retry or repair the operation.
                    Log.e(TAG, "Stylus Bluetooth operation failed", exception)
                }
            }
        }
    }

    private fun handlePenMac(rawMac: String) {
        val address = normalizeAddress(rawMac)
        if (address == null) {
            Log.w(TAG, "Ignoring malformed 12-hex-digit pen MAC")
            return
        }

        // OS3 discards PEN_MAC unless reverse charging was already in state 4.
        // In particular, a later state-4 event must not revive a stale MAC.
        if (!macGateArmed || adapter == null) return
        // Stock consumes this one-shot gate for every valid MAC attempt,
        // including the Bluetooth-off caching path.
        macGateArmed = false

        if (address != currentAddress) {
            pairingScanner?.stop()
            currentAddress = address
            chargerBattery = null
            gattBattery = null
            deviceBattery = null
            closeGatt()
        }

        removePreviouslyBondedPens(address)
        Settings.Secure.putString(context.contentResolver, STORED_PEN_ADDRESS, address)
        connectOrPair(address, allowPairing = true)
    }

    private fun handleChargeState(state: Int) {
        if (state != chargeState) {
            macGateArmed = state == CHARGE_STATE_PAIRING
        }
        chargeState = state
    }

    private fun handleChargerBattery(level: Int) {
        // The original extension only accepts charger SOC while state 4 is active.
        if (chargeState == CHARGE_STATE_PAIRING && level in BATTERY_RANGE) {
            chargerBattery = BatterySample(level)
            publishConnectedBatteryIfAvailable()
        }
    }

    private fun handleDocked() {
        docked = true
        lastDockPresentation = null

        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            publishDockStatus(StylusDockConnectionState.BLUETOOTH_OFF)
            return
        }

        val device = currentPenDevice()
        if (device != null && hidConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            refreshDeviceBattery(device)
            publishDockStatus(StylusDockConnectionState.CONNECTED, latestBattery())
            openBatteryGatt(device)
            return
        }

        val state =
            if (device?.bondState == BluetoothDevice.BOND_BONDING) {
                StylusDockConnectionState.PAIRING
            } else {
                StylusDockConnectionState.CONNECTING
            }
        publishPendingDockStatus(state)
    }

    private fun handleUndocked() {
        docked = false
        lastDockPresentation = null
        workerHandler.removeCallbacks(connectionTimeout)
        mainHandler.post {
            if (running) {
                try {
                    listener.onUndocked()
                } catch (exception: RuntimeException) {
                    Log.e(TAG, "Could not deliver stylus undock state", exception)
                }
            }
        }
    }

    private fun handleAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                requestHidProxy()
                if (docked) publishPendingDockStatus(StylusDockConnectionState.CONNECTING)
                // Stock restarts the address-filtered scan when Bluetooth is
                // enabled while the physical reverse-charge state is still 4.
                retryStoredAddress(allowPairing = chargeState == CHARGE_STATE_PAIRING)
            }

            BluetoothAdapter.STATE_TURNING_OFF -> {
                pairingScanner?.stop()
                closeGatt()
                if (docked) publishDockStatus(StylusDockConnectionState.BLUETOOTH_OFF)
            }

            BluetoothAdapter.STATE_OFF -> {
                pairingScanner?.stop()
                closeGatt()
                closeHidProxy()
                if (docked) publishDockStatus(StylusDockConnectionState.BLUETOOTH_OFF)
            }
        }
    }

    private fun handleBondState(device: BluetoothDevice, state: Int) {
        if (!isCurrentPen(device)) return
        when (state) {
            BluetoothDevice.BOND_BONDING -> {
                pairingScanner?.stop()
                if (docked) publishPendingDockStatus(StylusDockConnectionState.PAIRING)
            }

            BluetoothDevice.BOND_BONDED -> {
                pairingScanner?.stop()
                Settings.Secure.putString(
                    context.contentResolver,
                    STORED_PEN_ADDRESS,
                    device.address,
                )
                if (docked) publishPendingDockStatus(StylusDockConnectionState.CONNECTING)
                connectHid(device)
            }

            BluetoothDevice.BOND_NONE -> {
                if (docked) publishDockStatus(StylusDockConnectionState.CONNECTION_FAILED)
            }
        }
    }

    private fun handleHidState(device: BluetoothDevice, state: Int) {
        if (!isCurrentPen(device)) return
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                refreshDeviceBattery(device)
                publishDockStatus(StylusDockConnectionState.CONNECTED, latestBattery())
                openBatteryGatt(device)
            }

            BluetoothProfile.STATE_CONNECTING -> {
                if (docked) publishPendingDockStatus(StylusDockConnectionState.CONNECTING)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                if (gatt?.device?.address == device.address) closeGatt()
                publishDisconnectedDockStatus()
            }
        }
    }

    private fun handleDeviceBattery(device: BluetoothDevice, level: Int) {
        if (isCurrentPen(device) && level in BATTERY_RANGE) {
            deviceBattery = BatterySample(level)
            publishConnectedBatteryIfAvailable()
        }
    }

    private fun handleProfileConnected(proxy: BluetoothProfile) {
        val oldProxy = hidHost?.takeIf { it !== proxy }
        hidHost = proxy
        profileRequested = false
        if (oldProxy != null) {
            try {
                adapter?.closeProfileProxy(BluetoothProfile.HID_HOST, oldProxy)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not close replaced HID profile proxy", exception)
            }
        }
        retryStoredAddress(allowPairing = false)
    }

    private fun handleProfileDisconnected() {
        hidHost = null
        profileRequested = false
        publishDisconnectedDockStatus()
    }

    private fun requestHidProxy() {
        val bluetoothAdapter = adapter ?: return
        if (!bluetoothAdapter.isEnabled || hidHost != null || profileRequested) return
        profileRequested =
            bluetoothAdapter.getProfileProxy(
                context,
                profileListener,
                BluetoothProfile.HID_HOST,
            )
    }

    private fun closeHidProxy() {
        val proxy = hidHost
        hidHost = null
        profileRequested = false
        if (proxy != null) {
            try {
                adapter?.closeProfileProxy(BluetoothProfile.HID_HOST, proxy)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not close HID profile proxy", exception)
            }
        }
    }

    private fun retryStoredAddress(allowPairing: Boolean) {
        val address = currentAddress ?: readStoredAddress() ?: return
        currentAddress = address
        connectOrPair(address, allowPairing)
    }

    /**
     * OS3 keeps only the pen announced by the state-4 charging handshake.
     *
     * Its candidate check deliberately uses String.contains() against one
     * comma-separated name list rather than exact set membership. Preserve
     * that quirk, as well as its per-address three-second retry suppression.
     * A failed removeBond() never blocks pairing the newly announced pen.
     */
    private fun removePreviouslyBondedPens(newAddress: String) {
        val bluetoothAdapter = adapter ?: return
        if (!bluetoothAdapter.isEnabled) return

        val bondedDevices = try {
            bluetoothAdapter.bondedDevices
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not enumerate previously bonded pens", exception)
            return
        }

        for (device in bondedDevices) {
            val name = device.name ?: continue
            if (!STOCK_PEN_NAMES.contains(name) ||
                device.address.equals(newAddress, ignoreCase = true)
            ) {
                continue
            }

            val now = System.currentTimeMillis()
            val elapsed = now - lastRemoveTimeMillis
            if (device.address.equals(lastRemovedAddress, ignoreCase = true) &&
                elapsed in 1 until OLD_PEN_REMOVE_RETRY_MS
            ) {
                continue
            }

            try {
                val removed = device.removeBond()
                Log.i(TAG, "Old pen ${device.address} removeBond=$removed")
                // Stock records a normally returned attempt even when the
                // Boolean result is false, then proceeds with the new pen.
                lastRemovedAddress = device.address
                lastRemoveTimeMillis = now
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not remove old pen ${device.address}", exception)
            }
        }
    }

    private fun connectOrPair(address: String, allowPairing: Boolean) {
        val bluetoothAdapter = adapter ?: return
        if (!bluetoothAdapter.isEnabled) return // Never turn Bluetooth on for the pen.

        val device =
            try {
                bluetoothAdapter.getRemoteDevice(address)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Stored pen address is invalid", exception)
                return
            }

        refreshDeviceBattery(device)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> connectHid(device)
            BluetoothDevice.BOND_BONDING -> {
                if (docked) publishPendingDockStatus(StylusDockConnectionState.PAIRING)
            }

            BluetoothDevice.BOND_NONE -> {
                // A new bond is only initiated by the OS3 state-4 charging path.
                if (allowPairing && chargeState == CHARGE_STATE_PAIRING) {
                    val started = pairingScanner?.start(address) == true
                    if (started) {
                        if (docked) {
                            publishPendingDockStatus(StylusDockConnectionState.CONNECTING)
                        }
                    } else if (docked) {
                        publishDockStatus(StylusDockConnectionState.CONNECTION_FAILED)
                    }
                }
            }
        }
    }

    private fun handleScannedPen(address: String) {
        if (!address.equals(currentAddress, ignoreCase = true)) return
        val bluetoothAdapter = adapter ?: return
        if (!bluetoothAdapter.isEnabled) return

        val device =
            try {
                bluetoothAdapter.getRemoteDevice(address)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Scanned pen address is invalid", exception)
                if (docked) publishDockStatus(StylusDockConnectionState.CONNECTION_FAILED)
                return
            }

        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> connectHid(device)
            BluetoothDevice.BOND_BONDING -> {
                if (docked) publishPendingDockStatus(StylusDockConnectionState.PAIRING)
            }
            BluetoothDevice.BOND_NONE -> {
                val started = device.createBond(BluetoothDevice.TRANSPORT_LE)
                if (started) {
                    if (docked) publishPendingDockStatus(StylusDockConnectionState.PAIRING)
                } else {
                    Log.w(TAG, "LE pen bonding did not start after scan")
                    if (docked) {
                        publishDockStatus(StylusDockConnectionState.CONNECTION_FAILED)
                    }
                }
            }
        }
    }

    private fun connectHid(device: BluetoothDevice) {
        val proxy = hidHost
        if (proxy == null) {
            requestHidProxy()
            if (docked) publishPendingDockStatus(StylusDockConnectionState.CONNECTING)
            return
        }

        when (proxy.getConnectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> {
                refreshDeviceBattery(device)
                publishDockStatus(StylusDockConnectionState.CONNECTED, latestBattery())
                openBatteryGatt(device)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                // BluetoothExtension calls BluetoothDevice.connect() here,
                // using HID_HOST only to gate/check the connection state.
                // This connects every enabled profile, including HID_HOST.
                val result = device.connect()
                if (result != BluetoothStatusCodes.SUCCESS) {
                    Log.w(TAG, "All-profile pen connection did not start: $result")
                    if (docked) {
                        publishDockStatus(StylusDockConnectionState.CONNECTION_FAILED)
                    }
                } else if (docked) {
                    publishPendingDockStatus(StylusDockConnectionState.CONNECTING)
                }
            }

            BluetoothProfile.STATE_CONNECTING -> {
                if (docked) publishPendingDockStatus(StylusDockConnectionState.CONNECTING)
            }
        }
    }

    private fun openBatteryGatt(device: BluetoothDevice) {
        if (gatt?.device?.address == device.address) return
        closeGatt()
        gatt =
            device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
    }

    private fun handleGattState(
        bluetoothGatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        if (bluetoothGatt !== gatt) {
            bluetoothGatt.close()
            return
        }

        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            if (!bluetoothGatt.discoverServices()) {
                Log.w(TAG, "Could not start pen GATT service discovery")
            }
        } else if (
            status != BluetoothGatt.GATT_SUCCESS ||
                newState == BluetoothProfile.STATE_DISCONNECTED
        ) {
            closeGatt()
        }
    }

    private fun handleServicesDiscovered(bluetoothGatt: BluetoothGatt, status: Int) {
        if (bluetoothGatt !== gatt || status != BluetoothGatt.GATT_SUCCESS) return

        val characteristic =
            bluetoothGatt
                .getService(BATTERY_SERVICE_UUID)
                ?.getCharacteristic(BATTERY_UUID)
        val deviceInformationService = bluetoothGatt.getService(DEVICE_INFORMATION_SERVICE_UUID)
        firmwareRevisionCharacteristic =
            deviceInformationService?.getCharacteristic(FIRMWARE_REVISION_UUID)
        pnpIdCharacteristic = deviceInformationService?.getCharacteristic(PNP_ID_UUID)

        if (characteristic == null) {
            Log.w(TAG, "Connected pen has no standard Battery Service characteristic")
            readFirmwareRevision(bluetoothGatt)
            return
        }

        batteryCharacteristic = characteristic
        val supportsNotifications =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (
            supportsNotifications &&
                descriptor != null &&
                bluetoothGatt.setCharacteristicNotification(characteristic, true)
        ) {
            val result =
                bluetoothGatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                )
            if (result == BluetoothStatusCodes.SUCCESS) return
            Log.w(TAG, "Could not enable pen battery notifications: $result")
        }

        readBatteryCharacteristic(bluetoothGatt)
    }

    private fun readBatteryCharacteristic(bluetoothGatt: BluetoothGatt) {
        if (bluetoothGatt !== gatt) return
        val characteristic = batteryCharacteristic ?: return
        if (!bluetoothGatt.readCharacteristic(characteristic)) {
            Log.w(TAG, "Could not read pen battery characteristic")
            readFirmwareRevision(bluetoothGatt)
        }
    }

    private fun handleCharacteristicRead(
        bluetoothGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (bluetoothGatt !== gatt) return

        when (characteristic.uuid) {
            BATTERY_UUID -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cacheGattBattery(bluetoothGatt, value)
                }
                readFirmwareRevision(bluetoothGatt)
            }

            FIRMWARE_REVISION_UUID -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deviceInformation = deviceInformation.withFirmwareRevision(value)
                    Log.i(TAG, "Pen firmware revision: ${deviceInformation.firmwareRevision}")
                }
                readPnpId(bluetoothGatt)
            }

            PNP_ID_UUID -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deviceInformation = deviceInformation.withPnpId(value)
                    Log.i(
                        TAG,
                        "Pen PNP: vid=${deviceInformation.vendorId} " +
                            "pid=${deviceInformation.productId} " +
                            "version=${deviceInformation.productVersion}",
                    )
                }
            }
        }
    }

    private fun readFirmwareRevision(bluetoothGatt: BluetoothGatt) {
        if (bluetoothGatt !== gatt) return
        val characteristic = firmwareRevisionCharacteristic
        if (characteristic == null || !bluetoothGatt.readCharacteristic(characteristic)) {
            if (characteristic != null) {
                Log.w(TAG, "Could not read pen firmware revision")
            }
            readPnpId(bluetoothGatt)
        }
    }

    private fun readPnpId(bluetoothGatt: BluetoothGatt) {
        if (bluetoothGatt !== gatt) return
        val characteristic = pnpIdCharacteristic ?: return
        if (!bluetoothGatt.readCharacteristic(characteristic)) {
            Log.w(TAG, "Could not read pen PNP ID")
        }
    }

    private fun cacheGattBattery(bluetoothGatt: BluetoothGatt, value: ByteArray) {
        if (bluetoothGatt !== gatt || value.isEmpty()) return
        val level = value[0].toInt() and 0xff
        if (level in BATTERY_RANGE) {
            gattBattery = BatterySample(level)
            publishConnectedBatteryIfAvailable()
        }
    }

    private fun refreshDeviceBattery(device: BluetoothDevice) {
        val level = device.batteryLevel
        // batteryLevel is a framework cache. Reading the same value again does
        // not make that observation newer than an actual charger/GATT update.
        if (level in BATTERY_RANGE && deviceBattery?.level != level) {
            deviceBattery = BatterySample(level)
        }
    }

    private fun latestBattery(): Int? =
        listOfNotNull(chargerBattery, gattBattery, deviceBattery)
            .maxByOrNull(BatterySample::timestamp)
            ?.level

    private fun publishConnectedBatteryIfAvailable() {
        if (!docked || !isCurrentPenConnected()) return
        publishDockStatus(StylusDockConnectionState.CONNECTED, latestBattery())
    }

    private fun publishDisconnectedDockStatus() {
        if (!docked) return
        val state = adapter?.state
        val status =
            if (state == BluetoothAdapter.STATE_OFF ||
                state == BluetoothAdapter.STATE_TURNING_OFF
            ) {
                StylusDockConnectionState.BLUETOOTH_OFF
            } else {
                StylusDockConnectionState.CONNECTION_FAILED
            }
        publishDockStatus(status)
    }

    private fun isCurrentPenConnected(): Boolean {
        val device = currentPenDevice() ?: return false
        return hidConnectionState(device) == BluetoothProfile.STATE_CONNECTED
    }

    private fun hidConnectionState(device: BluetoothDevice): Int =
        hidHost?.getConnectionState(device) ?: BluetoothProfile.STATE_DISCONNECTED

    private fun publishPendingDockStatus(state: StylusDockConnectionState) {
        publishDockStatus(state)
        workerHandler.removeCallbacks(connectionTimeout)
        workerHandler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
    }

    private fun publishDockStatus(state: StylusDockConnectionState, battery: Int? = null) {
        if (!docked) return
        if (state != StylusDockConnectionState.CONNECTING &&
            state != StylusDockConnectionState.PAIRING
        ) {
            workerHandler.removeCallbacks(connectionTimeout)
        }

        val presentation =
            DockPresentation(
                state,
                battery.takeIf { state == StylusDockConnectionState.CONNECTED },
            )
        if (presentation == lastDockPresentation) return
        lastDockPresentation = presentation

        mainHandler.post {
            if (running) {
                try {
                    listener.onDockStatusChanged(presentation.state, presentation.battery)
                } catch (exception: RuntimeException) {
                    Log.e(TAG, "Could not deliver stylus dock status", exception)
                }
            }
        }
    }

    private fun currentPenDevice(): BluetoothDevice? {
        val bluetoothAdapter = adapter ?: return null
        val address = currentAddress ?: readStoredAddress() ?: return null
        return try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (exception: IllegalArgumentException) {
            null
        }
    }

    private fun isCurrentPen(device: BluetoothDevice): Boolean {
        val expectedAddress = currentAddress ?: readStoredAddress() ?: return false
        return device.address.equals(expectedAddress, ignoreCase = true)
    }

    private fun readStoredAddress(): String? =
        normalizeAddress(
            Settings.Secure.getString(context.contentResolver, STORED_PEN_ADDRESS),
        )

    private fun closeGatt() {
        batteryCharacteristic = null
        firmwareRevisionCharacteristic = null
        pnpIdCharacteristic = null
        deviceInformation = StylusDeviceInformation()
        val bluetoothGatt = gatt
        gatt = null
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect()
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not disconnect pen GATT", exception)
            }
            try {
                bluetoothGatt.close()
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not close pen GATT", exception)
            }
        }
    }

    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private companion object {
        private const val TAG = "LiuqinStylusBluetooth"
        private const val WORKER_NAME = "LiuqinStylusBluetooth"
        private const val STORED_PEN_ADDRESS = "miui_bluetooth_store_pen_address"
        private const val CHARGE_STATE_PAIRING = 4
        private const val OLD_PEN_REMOVE_RETRY_MS = 3_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val ACTION_HID_CONNECTION_STATE_CHANGED =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED"

        private const val STOCK_PEN_NAMES =
            "Xiaomi Smart Pen,SWD LUX Pen,Xiaomi Focus Pen,N83C pen," +
                "Redmi Smart Pen,POCO Smart Pen,REDMI Smart Pen," +
                "Xiaomi P81C Pen,POCO Focus Pen"

        private val BATTERY_RANGE = 0..100
        private val BATTERY_SERVICE_UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val DEVICE_INFORMATION_SERVICE_UUID =
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REVISION_UUID =
            UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
        private val PNP_ID_UUID =
            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val RAW_MAC = Regex("^[0-9A-Fa-f]{12}$")
        private val FORMATTED_MAC = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        private fun normalizeAddress(value: String?): String? {
            if (value == null) return null
            if (FORMATTED_MAC.matches(value)) return value.uppercase()
            if (!RAW_MAC.matches(value)) return null
            return value.uppercase().chunked(2).joinToString(":")
        }
    }
}
