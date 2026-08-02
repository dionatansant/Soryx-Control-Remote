package com.soryx.remote

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Keeps a single `su` session alive so commands don't pay the su fork/elevation
 * cost on every button press (that overhead was the main source of input lag).
 */
object RootShell {
    private const val TAG = "RootShell"

    @Volatile private var process: Process? = null
    private var writer: BufferedWriter? = null

    @Synchronized
    private fun ensureStarted() {
        val current = process
        if (current != null && isAlive(current)) return

        val newProcess = ProcessBuilder("su").redirectErrorStream(true).start()
        process = newProcess
        writer = BufferedWriter(OutputStreamWriter(newProcess.outputStream))
        drain(newProcess)
    }

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun drain(p: Process) {
        Thread {
            try {
                val buffer = ByteArray(1024)
                val input = p.inputStream
                while (true) {
                    if (input.read(buffer) == -1) break
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    fun exec(command: String) {
        try {
            ensureStarted()
            writer?.apply {
                write(command)
                newLine()
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "su session died, will restart on next command", e)
            process = null
            writer = null
        }
    }
}
