package com.soryx.remote

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Non-root fallback for TVs with no `su` available (e.g. real certified Android TV
 * sets like this Philco/Changhong box on Android 14). Only used when the daemon and
 * su paths fail — rooted boxes never touch this, since it's a no-op unless the user
 * has manually granted Accessibility access in Settings.
 */
class SoryxAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: SoryxAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        private val KEYCODE_TO_GLOBAL_ACTION = mapOf(
            KeyEvent.KEYCODE_HOME to GLOBAL_ACTION_HOME,
            KeyEvent.KEYCODE_BACK to GLOBAL_ACTION_BACK,
            KeyEvent.KEYCODE_APP_SWITCH to GLOBAL_ACTION_RECENTS,
            KeyEvent.KEYCODE_POWER to GLOBAL_ACTION_POWER_DIALOG,
            KeyEvent.KEYCODE_DPAD_UP to GLOBAL_ACTION_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to GLOBAL_ACTION_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to GLOBAL_ACTION_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to GLOBAL_ACTION_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER to GLOBAL_ACTION_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER to GLOBAL_ACTION_DPAD_CENTER
        )

        fun tryKeyEvent(keyCode: Int): Boolean {
            val svc = instance ?: return false
            val action = KEYCODE_TO_GLOBAL_ACTION[keyCode] ?: return false
            return try {
                svc.performGlobalAction(action)
            } catch (_: Exception) {
                false
            }
        }

        fun tryText(text: String): Boolean {
            val svc = instance ?: return false
            return try {
                val root = svc.rootInActiveWindow ?: return false
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Exception) {
                false
            }
        }
    }
}
