/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import org.lineageos.liuqinparts.hall.HallDefaults
import org.lineageos.liuqinparts.stylus.StylusService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Bluetooth, Hall and the touch controller are device-global. Running
        // one observer per Android user would duplicate pairing and toasts.
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        HallDefaults.migrate(context)

        try {
            context.startService(Intent(context, StylusService::class.java))
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not start the stylus helper", exception)
        }
    }

    companion object {
        private const val TAG = "LiuqinParts.Boot"
    }
}
