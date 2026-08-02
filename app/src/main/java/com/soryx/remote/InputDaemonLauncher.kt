package com.soryx.remote

import android.content.Context

/** (re)launches the root input daemon as a detached background process via the persistent su shell. */
object InputDaemonLauncher {
    private const val COOLDOWN_MS = 3000L

    @Volatile private var lastLaunchAttempt = 0L

    @Synchronized
    fun ensureRunning(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastLaunchAttempt < COOLDOWN_MS) return
        lastLaunchAttempt = now

        val apkPath = context.applicationInfo.sourceDir
        RootShell.exec(
            "(app_process -Djava.class.path=$apkPath /system/bin com.soryx.remote.InputDaemonKt >/dev/null 2>&1 &)"
        )
    }
}
