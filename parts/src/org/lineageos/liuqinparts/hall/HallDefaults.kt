/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.hall

import android.content.Context
import android.provider.Settings
import android.util.Log

object HallDefaults {
    fun migrate(context: Context) {
        val resolver = context.contentResolver
        val version = try {
            Settings.Global.getInt(resolver, MIGRATION_VERSION_KEY, 0)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not read Hall migration version", exception)
            return
        }
        if (version >= CURRENT_MIGRATION_VERSION) return

        val lidBehavior = try {
            Settings.Global.getInt(
                resolver,
                Settings.Global.LID_BEHAVIOR,
                LID_BEHAVIOR_NONE,
            )
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not read lid behavior", exception)
            return
        }

        val migrated = lidBehavior == LID_BEHAVIOR_SLEEP || try {
            // Older liuqin builds initialized this to NONE because their
            // framework overlay did not advertise lid-controlled sleep. This
            // device uses the fixed stock wake/sleep policy, so normalize any
            // imported AOSP LOCK or invalid value as well.
            Settings.Global.putInt(
                resolver,
                Settings.Global.LID_BEHAVIOR,
                LID_BEHAVIOR_SLEEP,
            )
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not enable lid-controlled sleep", exception)
            false
        }

        if (!migrated || !writeMigrationVersion(context)) {
            Log.w(TAG, "Hall default migration was incomplete; it will be retried")
        }
    }

    private fun writeMigrationVersion(context: Context): Boolean = try {
        Settings.Global.putInt(
            context.contentResolver,
            MIGRATION_VERSION_KEY,
            CURRENT_MIGRATION_VERSION,
        )
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Could not record Hall migration version", exception)
        false
    }

    private const val MIGRATION_VERSION_KEY = "liuqin_hall_defaults_version"
    // Version 2 removes the configurable cover switch and restores the fixed
    // stock policy for installs that used the intermediate switch.
    private const val CURRENT_MIGRATION_VERSION = 2
    private const val LID_BEHAVIOR_NONE = 0
    private const val LID_BEHAVIOR_SLEEP = 1
    private const val TAG = "LiuqinParts.HallDefaults"
}
