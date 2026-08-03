/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

/** Standard Device Information values read by the liuqin stock helper. */
internal data class StylusDeviceInformation(
    val firmwareRevision: String? = null,
    val vendorIdSource: Int? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val productVersion: Int? = null,
) {
    fun withFirmwareRevision(value: ByteArray): StylusDeviceInformation =
        copy(
            firmwareRevision =
                value
                    .toString(Charsets.UTF_8)
                    .trimEnd('\u0000')
                    .takeIf(String::isNotBlank),
        )

    fun withPnpId(value: ByteArray): StylusDeviceInformation {
        if (value.size < PNP_ID_LENGTH) return this
        return copy(
            vendorIdSource = value[0].toInt() and 0xff,
            vendorId = value.readUnsignedLittleEndian16(1),
            productId = value.readUnsignedLittleEndian16(3),
            productVersion = value.readUnsignedLittleEndian16(5),
        )
    }

    private fun ByteArray.readUnsignedLittleEndian16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        private const val PNP_ID_LENGTH = 7
    }
}
