package com.soryx.remote

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Injects key events directly via the hidden InputManager#injectInputEvent, the same
 * mechanism the `input` shell command uses internally. Only works when the calling
 * process is root (uid 0) — normal apps don't hold INJECT_EVENTS, but Android's
 * permission checks special-case uid 0. This class is loaded both by the normal app
 * process (unused there) and by the standalone root daemon process (InputDaemon.kt).
 */
object InputInjector {
    private const val MODE_ASYNC = 0

    private val inputManagerClass = Class.forName("android.hardware.input.InputManager")
    private val instance = inputManagerClass.getMethod("getInstance").invoke(null)
    private val injectMethod = inputManagerClass.getMethod(
        "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
    )

    fun injectKeyEvent(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        inject(buildKeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode))
        inject(buildKeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode))
    }

    fun injectText(text: String) {
        val keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = keyCharacterMap.getEvents(text.toCharArray()) ?: return
        for (event in events) inject(event)
    }

    private fun buildKeyEvent(downTime: Long, eventTime: Long, action: Int, code: Int): KeyEvent {
        return KeyEvent(
            downTime, eventTime, action, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
        )
    }

    private fun inject(event: InputEvent) {
        injectMethod.invoke(instance, event, MODE_ASYNC)
    }
}
