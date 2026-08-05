package com.soryx.remote

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
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

    /**
     * GLOBAL_ACTION_DPAD_* only exists from API 33 (Android 13) onward, so on older
     * TV boxes without root — very common on cheap generic Android TV sticks —
     * performGlobalAction() silently returns false for them. This walks the
     * accessibility tree to find the nearest focusable node in the requested
     * direction and focuses it, which works on every API level this app supports.
     */
    private fun moveFocus(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (current == null) {
            val first = findFirstFocusable(root) ?: return false
            return focusNode(first)
        }

        val fromBounds = Rect().also { current.getBoundsInScreen(it) }
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectFocusable(root, candidates)

        var best: AccessibilityNodeInfo? = null
        var bestScore = Long.MAX_VALUE
        for (node in candidates) {
            if (node == current) continue
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val score = directionalScore(fromBounds, bounds, direction) ?: continue
            if (score < bestScore) {
                bestScore = score
                best = node
            }
        }
        return best?.let { focusNode(it) } ?: false
    }

    private fun clickFocusedNode(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun focusNode(node: AccessibilityNodeInfo): Boolean {
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        val inputFocused = if (node.isFocusable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } else {
            false
        }
        return focused || inputFocused
    }

    private fun findFirstFocusable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocusable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstFocusable(child)?.let { return it }
        }
        return null
    }

    private fun collectFocusable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (out.size >= MAX_FOCUS_CANDIDATES) return
        if (node.isVisibleToUser && (node.isFocusable || node.isClickable)) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectFocusable(child, out)
            if (out.size >= MAX_FOCUS_CANDIDATES) return
        }
    }

    /** Lower is better. Primary-axis distance dominates; misalignment on the
     *  perpendicular axis is penalized so neighbors roughly in line win ties. */
    private fun directionalScore(from: Rect, to: Rect, direction: Int): Long? {
        val primary: Int
        val perpendicular: Int
        when (direction) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (to.centerX() <= from.centerX()) return null
                primary = to.left - from.right
                perpendicular = to.centerY() - from.centerY()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (to.centerX() >= from.centerX()) return null
                primary = from.left - to.right
                perpendicular = to.centerY() - from.centerY()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (to.centerY() <= from.centerY()) return null
                primary = to.top - from.bottom
                perpendicular = to.centerX() - from.centerX()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (to.centerY() >= from.centerY()) return null
                primary = from.top - to.bottom
                perpendicular = to.centerX() - from.centerX()
            }
            else -> return null
        }
        val primaryDist = primary.coerceAtLeast(0).toLong()
        return primaryDist * primaryDist + perpendicular.toLong() * perpendicular * 4
    }

    companion object {
        private const val MAX_FOCUS_CANDIDATES = 500

        @Volatile private var instance: SoryxAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        private val KEYCODE_TO_GLOBAL_ACTION = mapOf(
            KeyEvent.KEYCODE_HOME to GLOBAL_ACTION_HOME,
            KeyEvent.KEYCODE_BACK to GLOBAL_ACTION_BACK,
            KeyEvent.KEYCODE_APP_SWITCH to GLOBAL_ACTION_RECENTS,
            KeyEvent.KEYCODE_POWER to GLOBAL_ACTION_POWER_DIALOG
        ).let { base ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base + mapOf(
                    KeyEvent.KEYCODE_DPAD_UP to GLOBAL_ACTION_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN to GLOBAL_ACTION_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT to GLOBAL_ACTION_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT to GLOBAL_ACTION_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER to GLOBAL_ACTION_DPAD_CENTER
                )
            } else {
                base
            }
        }

        fun tryKeyEvent(keyCode: Int): Boolean {
            val svc = instance ?: return false

            KEYCODE_TO_GLOBAL_ACTION[keyCode]?.let { action ->
                try {
                    if (svc.performGlobalAction(action)) return true
                } catch (_: Exception) {
                }
            }

            return try {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> svc.moveFocus(keyCode)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> svc.clickFocusedNode()
                    else -> false
                }
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
