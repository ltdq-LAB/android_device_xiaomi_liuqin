/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.liuqinparts.R
import org.lineageos.liuqinparts.settings.SecureSettings

class StylusSettingsFragment : SettingsBasePreferenceFragment() {
    private val secureSettings by lazy(LazyThreadSafetyMode.NONE) {
        SecureSettings.from(requireContext())
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.stylus_settings, rootKey)
        bindActionPreference(
            StylusSettingsContract.PRIMARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
        )
        bindActionPreference(
            StylusSettingsContract.SECONDARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
        )
        bindQuickNotePreference()
    }

    override fun onResume() {
        super.onResume()
        refreshActionPreference(
            StylusSettingsContract.PRIMARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
        )
        refreshActionPreference(
            StylusSettingsContract.SECONDARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
        )
        refreshQuickNotePreference()
    }

    private fun bindActionPreference(key: String, defaultValue: String) {
        val preference = findPreference<ListPreference>(key) ?: return
        preference.isPersistent = false
        refreshActionPreference(key, defaultValue)
        preference.setOnPreferenceChangeListener { _, newValue ->
            val action = newValue as? String
                ?: return@setOnPreferenceChangeListener false
            if (action !in StylusSettingsContract.Action.SUPPORTED) {
                return@setOnPreferenceChangeListener false
            }
            if (!secureSettings.putString(key, action)) {
                Toast.makeText(
                    requireContext(),
                    R.string.stylus_setting_write_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnPreferenceChangeListener false
            }
            updateSummary(preference, action)
            true
        }
    }

    private fun refreshActionPreference(key: String, defaultValue: String) {
        val preference = findPreference<ListPreference>(key) ?: return
        val action = StylusSettingsContract.sanitizeAction(
            secureSettings.getString(key),
            defaultValue,
        )
        preference.value = action
        updateSummary(preference, action)
    }

    private fun bindQuickNotePreference() {
        val preference =
            findPreference<SwitchPreferenceCompat>(
                StylusSettingsContract.STYLUS_QUICK_NOTE_SCREEN_OFF,
            ) ?: return
        preference.isPersistent = false
        refreshQuickNotePreference()
        preference.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean
                ?: return@setOnPreferenceChangeListener false
            if (!putQuickNoteEnabled(enabled)) {
                Toast.makeText(
                    requireContext(),
                    R.string.stylus_quick_note_write_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    private fun refreshQuickNotePreference() {
        val preference =
            findPreference<SwitchPreferenceCompat>(
                StylusSettingsContract.STYLUS_QUICK_NOTE_SCREEN_OFF,
            ) ?: return
        preference.isChecked =
            try {
                Settings.System.getInt(
                    requireContext().contentResolver,
                    StylusSettingsContract.STYLUS_QUICK_NOTE_SCREEN_OFF,
                    if (StylusSettingsContract.DEFAULT_STYLUS_QUICK_NOTE_SCREEN_OFF) 1 else 0,
                ) != 0
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not read the stylus wake setting", exception)
                StylusSettingsContract.DEFAULT_STYLUS_QUICK_NOTE_SCREEN_OFF
            }
    }

    private fun putQuickNoteEnabled(enabled: Boolean): Boolean =
        try {
            Settings.System.putInt(
                requireContext().contentResolver,
                StylusSettingsContract.STYLUS_QUICK_NOTE_SCREEN_OFF,
                if (enabled) 1 else 0,
            )
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not write the stylus wake setting", exception)
            false
        }

    private fun updateSummary(preference: ListPreference, action: String) {
        val index = preference.findIndexOfValue(action)
        preference.summary = if (index >= 0) preference.entries[index] else null
    }

    companion object {
        private const val TAG = "LiuqinParts.StylusSettings"
    }
}
