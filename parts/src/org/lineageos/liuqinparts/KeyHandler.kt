/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("DEPRECATION")

package org.lineageos.liuqinparts

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.SparseArray
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.KeyEvent
import android.view.MotionEvent
import com.android.internal.os.DeviceKeyHandler
import org.lineageos.liuqinparts.stylus.StylusActions
import org.lineageos.liuqinparts.stylus.StylusSettingsContract
import org.lineageos.liuqinparts.stylus.XiaomiStylusDevice
import java.util.ArrayDeque

/**
 * Global liuqin stylus-button handler loaded into system_server.
 *
 * OS3.0.7 maps PAGE_DOWN (93) to the primary button and PAGE_UP (92) to the
 * secondary button. Both stock shortcuts use a 380 ms long press. The original
 * type-1/type-2 path still passes the page-key events to the foreground app,
 * so this handler observes them without consuming them.
 */
class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val pendingActions = SparseArray<Runnable>()
    private val stylusTouchMonitor = StylusTouchMonitor(context, handler)
    private val recentEvents = HashSet<EventIdentity>()
    private val recentEventOrder = ArrayDeque<EventIdentity>()

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        val setting = when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> ButtonSetting(
                StylusSettingsContract.PRIMARY_BUTTON_ACTION,
                StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
            )

            KeyEvent.KEYCODE_PAGE_UP -> ButtonSetting(
                StylusSettingsContract.SECONDARY_BUTTON_ACTION,
                StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
            )

            else -> return event
        }

        if (!XiaomiStylusDevice.isStylus(event.device)) return event

        // PhoneWindowManager invokes a DeviceKeyHandler once before queueing
        // and again before dispatching. Those callbacks can run on different
        // input threads. Snapshot and post the event so all mutable gesture
        // state is confined to the system-server main looper; rememberEvent()
        // removes the duplicate policy-stage delivery.
        val eventIdentity = EventIdentity(
            event.deviceId,
            event.keyCode,
            event.scanCode,
            event.action,
            event.repeatCount,
            event.downTime,
            event.eventTime,
        )
        handler.post { handleEventOnMain(eventIdentity, setting) }
        return event
    }

    private fun handleEventOnMain(event: EventIdentity, setting: ButtonSetting) {
        if (!rememberEvent(event)) return
        if (!powerManager.isInteractive) {
            cancelPending(event.keyCode)
            stylusTouchMonitor.cancel(event.keyCode)
            return
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && pendingActions[event.keyCode] == null) {
                    val action = readAction(setting)
                    val runnable = Runnable {
                        pendingActions.remove(event.keyCode)
                        if (!powerManager.isInteractive) return@Runnable
                        val execute = Runnable { executeAction(action) }
                        if (action == StylusSettingsContract.Action.OPEN_NOTES) {
                            stylusTouchMonitor.arm(event.keyCode, execute)
                        } else {
                            execute.run()
                        }
                    }
                    pendingActions.put(event.keyCode, runnable)
                    handler.postAtTime(runnable, event.downTime + LONG_PRESS_TIMEOUT_MS)
                }
            }

            KeyEvent.ACTION_UP -> {
                cancelPending(event.keyCode)
                stylusTouchMonitor.cancel(event.keyCode)
            }

            else -> {
                cancelPending(event.keyCode)
                stylusTouchMonitor.cancel(event.keyCode)
            }
        }

    }

    private fun rememberEvent(event: EventIdentity): Boolean {
        if (!recentEvents.add(event)) return false
        recentEventOrder.addLast(event)
        while (recentEventOrder.size > RECENT_EVENT_LIMIT) {
            recentEvents.remove(recentEventOrder.removeFirst())
        }
        return true
    }

    private fun readAction(setting: ButtonSetting): String {
        val value = Settings.Secure.getStringForUser(
            context.contentResolver,
            setting.key,
            UserHandle.USER_CURRENT,
        )
        return StylusSettingsContract.sanitizeAction(value, setting.defaultValue)
    }

    private fun cancelPending(keyCode: Int) {
        val runnable = pendingActions[keyCode] ?: return
        handler.removeCallbacks(runnable)
        pendingActions.remove(keyCode)
    }

    private fun executeAction(action: String) {
        try {
            StylusActions.run(context, handler, action)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Stylus action $action failed", exception)
        }
    }

    /**
     * Stock quick note is a two-step gesture: hold the configured note button,
     * then touch the screen with the pen while the button remains held. The
     * original InputSettings mask window is MIUI-only; a read-only gesture
     * monitor reproduces its trigger semantics without stealing the event from
     * the foreground app.
     */
    private class StylusTouchMonitor(
        context: Context,
        private val handler: Handler,
    ) {
        private val inputManager = context.getSystemService(InputManager::class.java)

        private var inputMonitor: InputMonitor? = null
        private var inputReceiver: InputEventReceiver? = null
        private var armedKeyCode = KeyEvent.KEYCODE_UNKNOWN
        private var armedAction: Runnable? = null
        private var timeoutAction: Runnable? = null

        fun arm(keyCode: Int, action: Runnable): Boolean {
            if (!ensureMonitor()) return false
            clearArm()
            armedKeyCode = keyCode
            armedAction = action
            timeoutAction = Runnable {
                if (armedKeyCode == keyCode) clearArm()
            }.also { handler.postDelayed(it, QUICK_NOTE_TIMEOUT_MS) }
            return true
        }

        fun cancel(keyCode: Int) {
            if (armedKeyCode != keyCode) return
            clearArm()
        }

        private fun clearArm() {
            timeoutAction?.let(handler::removeCallbacks)
            timeoutAction = null
            armedKeyCode = KeyEvent.KEYCODE_UNKNOWN
            armedAction = null
        }

        private fun ensureMonitor(): Boolean {
            if (inputReceiver != null) return true
            val manager = inputManager ?: return false
            return try {
                val monitor = manager.monitorGestureInput(MONITOR_NAME, Display.DEFAULT_DISPLAY)
                val receiver = object : InputEventReceiver(monitor.inputChannel, handler.looper) {
                    override fun onInputEvent(event: InputEvent) {
                        try {
                            if (event is MotionEvent) handleMotionEvent(event)
                        } finally {
                            finishInputEvent(event, false)
                        }
                    }
                }
                inputMonitor = monitor
                inputReceiver = receiver
                true
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Could not monitor the quick-note pen touch", exception)
                false
            }
        }

        private fun handleMotionEvent(event: MotionEvent) {
            if (armedAction == null || event.actionMasked != MotionEvent.ACTION_DOWN ||
                !event.isFromSource(InputDevice.SOURCE_STYLUS) ||
                event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_STYLUS
            ) {
                return
            }

            val action = armedAction
            clearArm()
            // InputEventReceiver already runs on the handler looper. Posting
            // keeps action execution outside the native input callback frame.
            if (action != null) handler.post(action)
        }

        companion object {
            private const val MONITOR_NAME = "Liuqin stylus quick note"
            private const val QUICK_NOTE_TIMEOUT_MS = 10_000L
        }
    }

    private data class ButtonSetting(val key: String, val defaultValue: String)

    private data class EventIdentity(
        val deviceId: Int,
        val keyCode: Int,
        val scanCode: Int,
        val action: Int,
        val repeatCount: Int,
        val downTime: Long,
        val eventTime: Long,
    )

    companion object {
        private const val TAG = "LiuqinParts.KeyHandler"
        private const val LONG_PRESS_TIMEOUT_MS = 380L
        private const val RECENT_EVENT_LIMIT = 512
    }
}
