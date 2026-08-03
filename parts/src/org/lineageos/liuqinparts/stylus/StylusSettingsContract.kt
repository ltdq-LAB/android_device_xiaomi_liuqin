/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

/**
 * Stable settings contract shared by the settings UI and stylus helper.
 *
 * The stock OS3.0.7.0 defaults are primary button -> quick notes and secondary
 * button -> screenshot. The stock screen-off quick-note mode is disabled by
 * default. Values are deliberately plain strings so the service does not
 * depend on AndroidX preference classes.
 */
object StylusSettingsContract {
    const val PRIMARY_BUTTON_ACTION = "liuqin_stylus_primary_button_action"
    const val SECONDARY_BUTTON_ACTION = "liuqin_stylus_secondary_button_action"
    const val STYLUS_QUICK_NOTE_SCREEN_OFF = "stylus_quick_note_screen_off"

    object Action {
        const val NONE = "none"
        const val OPEN_NOTES = "open_notes"
        const val SCREENSHOT = "screenshot"
        const val BACK = "back"
        const val HOME = "home"
        const val RECENTS = "recents"
        const val MEDIA_PLAY_PAUSE = "media_play_pause"

        val SUPPORTED: Set<String> = setOf(
            NONE,
            OPEN_NOTES,
            SCREENSHOT,
            BACK,
            HOME,
            RECENTS,
            MEDIA_PLAY_PAUSE,
        )
    }

    const val DEFAULT_PRIMARY_BUTTON_ACTION = Action.OPEN_NOTES
    const val DEFAULT_SECONDARY_BUTTON_ACTION = Action.SCREENSHOT
    const val DEFAULT_STYLUS_QUICK_NOTE_SCREEN_OFF = false

    fun sanitizeAction(value: String?, defaultValue: String): String =
        value?.takeIf(Action.SUPPORTED::contains) ?: defaultValue
}
