/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.settings

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log

class SecureSettings private constructor(
    private val contentResolver: ContentResolver,
) {
    fun getString(key: String): String? = try {
        Settings.Secure.getString(contentResolver, key)
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Could not read secure setting $key", exception)
        null
    }

    fun putString(key: String, value: String): Boolean = try {
        Settings.Secure.putString(contentResolver, key, value)
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Could not write secure setting $key", exception)
        false
    }

    companion object {
        private const val TAG = "LiuqinParts.Settings"

        fun from(context: Context): SecureSettings = SecureSettings(context.contentResolver)
    }
}
